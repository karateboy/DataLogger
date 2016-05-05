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
          Logger.error(ex.getMessage)
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
        r_idx <- inputRegs.zipWithIndex
        r = r_idx._1
        idx = r_idx._2
      } yield InstrumentStatusType(key = s"$InputKey$idx", addr = r.addr, desc = r.desc, unit = r.unit)

    val holdingRegs =
      for (r <- modelReg.holdingRegs if probeHoldingReg(r.addr, r.desc))
        yield r

    val holdingRegStatusType =
      for {
        r_idx <- holdingRegs.zipWithIndex
        r = r_idx._1
        idx = r_idx._2
      } yield InstrumentStatusType(key = s"$HoldingKey$idx", addr = r.addr, desc = r.desc, unit = r.unit)

    val modeRegs =
      for (r <- modelReg.modeRegs if probeInputStatus(r.addr, r.desc))
        yield r

    val modeRegStatusType =
      for {
        r_idx <- modeRegs.zipWithIndex
        r = r_idx._1
        idx = r_idx._2
      } yield InstrumentStatusType(key = s"$ModeKey$idx", addr = r.addr, desc = r.desc, unit = "-")

    val warnRegs =
      for (r <- modelReg.warnRegs if probeInputStatus(r.addr, r.desc))
        yield r

    val warnRegStatusType =
      for {
        r_idx <- warnRegs.zipWithIndex
        r = r_idx._1
        idx = r_idx._2
      } yield InstrumentStatusType(key = s"$WarnKey$idx", addr = r.addr, desc = r.desc, unit = "-")

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
            ModelHelper.logException(ex)
            if (connected)
              log(instStr(instId), Level.ERR, s"${ex.getMessage}")

            connected = false
        } finally {
          import scala.concurrent.duration._
          timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadRegister))
        }
      }
    }

  def normalReceive(): Receive = {
    case ConnectHost(host) =>
      Logger.debug(s"${self.toString()}: connect $host")
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
            timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadRegister))
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage)
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
        if (tapiConfig.monitorTypes.isEmpty)
          Logger.error("There is no monitor type for calibration.")
        else if (!connected)
          Logger.error("Cannot calibration before connected.")
        else {
          Future {
            blocking {
              startCalibration(tapiConfig.monitorTypes.get)
            }
          } onFailure({
            case ex:Throwable=>
              ModelHelper.logInstrumentError(instId, ex.getMessage)
          })
        }
      } else {
        collectorState = state
        Instrument.setState(instId, collectorState)
      }
      Logger.info(s"$self => ${MonitorStatus.map(collectorState).desp}")
    case ExecuteSeq(seq, on) =>
      executeSeq(seq, on)
  }

  // Only for T700
  def executeSeq(seq: Int, on:Boolean) {}

  def startCalibration(monitorTypes: List[MonitorType.Value]) {
    import scala.concurrent.duration._

    Logger.info(s"start calibrating ${monitorTypes.mkString(",")}")
    val timer = Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, RaiseStart)
    import com.github.nscala_time.time.Imports._
    val endState = collectorState
    collectorState = MonitorStatus.ZeroCalibrationStat
    Instrument.setState(instId, collectorState)
    context become calibration(MonitorStatus.ZeroCalibrationStat, DateTime.now, false, List.empty[ReportData], 
        List.empty[(MonitorType.Value, Double)],  endState, timer)
  }

  def calibrationErrorHandler(id: String, timer: Cancellable, endState: String): PartialFunction[Throwable, Unit] = {
    case ex: Exception =>
      timer.cancel()
      logInstrumentError(id, s"${self.path.name}: ${ex.getMessage}. ")
      resetToNormal
      Instrument.setState(id, endState)
      collectorState = endState
      context become normalReceive
  }

  import com.github.nscala_time.time.Imports._
  def calibration(state: String, startTime: DateTime, recordCalibration: Boolean, calibrationReadingList: List[ReportData], 
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
        collectorState = targetState
        Instrument.setState(instId, targetState)
        resetToNormal
        context become normalReceive
      }
      Logger.info(s"$self => ${MonitorStatus.map(collectorState).desp}")

    case RaiseStart =>
      Future {
        blocking {
          Logger.info(s"${self.path.name} => RaiseStart")
          import scala.concurrent.duration._
          if (state == MonitorStatus.ZeroCalibrationStat) {
            triggerZeroCalibration(true)
          } else if (state == MonitorStatus.SpanCalibrationStat) {
            triggerSpanCalibration(true)
          } else {
            Logger.error(s"Unexpected state: $state")
          }
          val calibrationTimer = Akka.system.scheduler.scheduleOnce(Duration(tapiConfig.raiseTime.get, SECONDS), self, HoldStart)
          context become calibration(state, DateTime.now, recordCalibration, 
              calibrationReadingList, zeroReading, endState, calibrationTimer)
        }
      } onFailure (calibrationErrorHandler(instId, timer, endState))

    case HoldStart => {
      Logger.info(s"${self.path.name} => HoldStart")
      import scala.concurrent.duration._
      val calibrationTimer = Akka.system.scheduler.scheduleOnce(Duration(tapiConfig.holdTime.get, SECONDS), self, DownStart)
      context become calibration(state, startTime, true, calibrationReadingList, 
          zeroReading, endState, calibrationTimer)
    }

    case DownStart =>
      Future {
        blocking {
          Logger.info(s"${self.path.name} => DownStart (${calibrationReadingList.length})")
          import scala.concurrent.duration._
          if (state == MonitorStatus.ZeroCalibrationStat) {
            triggerZeroCalibration(false)
          } else if (state == MonitorStatus.SpanCalibrationStat) {
            triggerSpanCalibration(false)
          } else {
            Logger.error(s"Unexpected state: $state")
          }
          val calibrationTimer = Akka.system.scheduler.scheduleOnce(Duration(tapiConfig.downTime.get, SECONDS), self, CalibrateEnd)
          context become calibration(state, startTime, false, calibrationReadingList, 
              zeroReading, endState, calibrationTimer)
        }
      } onFailure (calibrationErrorHandler(instId, timer, endState))

    case rd: ReportData =>
      Logger.debug(s"calibrationReadingList #=${calibrationReadingList.length}")
      context become calibration(state, startTime, recordCalibration, rd :: calibrationReadingList, 
          zeroReading, endState, timer)

    case CalibrateEnd =>
      Future {
        blocking {
          Logger.info(s"$self =>$state CalibrateEnd")

          val values = for { mt <- tapiConfig.monitorTypes.get } yield {
            val calibrations = calibrationReadingList.flatMap {
                reading =>
                  reading.dataList.filter { _.mt == mt }.map { r => r.value }
              }
            
            if(calibrations.length == 0){
              Logger.warn(s"No calibration data for $mt")
              (mt, 0d)
            }else
              (mt, calibrations.sum/calibrations.length)                          
          }
          
          if (state == MonitorStatus.ZeroCalibrationStat) {
            for(v <- values)
              Logger.info(s"${v._1} zero calibration end. (${v._2})")
              collectorState = MonitorStatus.SpanCalibrationStat
              Instrument.setState(instId, collectorState)
              context become calibration(MonitorStatus.SpanCalibrationStat, startTime, false, List.empty[ReportData], 
                  values, endState, timer)
              self ! RaiseStart
          } else if (state == MonitorStatus.SpanCalibrationStat) {            
            val endTime = DateTime.now()
            val duration = new Duration(startTime, endTime)

            val zeroMap = zeroReading.toMap
            val spanMap = values.toMap
            
            for(mt <- tapiConfig.monitorTypes.get){
              val zero = zeroMap(mt)
              val span = spanMap(mt)
              val spanStd = MonitorType.map(mt).span
              val cal = Calibration(mt, startTime, endTime, zero, spanStd.getOrElse(0), span)
              Calibration.insert(cal)
            }
            Logger.info("All monitorTypes are calibrated.")
            collectorState = endState
            Instrument.setState(instId, collectorState)
            resetToNormal
            context become normalReceive
            Logger.info(s"$self => ${MonitorStatus.map(collectorState).desp}")
          } else {
            Logger.error(s"Unexpected state: $state")
          }
        }
      }
  }

  def resetToNormal: Unit
  def triggerZeroCalibration(v: Boolean)

  def triggerSpanCalibration(v: Boolean)

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
    val period = 10
    val nextTime = getNextTime(period)
    Logger.debug(s"$instId next logging time= $nextTime")
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
      Logger.debug("Log instrument state")
      logInstrumentStatus(regValue)
      nextLoggingStatusTime = nextLoggingStatusTime + 10.minute
      Logger.debug(s"next logging time = $nextLoggingStatusTime")
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
    val instStatus = InstrumentStatus(DateTime.now(), instId, isList)
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