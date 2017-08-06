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

  case class PrepareCollect(id: String, com: Int, param: Adam4068Param)
  case object Collect

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, param: Adam4068Param)(implicit context: ActorContext) = {
    val collector = context.actorOf(Props[Adam4068Collector], name = "Adam4067Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.serial)
    val com = protocolParam.comPort.get
    collector ! PrepareCollect(id, com, param)
    collector
  }
}

import Adam4068._
import Protocol.ProtocolParam

class Adam4068Collector(id: String, protocolParam: ProtocolParam, param: Adam4068Param) extends Actor with ActorLogging {
  var comm: SerialComm = SerialComm.open(protocolParam.comPort.get)

  import DataCollectManager._
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
      val writeCmd = if(on) 
        s"#${param.addr}0001\r"
      else
        s"#${param.addr}0000\r"
      
      os.write(writeCmd.getBytes)

  }

  override def postStop(): Unit = {

    if (comm != null)
      SerialComm.close(comm)
  }
}
