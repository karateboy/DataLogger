package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Adam4017Collector {
  import Adam4017._
  import Protocol.ProtocolParam

  case class PrepareCollect(id: String, com: Int, param: List[Adam4017Param])
  case object Collect

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, param: List[Adam4017Param])(implicit context: ActorContext) = {
    val collector = context.actorOf(Props[Adam4017Collector], name = "Adam4017Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.serial)
    val com = protocolParam.comPort.get
    collector ! PrepareCollect(id, com, param)
    collector
  }
}

class Adam4017Collector extends Actor {
  import Adam4017Collector._
  import Adam4017._
  import java.io.BufferedReader
  import java.io._

  var instId: String = _
  var cancelable: Cancellable = _
  var comm: SerialComm = _
  var paramList: List[Adam4017Param] = _

  def decode(str: String)(param: Adam4017Param) = {
    val ch = str.substring(1).split("(?=[+-])", 8)
    if (ch.length != 8)
      throw new Exception("unexpected format:" + str)

    import DataCollectManager._
    import java.lang._
    val values = ch.map { Double.valueOf(_) }
    val dataPairList =
      for {
        cfg <- param.ch.zipWithIndex
        (chCfg, idx) = cfg if chCfg.enable
        rawValue = values(idx)
        mt <- chCfg.mt
        mtMin <- chCfg.mtMin
        mtMax <- chCfg.mtMax
        max <- chCfg.max
        min <- chCfg.min
      } yield {
        val v = mtMin + (mtMax - mtMin) / (max - min) * (values(idx) - min)
        val status = if (MonitorTypeCollectorStatus.map.contains(mt))
          MonitorTypeCollectorStatus.map(mt)
        else {
          if (chCfg.repairMode.getOrElse(false))
            MonitorStatus.MaintainStat
          else
            collectorState
        }
        val rawMt = MonitorType.getRawMonitorType(mt)
        List(MonitorTypeData(mt, v, status), MonitorTypeData(rawMt, rawValue, status))
      }
    val dataList = dataPairList.flatMap { x => x }
    context.parent ! ReportData(dataList.toList)
  }

  import DataCollectManager._
  import scala.concurrent.Future
  import scala.concurrent.blocking

  var collectorState = MonitorStatus.NormalStat
  def receive = {
    case PrepareCollect(id, com, param) =>
      Future {
        blocking {
          try {
            instId = id
            paramList = param
            comm = SerialComm.open(com)
            cancelable = Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              cancelable = Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, PrepareCollect(id, com, param))
          }

        }
      } onFailure errorHandler

    case Collect =>
      Future {
        blocking {
          import com.github.nscala_time.time.Imports._
          val os = comm.os
          val is = comm.is
          try {
            for (param <- paramList) {
              val readCmd = s"#${param.addr}\r"
              os.write(readCmd.getBytes)
              var strList = comm.getLine
              val startTime = DateTime.now
              while (strList.length == 0) {
                val elapsedTime = new Duration(startTime, DateTime.now)
                if (elapsedTime.getStandardSeconds > 1) {
                  throw new Exception("Read timeout!")
                }
                strList = comm.getLine
              }

              for (str <- strList) {
                decode(str)(param)
              }
            }

          } catch (errorHandler)
          finally {
            cancelable = Akka.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
          }
        }
      } onFailure errorHandler

    case SetState(id, state) =>
      Logger.info(s"$self => $state")
      Instrument.setState(id, state)
      collectorState = state
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

    if (comm != null)
      SerialComm.close(comm)
  }
}