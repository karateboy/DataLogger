package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.ExecutionContext.Implicits.global
import Protocol.ProtocolParam

import ModbusBaseCollector._

object Adam5000Collector {
  import Adam5000._

  var count = 0
  def start(instID: String, protocolParam: ProtocolParam, config: Adam5000Param)(implicit context: ActorContext) = {

    val props = Props(classOf[Adam5000Collector], instID, protocolParam, config)
    val actorName = s"Adam5000_${count}"
    count += 1
    val collector = context.actorOf(props, name = actorName)
    Logger.info(s"$actorName is created.")

    val host = protocolParam.host.get
    collector ! ConnectHost(host)
    collector
  }
}

import Adam5000._
import Adam5000Collector._

class Adam5000Collector(instID: String, protocolParam: ProtocolParam, config: Adam5000Param) extends Actor with ActorLogging {
  var timerOpt: Option[Cancellable] = None
  import DataCollectManager._
  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  var masterOpt: Option[ModbusMaster] = None
  var (collectorState, instrumentStatusTypesOpt) = {
    val instList = Instrument.getInstrument(instID)
    if (!instList.isEmpty) {
      val inst = instList(0)
      (inst.state, inst.statusType)
    } else
      (MonitorStatus.NormalStat, None)
  }

  Logger.info(s"$self state=${MonitorStatus.map(collectorState).desp}")

  def readReg() = {
    import com.serotonin.modbus4j.locator.BaseLocator
    import com.serotonin.modbus4j.code.DataType

    def diModuleReader(moduleInfo: ModuleInfo, moduleCfg: ModuleCfg) = {
      val batch = new BatchRead[Integer]
      for (idx <- 0 to moduleInfo.channelNumber - 1)
        batch.addLocator(idx, BaseLocator.inputStatus(1, idx))

      batch.setContiguousRequests(true)
      val rawResult = masterOpt.get.send(batch)
      val result =
        for (idx <- 0 to moduleInfo.channelNumber - 1) yield rawResult.getValue(idx).asInstanceOf[Boolean]

      for {
        idx <- 0 to moduleInfo.channelNumber - 1
        channelCfg = moduleCfg.ch(idx) if channelCfg.enable && channelCfg.mt.isDefined
        mt = channelCfg.mt.get
        v = result(idx)
      } {
        MonitorType.logDiMonitorType(mt, v)
      }
    }

    def aiModuleReader(moduleInfo: ModuleInfo, moduleCfg: ModuleCfg) = {
      def decode(values: Seq[Double]) = {

        import DataCollectManager._
        import java.lang._
        val dataList =
          for {
            cfg_idx <- moduleCfg.ch.zipWithIndex
            chCfg = cfg_idx._1 if chCfg.enable
            idx = cfg_idx._2
          } yield {
            val v = chCfg.mtMin.get + (chCfg.mtMax.get - chCfg.mtMin.get) / (chCfg.max.get - chCfg.min.get) * (values(idx) - chCfg.min.get)
            val status = if (MonitorTypeCollectorStatus.map.contains(chCfg.mt.get))
              MonitorTypeCollectorStatus.map(chCfg.mt.get)
            else {
              if (chCfg.repairMode.isDefined && chCfg.repairMode.get)
                MonitorStatus.MaintainStat
              else
                collectorState
            }
            MonitorTypeData(chCfg.mt.get, v, status)
          }
        context.parent ! ReportData(dataList.toList)
      }

      val batch = new BatchRead[Integer]
      for (idx <- 0 to moduleInfo.channelNumber)
        batch.addLocator(idx, BaseLocator.inputRegister(1, moduleCfg.addr + idx, DataType.TWO_BYTE_INT_UNSIGNED))

      batch.setContiguousRequests(true)

      val rawResult = masterOpt.get.send(batch)
      val result =
        for (idx <- 0 to moduleInfo.channelNumber) yield rawResult.getIntValue(idx)

      val values = result.map { _.toDouble }
      decode(values)
    }

    for (module <- config.moduleList) {
      val moduleInfo = Adam5000.moduleMap(module.moduleID)
      moduleInfo.moduleType match {
        case ModuleType.AiModule =>
          aiModuleReader(moduleInfo, module)
        case ModuleType.DiModule =>
          diModuleReader(moduleInfo, module)
        case ModuleType.DoModule =>

      }
    }
  }

  var connected = false
  import Alarm._

  def receive = normalReceive

  import scala.concurrent.Future
  import scala.concurrent.blocking
  def readRegFuture =
    Future {
      blocking {
        try {
          readReg()
          connected = true
        } catch {
          case ex: Exception =>
            Logger.error(ex.getMessage, ex)
            connected = false
        } finally {
          import scala.concurrent.duration._
          timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadRegister))
        }
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
            import scala.concurrent.duration._
            timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadRegister))
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              import scala.concurrent.duration._

              Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost(host))
          }
        }
      }

    case ReadRegister =>
      readRegFuture

    case SetState(id, state) =>
      if (state == MonitorStatus.ZeroCalibrationStat) {
        Logger.error(s"Unexpected command: SetState($state)")
      } else {
        collectorState = state
        Instrument.setState(instID, collectorState)
      }
      Logger.info(s"$self => ${MonitorStatus.map(collectorState).desp}")

  }

  override def postStop(): Unit = {
    if (timerOpt.isDefined)
      timerOpt.get.cancel()

    if (masterOpt.isDefined)
      masterOpt.get.destroy()
  }
}