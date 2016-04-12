package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import Protocol.ProtocolParam
import scala.concurrent.ExecutionContext.Implicits.global

object Baseline9000Collector {
  case object ConnectHost
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

  var cancelable = Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ConnectHost)

  def receive = {
    case ConnectHost =>
      val f = Future {
        blocking {

        }
      }
      f.onFailure({
        case ex: Exception =>
          Logger.error(ex.getMessage)
      })
      
    case ReadData=>
  }
}