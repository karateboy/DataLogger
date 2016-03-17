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

  //var instId: String = _
  var master: Option[ModbusMaster] = None
  var collectorState = {
    val instList = Instrument.getInstrument(instId)
    if (!instList.isEmpty) {
      instList(0).state
    } else
      MonitorStatus.NormalStat
  }
  Logger.info(s"$self state=${MonitorStatus.map(collectorState).desp}")

  var instrumentState = MonitorStatus.NormalStat

  def readReg = {
    import com.serotonin.modbus4j.BatchRead
    val batch = new BatchRead[Integer]

    import com.serotonin.modbus4j.locator.BaseLocator
    import com.serotonin.modbus4j.code.DataType
    var idx = 0
    for (r <- modelReg.inputRegs) {
      batch.addLocator(idx, BaseLocator.inputRegister(tapiConfig.slaveID, r.addr, DataType.FOUR_BYTE_FLOAT))
      idx += 1
    }
    val holdingIdx = idx
    for (r <- modelReg.holdingRegs) {
      batch.addLocator(idx, BaseLocator.holdingRegister(tapiConfig.slaveID, r.addr, DataType.FOUR_BYTE_FLOAT))
      idx += 1
    }
    val modeIdx = idx
    for (r <- modelReg.modeRegs) {
      batch.addLocator(idx, BaseLocator.inputStatus(tapiConfig.slaveID, r.addr))
      idx += 1
    }

    val warnIdx = idx
    for (r <- modelReg.warnRegs) {
      batch.addLocator(idx, BaseLocator.inputStatus(tapiConfig.slaveID, r.addr))
      idx += 1
    }

    val coilIdx = idx
    for (r <- modelReg.coilRegs) {
      batch.addLocator(idx, BaseLocator.coilStatus(tapiConfig.slaveID, r.addr))
      idx += 1
    }

    batch.setContiguousRequests(false)
    assert(master.isDefined)

    val results = master.get.send(batch)
    val inputs =
      for (i <- 0 to holdingIdx - 1)
        yield results.getFloatValue(i).toFloat

    val holdings =
      for (i <- holdingIdx to modeIdx - 1)
        yield results.getFloatValue(i).toFloat

    val modes =
      for (i <- modeIdx to warnIdx - 1) yield {
        results.getValue(i).asInstanceOf[Boolean]
      }
    val warns =
      for (i <- warnIdx to coilIdx - 1) yield {
        results.getValue(i).asInstanceOf[Boolean]
      }

    val coils =
      for (i <- coilIdx to idx - 1) yield {
        results.getValue(i).asInstanceOf[Boolean]
      }

    ModelRegValue(inputs.toList, holdings.toList, modes.toList, warns.toList, coils.toList)
  }

  var connected = false
  var oldModelReg: ModelRegValue = _
  import Alarm._

  def receive = normalReceive
  def readRegHandler = {
    try {
      val regValue = readReg
      connected = true
      regReadHandler(regValue)
    } catch {
      case ex: java.net.ConnectException =>
        Logger.error(ex.getMessage);
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
        else if(!connected)
          Logger.error("Cannot calibration before connected.")
        else
          startCalibration(tapiConfig.monitorTypes.get)
      } else{
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

  def triggerZeroCalibration(v:Boolean)
  def readCalibratingValue(): List[Double]

  def triggerSpanCalibration(v:Boolean)
  def getSpanStandard(): List[Double]

  def reportData(regValue: ModelRegValue)

  def regReadHandler(regValue: ModelRegValue) = {
    reportData(regValue)
    for (r <- regValue.modeRegs.zipWithIndex) {
      if (r._1) {
        val idx = r._2
        if (oldModelReg == null || oldModelReg.modeRegs(idx) != r._1) {
          log(instStr(instId), Level.INFO, modelReg.modeRegs(idx).desc)
        }
      }
    }

    for (r <- regValue.warnRegs.zipWithIndex) {
      val v = r._1
      val idx = r._2
      if (v) {
        if (oldModelReg == null || oldModelReg.warnRegs(idx) != v) {
          log(instStr(instId), Level.WARN, modelReg.warnRegs(idx).desc)
        }
      } else {
        if (oldModelReg != null && oldModelReg.warnRegs(idx) != v) {
          log(instStr(instId), Level.INFO, s"${modelReg.warnRegs(idx).desc} 解除")
        }
      }
    }

    oldModelReg = regValue
  }
  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

    if (master.isDefined)
      master.get.destroy()
  }
}