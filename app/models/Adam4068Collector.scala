package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Adam4068Collector {
  import Adam4068._
  import Protocol.ProtocolParam

  case object StopEvtOperationOverThreshold

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, param: Adam4068Param)(implicit context: ActorContext) = {
    val prop = Props(classOf[Adam4068Collector], id, protocolParam, param)
    val collector = context.actorOf(prop, name = "Adam4068_" + count)
    count += 1
    collector
  }
}

import Adam4068._
import Protocol.ProtocolParam

class Adam4068Collector(id: String, protocolParam: ProtocolParam, param: Adam4068Param) extends Actor with ActorLogging {
  var comm: SerialComm = SerialComm.open(protocolParam.comPort.get)
  var handleEvtOperation = false

  import DataCollectManager._
  import Adam4068Collector._

  import scala.concurrent.Future
  import scala.concurrent.blocking

  def receive = handler(MonitorStatus.NormalStat)

  def handler(collectorState: String): Receive = {
    case SetState(id, state) =>
      Logger.warn(s"Ignore $self => $state")

    case WriteDO(bit, on) =>
      val os = comm.os
      val is = comm.is
      Logger.info(s"Output DO $bit to $on")
      val writeCmd = if (on)
        s"#${param.addr}0001\r"
      else
        s"#${param.addr}0000\r"

      os.write(writeCmd.getBytes)

    case EvtOperationOverThreshold =>
      if (handleEvtOperation == false) {
        Logger.info("EvtOperationOverThreshold")
        handleEvtOperation = true
        for {
          ch_idx <- param.ch.zipWithIndex
          ch = ch_idx._1
          idx = ch_idx._2
        } {
          if (ch.enable && ch.evtOp == Some(EventOperation.OverThreshold)) {
            self ! WriteDO(idx, true)
            Akka.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(ch.duration.get, SECONDS), self, StopEvtOperationOverThreshold)
          }
        }
      }

    case StopEvtOperationOverThreshold =>
      if (handleEvtOperation == true) {
        Logger.info("Stop EvtOperationOverThreshold")
        handleEvtOperation = false
        for {
          ch_idx <- param.ch.zipWithIndex
          ch = ch_idx._1
          idx = ch_idx._2
        } {
          if (ch.enable && ch.evtOp == Some(EventOperation.OverThreshold)) {
            self ! WriteDO(idx, false)
          }
        }
      }
  }

  override def postStop(): Unit = {

    if (comm != null)
      SerialComm.close(comm)
  }
}
