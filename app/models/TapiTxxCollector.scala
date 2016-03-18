package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object TapiTxxCollector {
  import TapiTxx._
  case class ConnectHost(host: String)
  case object ReadRegister
  case object ReadZeroCalibration
  case object ReadSpanCalibration
  case object SpanCalibrationEnd

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
  var cancelable: Cancellable = _
  import DataCollectManager._
  import TapiTxxCollector._
  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  var master: Option[ModbusMaster] = None
  var (collectorState, instrumentStatusTypes) = {
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
        master.get.getValue(locator)
        true
      } catch {
        case ex: Throwable =>
          Logger.info(s"$addr $desc is not supported.")
          false
      }
    }

    def probeHoldingReg(addr: Int, desc: String) = {
      try {
        val locator = BaseLocator.holdingRegister(tapiConfig.slaveID, addr, DataType.FOUR_BYTE_FLOAT)
        master.get.getValue(locator)
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
        master.get.getValue(locator)
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

    batch.setContiguousRequests(false)

    val results = master.get.send(batch)
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
  def readRegHandler = {
    try {
      instrumentStatusTypes.map { readReg }.map { regReadHandler }
      connected = true
    } catch {
      case ex: java.net.ConnectException =>
        Logger.error(ex.getMessage);
        if (connected) {
          log(instStr(instId), Level.ERR, s"讀取發生錯誤:${ex.getMessage}")
          connected = false
        }
      case ex: Exception =>
        logException(ex)
        if (connected) {
          log(instStr(instId), Level.ERR, s"讀取發生錯誤:${ex.getMessage}")
          connected = false
        }
    }
  }

  def startCalibration(monitorTypes: List[MonitorType.Value]) {
    Logger.info(s"start calibrating ${monitorTypes.mkString(",")}")
    triggerZeroCalibration(true)
    Akka.system.scheduler.scheduleOnce(Duration(tapiConfig.downTime.get, SECONDS), self, ReadZeroCalibration)
    import com.github.nscala_time.time.Imports._
    val endState = collectorState
    collectorState = MonitorStatus.ZeroCalibrationStat
    Instrument.setState(instId, collectorState)
    context become calibration(DateTime.now, List.empty[Double], List.empty[Double], monitorTypes, endState)
  }

  def normalReceive(): Receive = {
    case ConnectHost(host) =>
      Logger.debug(s"${self.toString()}: connect $host")
      try {
        //instId = id
        val ipParameters = new IpParameters()
        ipParameters.setHost(host);
        ipParameters.setPort(502);
        val modbusFactory = new ModbusFactory()

        master = Some(modbusFactory.createTcpMaster(ipParameters, true))
        master.get.setTimeout(4000)
        master.get.setRetries(1)
        master.get.setConnected(true)

        master.get.init();
        connected = true
        if (instrumentStatusTypes.isEmpty) {
          instrumentStatusTypes = Some(probeInstrumentStatusType)
          Instrument.updateStatusType(instId, instrumentStatusTypes.get)
        }
        cancelable = Akka.system.scheduler.schedule(Duration(3, SECONDS), Duration(3, SECONDS), self, ReadRegister)
      } catch {
        case ex: Exception =>
          Logger.error(ex.getMessage);
          log(instStr(instId), Level.ERR, s"無法連接:${ex.getMessage}")
          Logger.error("Try again 1 min later")
          Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost(host))
      }

    case ReadRegister =>
      readRegHandler

    case SetState(id, state) =>
      if (state == MonitorStatus.ZeroCalibrationStat) {
        if (tapiConfig.monitorTypes.isEmpty)
          Logger.error("There is no monitor type for calibration.")
        else if (!connected)
          Logger.error("Cannot calibration before connected.")
        else
          startCalibration(tapiConfig.monitorTypes.get)
      } else {
        collectorState = state
        Instrument.setState(instId, collectorState)
      }
      Logger.info(s"$self => ${MonitorStatus.map(collectorState).desp}")
  }

  import com.github.nscala_time.time.Imports._
  def calibration(startTime: DateTime, zeroReading: List[Double], spanReading: List[Double],
                  monitorTypes: List[MonitorType.Value], endState: String): Receive = {
    case ConnectHost(host) =>
      Logger.error("unexpected ConnectHost msg")

    case ReadRegister =>
      readRegHandler

    case SetState(id, state) =>
      Logger.error("Cannot change state during calibration")

    case ReadZeroCalibration =>
      val zeroValue = readCalibratingValue
      Logger.debug(s"ReadZeroCalibration $zeroValue")
      triggerZeroCalibration(false)
      triggerSpanCalibration(true)
      collectorState = MonitorStatus.SpanCalibrationStat
      Instrument.setState(instId, collectorState)
      Logger.info(s"$self => ${MonitorStatus.map(collectorState).desp}")
      Akka.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(tapiConfig.raiseTime.get, SECONDS),
        self, ReadSpanCalibration)
      context become calibration(startTime, zeroValue, spanReading, monitorTypes, endState)

    case ReadSpanCalibration =>
      val spanValue = readCalibratingValue
      Logger.debug(s"ReadSpanCalibration $spanValue")
      triggerSpanCalibration(false)
      Akka.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(tapiConfig.downTime.get, SECONDS),
        self, SpanCalibrationEnd)

      context become calibration(startTime, zeroReading, spanValue, monitorTypes, endState)
    case SpanCalibrationEnd =>
      val endTime = DateTime.now()
      val duration = new Duration(startTime, endTime)
      Logger.debug(s"SpanCalibrationEnd duration=${duration.getStandardSeconds} sec")

      val spanStdList = getSpanStandard
      for {
        mt_idx <- monitorTypes.zipWithIndex
        mt = mt_idx._1
        idx = mt_idx._2
      } {
        val zero = zeroReading(idx)
        val span = spanReading(idx)
        val spanStd = spanStdList(idx)
        val cal = Calibration(mt, startTime, endTime, zero, spanStd, span)
        Calibration.insert(cal)
      }
      Logger.info("All monitorTypes are calibrated.")
      collectorState = endState
      Instrument.setState(instId, collectorState)
      context become normalReceive
      Logger.info(s"$self => ${MonitorStatus.map(collectorState).desp}")
  }

  def triggerZeroCalibration(v: Boolean)
  def readCalibratingValue(): List[Double]

  def triggerSpanCalibration(v: Boolean)
  def getSpanStandard(): List[Double]

  def reportData(regValue: ModelRegValue)

  def regReadHandler(regValue: ModelRegValue) = {
    reportData(regValue)
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

    oldModelReg = Some(regValue)
  }

  def findDataRegIdx(regValue: ModelRegValue)(addr: Int) = {
    val dataReg = regValue.inputRegs.zipWithIndex.find(r_idx => r_idx._1._1.addr == addr)
    if (dataReg.isEmpty)
      throw new Exception("Cannot found Data register!")

    dataReg.get._2
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

    if (master.isDefined)
      master.get.destroy()
  }
}