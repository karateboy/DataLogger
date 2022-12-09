package models

import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Cancellable, Props}
import com.github.nscala_time.time.Imports._
import models.ModelHelper.errorHandler
import models.MoxaE1212Collector.{ConnectHost, count}
import models.Protocol.ProtocolParam
import play.api.Logger

object Nh3Instrument extends DriverOps {
  override def verifyParam(param: String): String = param

  override def getMonitorTypes(param: String): List[MonitorType.Value] = {
    val mtNames = List("NH3", "HCL", "HF", "HNO3", "AcOH")
    mtNames.map(MonitorType.withName)
  }

  override def getCalibrationTime(param: String): Option[LocalTime] = None

  var count = 0
  override def start(id: String, protocol: Protocol.ProtocolParam, param: String)
                    (implicit context: ActorContext): ActorRef = {
    assert(protocol.protocol == Protocol.tcp)
    val prop = Props(classOf[Nh3Collector], id, protocol)
    count += 1
    context.actorOf(prop, name = "Nh3Collector" + count)
  }
}

class Nh3Collector(id: String, protocolParam: ProtocolParam) extends Actor with ActorLogging {

  import DataCollectManager._
  import MoxaE1212Collector._
  import context.dispatcher

  self ! ConnectHost
  var cancelable: Cancellable = _

  def decodeValue(values: Seq[Short], collectorState: String): Unit = {
    val mtInfo = List(
      (3, MonitorType.withName("HCL")),
      (4, MonitorType.withName("HF")),
      (5, MonitorType.withName("NH3")),
      (6, MonitorType.withName("HNO3")),
      (7, MonitorType.withName("AcOH"))
    )

    val polarity = values(2)
    val dataList =
      for {
        (offset, mt) <- mtInfo
      } yield {
        val v = 0.1 * values(offset)
        MonitorTypeData(mt, v, collectorState)
      }
    context.parent ! ReportData(dataList)
  }

  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  import scala.concurrent.{Future, blocking}

  def receive: Receive = handler(MonitorStatus.NormalStat, None)

  def handler(collectorState: String, masterOpt: Option[ModbusMaster]): Receive = {
    case ConnectHost =>
      log.info(s"connect to NH3 device")
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
            import scala.concurrent.duration._
            cancelable = context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              import scala.concurrent.duration._
              cancelable = context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }

        }
      } onFailure errorHandler

    case Collect =>
      Future {
        blocking {
          try {
            import com.serotonin.modbus4j.BatchRead
            import com.serotonin.modbus4j.code.DataType
            import com.serotonin.modbus4j.locator.BaseLocator

            //DI Counter ...
            {
              val batch = new BatchRead[Integer]

              for (idx <- 0 to 8)
                batch.addLocator(idx, BaseLocator.inputRegister(1, 1 + idx, DataType.TWO_BYTE_INT_SIGNED))

              batch.setContiguousRequests(true)

              val rawResult = masterOpt.get.send(batch)
              val result =
                for (idx <- 0 to 7) yield
                  rawResult.getValue(idx).asInstanceOf[Short]


              decodeValue(result, collectorState)
            }

            import scala.concurrent.duration._
            cancelable = context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(5, SECONDS), self, Collect)
          } catch {
            case ex: Throwable =>
              Logger.error("Read reg failed", ex)
              masterOpt.get.destroy()
              context become handler(collectorState, None)
              self ! ConnectHost
          }
        }
      } onFailure errorHandler

    case SetState(id, state) =>
      Logger.info(s"$self => $state")
      Instrument.setState(id, state)
      context become handler(state, masterOpt)

  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

  }
}