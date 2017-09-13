package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
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
  import DataCollectManager._

  var cancelable: Cancellable = _

  val resetTimer = {
    import com.github.nscala_time.time.Imports._

    val resetTime = DateTime.now().withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0) + 1.hour
    val duration = new Duration(DateTime.now(), resetTime)
    import scala.concurrent.duration._
    Akka.system.scheduler.schedule(scala.concurrent.duration.Duration(duration.getStandardSeconds, SECONDS),
      scala.concurrent.duration.Duration(1, HOURS), self, ResetCounter)
  }

  def decodeDiCounter(values: Seq[Int], collectorState: String) = {
    import DataCollectManager._
    val dataOptList =
      for {
        cfg <- param.ch.zipWithIndex
        chCfg = cfg._1 if chCfg.enable
        idx = cfg._2
        scale = chCfg.scale.get
      } yield {
        val v = scale * values(idx)
        val state = if (chCfg.repairMode.isDefined && chCfg.repairMode.get)
          MonitorStatus.MaintainStat
        else
          collectorState

        if (!MonitorType.DI_TYPES.contains(chCfg.mt.get))
          Some(MonitorTypeData(chCfg.mt.get, v, state))
        else
          None

      }
    val dataList = dataOptList.flatMap { d => d }
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
            import scala.concurrent.duration._
            cancelable = Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              import scala.concurrent.duration._
              cancelable = Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }

        }
      } onFailure errorHandler

    case Collect =>
      Future {
        blocking {
          try {
            import com.serotonin.modbus4j.BatchRead
            import com.serotonin.modbus4j.locator.BaseLocator
            import com.serotonin.modbus4j.code.DataType

            //DI Counter ...
            {
              val batch = new BatchRead[Integer]

              for (idx <- 0 to 7)
                batch.addLocator(idx, BaseLocator.inputRegister(1, 16 + 2 * idx, DataType.FOUR_BYTE_INT_SIGNED))

              batch.setContiguousRequests(true)

              val rawResult = masterOpt.get.send(batch)
              val result =
                for (idx <- 0 to 7) yield rawResult.getIntValue(idx).toInt

              decodeDiCounter(result.toSeq, collectorState)
            }
            // DI Value ...
            {
              val batch = new BatchRead[Integer]
              for (idx <- 0 to 7)
                batch.addLocator(idx, BaseLocator.inputStatus(1, idx))

              batch.setContiguousRequests(true)

              val rawResult = masterOpt.get.send(batch)
              val result =
                for (idx <- 0 to 7) yield rawResult.getValue(idx).asInstanceOf[Boolean]

              import DataCollectManager._

              for {
                cfg <- param.ch.zipWithIndex
                chCfg = cfg._1 if chCfg.enable &chCfg.mt.isDefined
                idx = cfg._2
                v = result(idx)
              } yield {
                MonitorType.logDiMonitorType(chCfg.mt.get, v)
              }
            }

            import scala.concurrent.duration._
            cancelable = Akka.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
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

    case ResetCounter =>
      Logger.info("Reset counter to 0")
      try {
        import com.serotonin.modbus4j.locator.BaseLocator
        import com.serotonin.modbus4j.code.DataType
        val resetRegAddr = 272

        for {
          ch_idx <- param.ch.zipWithIndex if ch_idx._1.enable && ch_idx._1.mt == Some(MonitorType.RAIN)
          ch = ch_idx._1
          idx = ch_idx._2
        } {
          val locator = BaseLocator.coilStatus(1, resetRegAddr + idx)
          masterOpt.get.setValue(locator, true)
        }
      } catch {
        case ex: Exception =>
          ModelHelper.logException(ex)
      }

    case WriteDO(bit, on) =>
      Logger.info(s"Output DO $bit to $on")
      try {
        import com.serotonin.modbus4j.locator.BaseLocator
        import com.serotonin.modbus4j.code.DataType
        val locator = BaseLocator.coilStatus(1, bit)
        masterOpt.get.setValue(locator, on)
      } catch {
        case ex: Exception =>
          ModelHelper.logException(ex)
      }
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

    resetTimer.cancel()
  }
}