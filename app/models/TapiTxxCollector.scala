package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object TapiTxxCollector {
    case class PrepareCollect(host:String, config:TapiConfig)
    object Collect

  import Protocol.ProtocolParam
  
  var count = 0
  def start(protocolParam:ProtocolParam, param: TapiConfig)(implicit context:ActorContext) = {
    val collector = context.actorOf(Props[Adam4017Collector], name = s"T100Collector" + count)
    count+=1
    assert(protocolParam.protocol == Protocol.tcp)
    val host = protocolParam.host.get
    collector ! PrepareCollect(host, param)
    collector
  }

}

class TapiTxxCollector extends Actor {
  var cancelable: Cancellable = _

  def receive = {
    case _=>
    /*
    case PrepareCollect() =>
      cancelable = Akka.system.scheduler.schedule(Duration(5, SECONDS), Duration(2, SECONDS), self, Collect)      
      
    case Collect =>
    * 
    */
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()
  }
}