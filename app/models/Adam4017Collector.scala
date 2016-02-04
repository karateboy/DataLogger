package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.ActorContext
object Adam4017Collector {
  import Adam4017._
  case class PrepareCollect(com: Int, param: Adam4017Param)
  object Collect

  var count = 0
  def start(com: Int, param: Adam4017Param)(implicit context:ActorContext) = {
    val collector = context.actorOf(Props[Adam4017Collector], name = "Adam4017Collector" + count)
    count += 1
    collector ! PrepareCollect(com, param)
    collector
  }
}

class Adam4017Collector extends Actor {
  import Adam4017Collector._
  import Adam4017._
  import java.io.BufferedReader
  import java.io._

  var cancelable: Cancellable = _
  var comm: SerialComm = _
  var adam4017param: Adam4017Param = _
  def readUntilCR={
    import scala.collection.mutable.StringBuilder
    val builder = StringBuilder.newBuilder
  }

  def decode(str: String) = {
    val ch = str.substring(1).split("(?=[+-])", 8)
    if (ch.length != 8)
      throw new Exception("unexpected format:" + str)
    
    import DataCollectManager._
    import java.lang._
    val values = ch.map { Double.valueOf(_) }
    for (cfg <- adam4017param.ch.zipWithIndex) {
      val chCfg = cfg._1
      val idx = cfg._2
      if (chCfg.enable) {
        val v = chCfg.mtMin.get + (chCfg.mtMax.get - chCfg.mtMin.get)/(chCfg.max.get - chCfg.min.get)*(values(idx)-chCfg.min.get)
        context.parent ! ReportData(chCfg.mt.get, v, "010")
      }
    }
  }

  def receive = {
    case PrepareCollect(com: Int, param) =>
      Logger.debug("prepareCollect")
      adam4017param = param
      comm = SerialComm.open(com)
      cancelable = Akka.system.scheduler.schedule(Duration(5, SECONDS), Duration(2, SECONDS), self, Collect)      
      
    case Collect =>
      val os = comm.os
      val is = comm.is
      val readCmd = s"#${adam4017param.addr}\r"
      os.write(readCmd.getBytes)      
      val str = comm.port.readString()
      Logger.info(str)
      if(str != null){
        decode(str)
      }      
  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()

    if (comm != null)
      SerialComm.close(comm)
  }
}