package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.ExecutionContext.Implicits.global

object TapiTxxCollector {
  import TapiTxx._
  case class ConnectHost(host: String)
  case object RaiseStart
  case object HoldStart
  case object DownStart
  case object CalibrateEnd
  case object ReadRegister
  import Protocol.ProtocolParam

  var count = 0
  def start(protocolParam: ProtocolParam, props: Props)(implicit context: ActorContext) = {

    val model = props.actorClass().getName.split('.')
    val actorName = s"${model(model.length - 1)}_${count}"
    count += 1
    val collector = context.actorOf(props, name = actorName)
    Logger.info(s"$actorName is created.")

    val host = protocolParam.host.get
    collector ! ConnectHost(host)
    collector
  }

}

import TapiTxx._
abstract class TapiTxxCollector(instId: String, modelReg: ModelReg, tapiConfig: TapiConfig) extends Actor {
  var timerOpt: Option[Cancellable] = None
  import DataCollectManager._
  import TapiTxxCollector._
  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  var masterOpt: Option[ModbusMaster] = None
  var (collectorState, instrumentStatusTypesOpt) = {
    val instList = Instrument.getInstrument(instId)
    if (!instList.isEmpty) {
      val inst = instList(0)
      (inst.state, inst.statusType)
    } else
      (MonitorStatus.NormalStat, None)
  }

  Logger.info(s"$self state=${MonitorStatus.map(collectorState).desp}")

  val InputKey = "Input"
  val HoldingKey = "Holding"
  val ModeKey = "Mode"
  val WarnKey = "Warn"

  def probeInstrumentStatusType = {
    Logger.info("Probing supported modbus registers...")
    import com.serotonin.modbus4j.locator.BaseLocator
    import com.serotonin.modbus4j.code.DataType

    def probeInputReg(addr: Int, desc: String) = {
      try {
        val locator = BaseLocator.inputRegister(tapiConfig.slaveID, addr, DataType.FOUR_BYTE_FLOAT)
        masterOpt.get.getValue(locator)
        true
      } catch {
        case ex: Throwable =>
          Logger.error(ex.getMessage, ex)
          Logger.info(s"$addr $desc is not supported.")
          false
      }
    }

    def probeHoldingReg(addr: Int, desc: String) = {
      try {
        val locator = BaseLocator.holdingRegister(tapiConfig.slaveID, addr, DataType.FOUR_BYTE_FLOAT)
        masterOpt.get.getValue(locator)
        true
      } catch {
        case ex: Throwable =>
          Logger.info(s"$addr $desc is not supported.")
          false
      }
    }

    def probeInputStatus(addr: Int, desc: String) = {
      try {
        val locator = BaseLocator.inputStatus(tapiConfig.slaveID, addr)
        masterOpt.get.getValue(locator)
        true
      } catch {
        case ex: Throwable =>
          Logger.info(s"$addr $desc is not supported.")
          false
      }
    }

    val inputRegs =
      for { r <- modelReg.inputRegs if probeInputReg(r.addr, r.desc) }
        yield r

    val inputRegStatusType =
      for {
        r <- inputRegs
      } yield InstrumentStatusType(key = s"$InputKey${r.addr}", addr = r.addr, desc = r.desc, unit = r.unit)

    val holdingRegs =
      for (r <- modelReg.holdingRegs if probeHoldingReg(r.addr, r.desc))
        yield r

    val holdingRegStatusType =
      for {
        r <- holdingRegs
      } yield InstrumentStatusType(key = s"$HoldingKey${r.addr}", addr = r.addr, desc = r.desc, unit = r.unit)

    val modeRegs =
      for (r <- modelReg.modeRegs if probeInputStatus(r.addr, r.desc))
        yield r

    val modeRegStatusType =
      for {
        r <- modeRegs
      } yield InstrumentStatusType(key = s"$ModeKey${r.addr}", addr = r.addr, desc = r.desc, unit = "-")

    val warnRegs =
      for (r <- modelReg.warnRegs if probeInputStatus(r.addr, r.desc))
        yield r

    val warnRegStatusType =
      for {
        r <- warnRegs
      } yield InstrumentStatusType(key = s"$WarnKey${r.addr}", addr = r.addr, desc = r.desc, unit = "-")

    Logger.info("Finish probing.")
    inputRegStatusType ++ holdingRegStatusType ++ modeRegStatusType ++ warnRegStatusType
  }

  def readReg(statusTypeList: List[InstrumentStatusType]) = {
    import com.serotonin.modbus4j.BatchRead
    val batch = new BatchRead[Integer]

    import com.serotonin.modbus4j.locator.BaseLocator
    import com.serotonin.modbus4j.code.DataType

    for {
      st_idx <- statusTypeList.zipWithIndex
      st = st_idx._1
      idx = st_idx._2
    } {
      if (st.key.startsWith(InputKey)) {
        batch.addLocator(idx, BaseLocator.inputRegister(tapiConfig.slaveID, st.addr, DataType.FOUR_BYTE_FLOAT))
      } else if (st.key.startsWith(HoldingKey)) {
        batch.addLocator(idx, BaseLocator.holdingRegister(tapiConfig.slaveID, st.addr, DataType.FOUR_BYTE_FLOAT))
      } else if (st.key.startsWith(ModeKey) || st.key.startsWith(WarnKey)) {
        batch.addLocator(idx, BaseLocator.inputStatus(tapiConfig.slaveID, st.addr))
      } else {
        throw new Exception(s"Unexpected key ${st.key}")
      }
    }

    batch.setContiguousRequests(true)

    val results = masterOpt.get.send(batch)
    val inputs =
      for {
        st_idx <- statusTypeList.zipWithIndex if st_idx._1.key.startsWith(InputKey)
        idx = st_idx._2
      } yield (st_idx._1, results.getFloatValue(idx).toFloat)

    val holdings =
      for {
        st_idx <- statusTypeList.zipWithIndex if st_idx._1.key.startsWith(HoldingKey)
        idx = st_idx._2
      } yield (st_idx._1, results.getFloatValue(idx).toFloat)

    val modes =
      for {
        st_idx <- statusTypeList.zipWithIndex if st_idx._1.key.startsWith(ModeKey)
        idx = st_idx._2
      } yield (st_idx._1, results.getValue(idx).asInstanceOf[Boolean])

    val warns =
      for {
        st_idx <- statusTypeList.zipWithIndex if st_idx._1.key.startsWith(WarnKey)
        idx = st_idx._2
      } yield (st_idx._1, results.getValue(idx).asInstanceOf[Boolean])

    ModelRegValue(inputs, holdings, modes, warns)
  }

  var connected = false
  var oldModelReg: Option[ModelRegValue] = None
  import Alarm._

  def receive = normalReceive

  import scala.concurrent.Future
  import scala.concurrent.blocking
  def readRegFuture(recordCalibration: Boolean) =
    Future {
      blocking {
        try {
          if (instrumentStatusTypesOpt.isDefined) {
            val regValues = readReg(instrumentStatusTypesOpt.get)
            regValueReporter(regValues)(recordCalibration)
          }
          connected = true
        } catch {
          case ex: Exception =>
            Logger.error(ex.getMessage, ex)
            if (connected)
              log(instStr(instId), Level.ERR, s"${ex.getMessage}")

            connected = false
        } finally {
          import scala.concurrent.duration._
          timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(2, SECONDS), self, ReadRegister))
        }
      }
    }

  def executeCalibration(calibrationType: CalibrationType) {
    if (tapiConfig.monitorTypes.isEmpty)
      Logger.error("There is no monitor type for calibration.")
    else if (!connected)
      Logger.error("Cannot calibration before connected.")
    else {
      Future {
        blocking {
          startCalibration(calibrationType, tapiConfig.monitorTypes.get)
        }
      } onFailure ({
        case ex: Throwable =>
          ModelHelper.logInstrumentError(instId, s"${self.path.name}: ${ex.getMessage}. ", ex)
      })
    }
  }

  def normalReceive(): Receive = {
    case ConnectHost(host) =>
      Logger.info(s"${self.toString()}: connect $host")
      Future {
        blocking {
          try {
            val ipParameters = new IpParameters()
            ipParameters.setHost(host);
            ipParameters.setPort(502);
            val modbusFactory = new ModbusFactory()

            masterOpt = Some(modbusFactory.createTcpMaster(ipParameters, true))
            masterOpt.get.setTimeout(4000)
            masterOpt.get.setRetries(1)
            masterOpt.get.setConnected(true)

            masterOpt.get.init();
            connected = true

            if (instrumentStatusTypesOpt.isEmpty) {
              instrumentStatusTypesOpt = Some(probeInstrumentStatusType)
              Instrument.updateStatusType(instId, instrumentStatusTypesOpt.get)
            }
            import scala.concurrent.duration._
            timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadRegister))
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              log(instStr(instId), Level.ERR, s"無法連接:${ex.getMessage}")
              import scala.concurrent.duration._

              Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost(host))
          }
        }
      }

    case ReadRegister =>
      readRegFuture(false)

    case SetState(id, state) =>
      if (state == MonitorStatus.ZeroCalibrationStat) {
        Logger.error(s"Unexpected command: SetState($state)")
      } else {
        collectorState = state
        Instrument.setState(instId, collectorState)
      }
      Logger.info(s"$self => ${MonitorStatus.map(collectorState).desp}")

    case AutoCalibration(instId) =>
      executeCalibration(AutoZero)

    case ManualZeroCalibration(instId) =>
      executeCalibration(ManualZero)

    case ManualSpanCalibration(instId) =>
      executeCalibration(ManualSpan)

    case ExecuteSeq(seq, on) =>
      executeSeq(seq, on)
  }

  // Only for T700
  def executeSeq(seq: Int, on: Boolean) {}

  def startCalibration(calibrationType: CalibrationType, monitorTypes: List[MonitorType.Value]) {
    import scala.concurrent.duration._

    Logger.info(s"start calibrating ${monitorTypes.mkString(",")}")
    val timer = if (!calibrationType.zero &&
      tapiConfig.calibratorPurgeTime.isDefined && tapiConfig.calibratorPurgeTime.get != 0)
      purgeCalibrator
    else
      Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, RaiseStart)

    import com.github.nscala_time.time.Imports._
    val endState = collectorState
    context become calibration(calibrationType, DateTime.now, false, List.empty[ReportData],
      List.empty[(MonitorType.Value, Double)], endState, timer)
  }

  def calibrationErrorHandler(id: String, timer: Cancellable, endState: String): PartialFunction[Throwable, Unit] = {
    case ex: Exception =>
      timer.cancel()
      logInstrumentError(id, s"${self.path.name}: ${ex.getMessage}. ", ex)
      resetToNormal
      Instrument.setState(id, endState)
      collectorState = endState
      context become normalReceive
  }

  import com.github.nscala_time.time.Imports._
  def calibration(calibrationType: CalibrationType, startTime: DateTime, recordCalibration: Boolean, calibrationReadingList: List[ReportData],
                  zeroReading: List[(MonitorType.Value, Double)],
                  endState: String, timer: Cancellable): Receive = {
    case ConnectHost(host) =>
      Logger.error("unexpected ConnectHost msg")

    case ReadRegister =>
      readRegFuture(recordCalibration)

    case SetState(id, targetState) =>
      if (targetState == MonitorStatus.ZeroCalibrationStat) {
        Logger.info("Already in calibration. Ignore it")
      } else if (targetState == MonitorStatus.NormalStat) {
        Logger.info("Cancel calibration.")
        timer.cancel()
        collectorState = targetState
        Instrument.setState(instId, targetState)
        resetToNormal
        context become normalReceive
      }
      Logger.info(s"$self => ${MonitorStatus.map(collectorState).desp}")

    case RaiseStart =>
      Future {
        blocking {
          collectorState =
            if (calibrationType.zero)
              MonitorStatus.ZeroCalibrationStat
            else
              MonitorStatus.SpanCalibrationStat

          Instrument.setState(instId, collectorState)

          Logger.info(s"${self.path.name} => RaiseStart")
          import scala.concurrent.duration._
          if (calibrationType.zero) {
            triggerZeroCalibration(true)
          } else {
            triggerSpanCalibration(true)
          }
          val calibrationTimer = Akka.system.scheduler.scheduleOnce(Duration(tapiConfig.raiseTime.get, SECONDS), self, HoldStart)
          context become calibration(calibrationType, startTime, recordCalibration,
            calibrationReadingList, zeroReading, endState, calibrationTimer)
        }
      } onFailure (calibrationErrorHandler(instId, timer, endState))

    case HoldStart => {
      Logger.info(s"${self.path.name} => HoldStart")
      import scala.concurrent.duration._
      val calibrationTimer = Akka.system.scheduler.scheduleOnce(Duration(tapiConfig.holdTime.get, SECONDS), self, DownStart)
      context become calibration(calibrationType, startTime, true, calibrationReadingList,
        zeroReading, endState, calibrationTimer)
    }

    case DownStart =>
      Future {
        blocking {
          Logger.info(s"${self.path.name} => DownStart (${calibrationReadingList.length})")
          import scala.concurrent.duration._

          val calibrationTimer =
            if (calibrationType.auto && calibrationType.zero) {
              // Auto zero calibration will jump to end immediately
              Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, CalibrateEnd)
            } else {
              collectorState = MonitorStatus.CalibrationResume
              Instrument.setState(instId, collectorState)
              Akka.system.scheduler.scheduleOnce(Duration(tapiConfig.downTime.get, SECONDS), self, CalibrateEnd)
            }
          context become calibration(calibrationType, startTime, false, calibrationReadingList,
            zeroReading, endState, calibrationTimer)
          if (calibrationType.zero) {
            triggerZeroCalibration(false)
          } else {
            triggerSpanCalibration(false)
          }
        }
      } onFailure (calibrationErrorHandler(instId, timer, endState))

    case rd: ReportData =>
      //Logger.debug(s"calibrationReadingList #=${calibrationReadingList.length}")
      context become calibration(calibrationType, startTime, recordCalibration, rd :: calibrationReadingList,
        zeroReading, endState, timer)

    case CalibrateEnd =>
      Future {
        blocking {
          Logger.info(s"$self =>$calibrationType CalibrateEnd")

          val values = for { mt <- tapiConfig.monitorTypes.get } yield {
            val calibrations = calibrationReadingList.flatMap {
              reading =>
                reading.dataList.filter { _.mt == mt }.map { r => r.value }
            }

            if (calibrations.length == 0) {
              Logger.warn(s"No calibration data for $mt")
              (mt, 0d)
            } else
              (mt, calibrations.sum / calibrations.length)
          }

          //For auto calibration, span will be executed after zero
          if (calibrationType.auto && calibrationType.zero) {
            for (v <- values)
              Logger.info(s"${v._1} zero calibration end. (${v._2})")

            val raiseStartTimer = if (tapiConfig.calibratorPurgeTime.isDefined) {
              collectorState = MonitorStatus.NormalStat
              Instrument.setState(instId, collectorState)
              purgeCalibrator
            } else {
              import scala.concurrent.duration._
              Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, RaiseStart)
            }

            context become calibration(AutoSpan, startTime, false, List.empty[ReportData],
              values, endState, raiseStartTimer)
          } else {
            val endTime = DateTime.now()
            val duration = new Duration(startTime, endTime)

            if (calibrationType.auto) {
              val zeroMap = zeroReading.toMap
              val spanMap = values.toMap

              for (mt <- tapiConfig.monitorTypes.get) {
                val zero = zeroMap.get(mt)
                val span = spanMap.get(mt)
                val spanStd = MonitorType.map(mt).span
                val cal = Calibration(mt, startTime, endTime, zero, spanStd, span)
                Calibration.insert(cal)
              }
            } else {
              val valueMap = values.toMap
              for (mt <- tapiConfig.monitorTypes.get) {
                val values = valueMap.get(mt)
                val cal =
                  if (calibrationType.zero)
                    Calibration(mt, startTime, endTime, values, None, None)
                  else {
                    val spanStd = MonitorType.map(mt).span
                    Calibration(mt, startTime, endTime, None, spanStd, values)
                  }
                Calibration.insert(cal)
              }
            }

            Logger.info("All monitorTypes are calibrated.")
            collectorState = endState
            Instrument.setState(instId, collectorState)
            resetToNormal
            context become normalReceive
            Logger.info(s"$self => ${MonitorStatus.map(collectorState).desp}")
          }
        }
      }
  }

  def resetToNormal {
    tapiConfig.calibrateZeoDO map {
      doBit =>
        context.parent ! WriteDO(doBit, false)
    }

    tapiConfig.calibrateSpanSeq map {
      seq =>
        context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
    }
  }

  def triggerZeroCalibration(v: Boolean) {
    tapiConfig.calibrateZeoDO map {
      doBit =>
        context.parent ! WriteDO(doBit, v)
    }

    tapiConfig.calibrateZeoSeq map {
      seq =>
        if (v)
          context.parent ! ExecuteSeq(seq, v)
        else
          context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
    }
  }

  def triggerSpanCalibration(v: Boolean) {
    tapiConfig.calibrateSpanDO map {
      doBit =>
        context.parent ! WriteDO(doBit, v)
    }

    tapiConfig.calibrateSpanSeq map {
      seq =>
        if (v)
          context.parent ! ExecuteSeq(seq, v)
        else
          context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
    }
  }

  def purgeCalibrator() = {
    import scala.concurrent.duration._

    val purgeTime = tapiConfig.calibratorPurgeTime.get
    Logger.info(s"Purge calibrator. Delay start of calibration $purgeTime seconds")
    triggerCalibratorPurge(true)
    Akka.system.scheduler.scheduleOnce(Duration(purgeTime + 1, SECONDS), self, RaiseStart)
  }

  def triggerCalibratorPurge(v: Boolean) {
    try {
      if (v && tapiConfig.calibratorPurgeSeq.isDefined)
        context.parent ! ExecuteSeq(tapiConfig.calibratorPurgeSeq.get, v)
      else
        context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def reportData(regValue: ModelRegValue): ReportData

  var nextLoggingStatusTime = {
    def getNextTime(period: Int) = {
      val now = DateTime.now()
      val nextMin = (now.getMinuteOfHour / period + 1) * period
      val hour = (now.getHourOfDay + (nextMin / 60)) % 24
      val nextDay = (now.getHourOfDay + (nextMin / 60)) / 24

      now.withHourOfDay(hour).withMinuteOfHour(nextMin % 60).withSecondOfMinute(0).withMillisOfSecond(0) + nextDay.day
    }
    // suppose every 10 min
    val period = 30
    val nextTime = getNextTime(period)
    //Logger.debug(s"$instId next logging time= $nextTime")
    nextTime
  }

  def regValueReporter(regValue: ModelRegValue)(recordCalibration: Boolean) = {
    val report = reportData(regValue)
    context.parent ! report

    if (recordCalibration)
      self ! report

    for {
      r <- regValue.modeRegs.zipWithIndex
      statusType = r._1._1
      enable = r._1._2
      idx = r._2
    } {
      if (enable) {
        if (oldModelReg.isEmpty || oldModelReg.get.modeRegs(idx)._2 != enable) {
          log(instStr(instId), Level.INFO, statusType.desc)
        }
      }
    }

    for {
      r <- regValue.warnRegs.zipWithIndex
      statusType = r._1._1
      enable = r._1._2
      idx = r._2
    } {
      if (enable) {
        if (oldModelReg.isEmpty || oldModelReg.get.warnRegs(idx)._2 != enable) {
          log(instStr(instId), Level.WARN, statusType.desc)
        }
      } else {
        if (oldModelReg.isDefined && oldModelReg.get.warnRegs(idx)._2 != enable) {
          log(instStr(instId), Level.INFO, s"${statusType.desc} 解除")
        }
      }
    }

    //Log Instrument state
    if (DateTime.now() > nextLoggingStatusTime) {
      //Logger.debug("Log instrument state")
      try {
        logInstrumentStatus(regValue)
      } catch {
        case _: Throwable =>
          Logger.error("Log instrument status failed")
      }
      nextLoggingStatusTime = nextLoggingStatusTime + 10.minute
      //Logger.debug(s"next logging time = $nextLoggingStatusTime")
    }

    oldModelReg = Some(regValue)
  }

  def logInstrumentStatus(regValue: ModelRegValue) = {
    import InstrumentStatus._
    val isList = regValue.inputRegs.map {
      kv =>
        val k = kv._1
        val v = kv._2
        Status(k.key, v)
    }
    val instStatus = InstrumentStatus(DateTime.now(), instId, isList).excludeNaN
    log(instStatus)
  }

  def findDataRegIdx(regValue: ModelRegValue)(addr: Int) = {
    val dataReg = regValue.inputRegs.zipWithIndex.find(r_idx => r_idx._1._1.addr == addr)
    if (dataReg.isEmpty)
      throw new Exception("Cannot found Data register!")

    dataReg.get._2
  }

  override def postStop(): Unit = {
    if (timerOpt.isDefined)
      timerOpt.get.cancel()

    if (masterOpt.isDefined)
      masterOpt.get.destroy()
  }
}