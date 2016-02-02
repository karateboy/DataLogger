package controllers
import akka.actor._
import play.api._
import play.api.mvc._ 
import com.github.nscala_time.time.Imports._
import models._

object MinMonthlyReportWorker {
  def props(out: ActorRef) = Props(new MinMonthlyReportWorker(out))
}
 
class MinMonthlyReportWorker(out: ActorRef) extends Actor {
  object CmdType extends Enumeration{
    val start = Value
    val progress = Value
    val finish = Value
  }
  
  var progress: Int = _
  def parseStartCmd(msg:String)={
    val param = msg.split(":")
    (CmdType.withName(param(0)), DateTime.parse(param(2)))
  }
  
  def progressCallback(percent:Int)={
    val msg = s"${CmdType.progress}:${percent}"
    out ! msg
  }
  
  def receive = {
    case msg: String => 
//      val (cmd, monitor, startDate) = parseStartCmd(msg)
//      val file = ExcelUtility.minMonthlyReport(List(monitor), startDate, progressCallback)
//      val n = SystemConfig.getFreeDownloadLink()
//      SystemConfig.setDownloadLink(n, file)
      val outMsg = "" //s"${CmdType.finish}:${n}"
      out ! outMsg
  }
}