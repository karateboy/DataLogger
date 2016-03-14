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
  case class ConnectHost(instId: String, host: String, config: TapiConfig, modelReg: ModelReg)
  object ReadRegister

  import Protocol.ProtocolParam

  var count = 0
  def start(instId: String, protocolParam: ProtocolParam,
            config: TapiConfig, modelReg: ModelReg, props: Props)(implicit context: ActorContext) = {

    val model = props.actorClass().getName.split('.')
    val actorName = s"${model(model.length - 1)}_${count}"
    count += 1
    val collector = context.actorOf(props, name = actorName)
    Logger.info(s"$actorName is created.")

    val host = protocolParam.host.get
    collector ! ConnectHost(instId, host, config, modelReg)
    collector
  }

}

trait TapiTxxCollector extends Actor {
  var cancelable: Cancellable = _
  import TapiTxxCollector._
  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters
  import TapiTxx._

  var instId: String = _
  var master: ModbusMaster = _
  var modelReg: ModelReg = _
  var tapiConfig: TapiConfig = _
  var currentStatus = MonitorStatus.normalStatus

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
    assert(master != null)

    val results = master.send(batch)
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
  def receive = {
    case ConnectHost(id, host, config, reg) =>
      Logger.debug(s"${self.toString()}: connect $host")
      try {
        instId = id
        val ipParameters = new IpParameters()
        ipParameters.setHost(host);
        ipParameters.setPort(502);
        val modbusFactory = new ModbusFactory()

        master = modbusFactory.createTcpMaster(ipParameters, true)
        master.setTimeout(4000)
        master.setRetries(1)
        master.setConnected(true)
        modelReg = reg
        tapiConfig = config

        master.init();
        connected = true
        cancelable = Akka.system.scheduler.schedule(Duration(3, SECONDS), Duration(3, SECONDS), self, ReadRegister)
      } catch {
        case ex: com.serotonin.modbus4j.exception.ErrorResponseException =>
          Logger.error(ex.getErrorResponse().getExceptionMessage());
          log(instStr(instId), Level.ERR, ex.getErrorResponse().getExceptionMessage())
          Logger.info("Try again 1 min later")
          Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost(instId, host, config, reg))
        case ex: Throwable =>
          Logger.error(ex.getMessage);
          log(instStr(instId), Level.ERR, s"無法連接:${ex.getMessage}")
          Logger.error("Try again 1 min later")
          Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost(instId, host, config, reg))
      }

    case ReadRegister =>
      try {
        val regValue = readReg
        connected = true
        regReadHandler(regValue)
      } catch {

        case ex: Throwable =>
          Logger.error(ex.getMessage);
          if (connected) {
            log(instStr(instId), Level.ERR, s"讀取發生錯誤:${ex.getMessage}")
            connected = false
          }
      }

  }

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

    if (master != null)
      master.destroy()
  }
}