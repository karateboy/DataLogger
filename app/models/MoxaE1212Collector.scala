package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import MoxaE1212._
import Protocol.ProtocolParam

object MoxaE1212Collector {

  case object ConnectHost
  case object Collect

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, param: MoxaE1212Param)(implicit context: ActorContext) = {
    val prop = Props(classOf[MoxaE1212Collector], id, protocolParam, param)
    val collector = context.actorOf(prop, name = "MoxaE1212Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.tcp)
    val host = protocolParam.host.get
    collector ! ConnectHost
    collector

  }
}

class MoxaE1212Collector(id: String, protocolParam: ProtocolParam, param: MoxaE1212Param) extends Actor with ActorLogging {
  import MoxaE1212Collector._
  import java.io.BufferedReader
  import java.io._

  var cancelable: Cancellable = _

  def decode(values: Seq[Short]) = {
    import DataCollectManager._
    val dataList =
      for {
        cfg <- param.ch.zipWithIndex
        chCfg = cfg._1 if chCfg.enable
        idx = cfg._2
      } yield {
        val v = values(idx)
        MonitorTypeData(chCfg.mt.get, v, "010")
      }
    context.parent ! ReportData(dataList.toList)
  }

  import DataCollectManager._
  import scala.concurrent.Future
  import scala.concurrent.blocking
  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  def receive = handler(MonitorStatus.NormalStat, None)

  def handler(collectorState: String, masterOpt: Option[ModbusMaster]): Receive = {
    case ConnectHost =>
      log.info(s"connect to E1212")
      Future {
        blocking {
          try {
            val ipParameters = new IpParameters()
            ipParameters.setHost(protocolParam.host.get);
            ipParameters.setPort(502);
            val modbusFactory = new ModbusFactory()

            val master = modbusFactory.createTcpMaster(ipParameters, true)
            master.setTimeout(4000)
            master.setRetries(1)
            master.setConnected(true)
            master.init();
            context become handler(collectorState, Some(master))
            cancelable = Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              cancelable = Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }

        }
      } onFailure errorHandler

    case Collect =>
      Future {
        blocking {
          try{
          import com.serotonin.modbus4j.BatchRead
          val batch = new BatchRead[Integer]

          import com.serotonin.modbus4j.locator.BaseLocator
          import com.serotonin.modbus4j.code.DataType

          //FIXME It is just POC!!!
          for (idx <- 0 to 7)
            batch.addLocator(idx, BaseLocator.inputRegister(1, 32 + 2*idx, DataType.TWO_BYTE_INT_UNSIGNED))

          batch.setContiguousRequests(true)

          assert(masterOpt.isDefined)
          
          val rawResult = masterOpt.get.send(batch)
          val result =
            for (idx <- 0 to 7) yield rawResult.getIntValue(idx).toShort
 

          decode(result.toSeq)
          cancelable = Akka.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
          }catch{
            case ex:Throwable=>
              Logger.error("Read reg failed", ex)
              masterOpt.get.destroy()
              context become handler(collectorState, None)
              self ! ConnectHost              
          }
        }
      } onFailure errorHandler

    case SetState(id, state) =>
      Logger.info(s"$self => $state")
      context become handler(state, masterOpt)
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

  }
}