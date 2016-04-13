package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import Protocol.ProtocolParam
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString

object Baseline9000Collector {
  case object ReadData

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, config: Baseline9000Config)(implicit context: ActorContext) = {
    import Protocol.ProtocolParam
    val actorName = s"Baseline_${count}"
    count += 1
    val collector = context.actorOf(Props(classOf[Baseline9000Collector], id, protocolParam, config), name = actorName)
    Logger.info(s"$actorName is created.")

    collector
  }

}

class Baseline9000Collector(id: String, protocolParam: ProtocolParam, config: Baseline9000Config) extends Actor {
  import Baseline9000Collector._
  import scala.concurrent.duration._
  import scala.concurrent.Future
  import scala.concurrent.blocking
  import java.net.InetSocketAddress
  import Tcp._
  import context.system // implicitly used by IO(Tcp)

  override def preStart() = {
    Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, Connect)
  }

  // override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable) = {}

  def receive = {
    case Connect =>
      if (protocolParam.protocol == Protocol.tcp) {
        val remote = new InetSocketAddress(protocolParam.host.get, 502)
        IO(Tcp) ! Connect(remote)
      }

    case c @ CommandFailed(_: Connect) =>
      Logger.error(s"${self.path.name}: Connected failed")
      //Try 1 min later
      Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, Connect)

    case c @ Connected(remote, local) =>
      val connection = sender()
      connection ! Register(self)
      context become connectedHandler(connection)
  }

  def connectedHandler(connection: ActorRef): Receive = {
    case data: ByteString =>
      connection ! Write(data)

    case CommandFailed(w: Write) =>
      Logger.error(s"${self.path.name}: write failed.")

    case Received(data) =>
      Logger.info(data.toString())

    case _: ConnectionClosed =>
      Logger.error(s"${self.path.name}: Connection closed.")
      //Try 1 min later
      Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, Connect)
      context become receive
  }
}