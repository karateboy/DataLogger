package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Apax5056Collector {
  import Apax5056._
  import Protocol.ProtocolParam

  case object Collect

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, param: List[Adam5056Param])(implicit context: ActorContext) = {
    val collector = context.actorOf(Props[Adam4017Collector], name = "Apax5056Collector" + count)
    count += 1
    assert(protocolParam.protocol == Protocol.serial)
    val com = protocolParam.comPort.get
    collector
  }
}

class Apax50564017Collector extends Actor {
  import Adam4017Collector._
  import Adam4017._
  import java.io.BufferedReader
  import java.io._

  var instId: String = _
  var comm: SerialComm = _

  import DataCollectManager._
  import scala.concurrent.Future
  import scala.concurrent.blocking

  var collectorState = MonitorStatus.NormalStat
  def receive = {
    case SetState(id, state) =>
      Logger.info(s"$self => $state")
      collectorState = state
  }

  override def postStop(): Unit = {
    if (comm != null)
      SerialComm.close(comm)
  }
}