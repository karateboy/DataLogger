package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import com.github.nscala_time.time.Imports._
import play.api.Play.current
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.ExecutionContext.Implicits.global

case class LatestRecordTime(time: Long)
object ForwardManager {
  import com.typesafe.config.ConfigFactory
  implicit val latestRecordTimeRead = Json.reads[LatestRecordTime]

  val serverConfig = ConfigFactory.load("server")
  val disable = serverConfig.getBoolean("disable")
  val server = serverConfig.getString("server")
  val monitor = serverConfig.getString("monitor")

  case object ForwardData
  case object ForwardHour
  case object ForwardMin
  case object ForwardCalibration
  case object ForwardAlarm
  case object ForwardInstrumentStatus
  case object UpdateInstrumentStatusType

  var managerOpt: Option[ActorRef] = None
  var count = 0
  def startup() = {
    val props = Props(classOf[ForwardManager], server, monitor)
    if (disable)
      Logger.info("forwarding is disabled.")

    if (!disable) {
      Logger.info(s"create forwarder $server")
      managerOpt = Some(Akka.system.actorOf(props, name = s"forward_$count"))
      count += 1
    }
  }
  
  def updateInstrumentStatusType = {
    managerOpt map { _ ! UpdateInstrumentStatusType}
  }
  
  def forwardHourData = {
    managerOpt map { _ ! ForwardHour}
  }
  
  def forwardMinData = {
    managerOpt map { _ ! ForwardMin}
  }
  
  def forwardCalibration = {
    managerOpt map { _ ! ForwardCalibration}
  }
  
  def forwardAlarm = {
    managerOpt map { _ ! ForwardAlarm}
  }
  
  def forwardInstrumentStatus = {
    managerOpt map { _ ! ForwardInstrumentStatus}
  }
}

class ForwardManager(server: String, monitor: String) extends Actor {
  import ForwardManager._
  val timer = {
    import scala.concurrent.duration._
    //Try to trigger at 30 sec
    val next40 = DateTime.now().withSecondOfMinute(40).plusMinutes(1)
    val postSeconds = new org.joda.time.Duration(DateTime.now, next40).getStandardSeconds
    Akka.system.scheduler.schedule(Duration(0, SECONDS), Duration(5, MINUTES), self, ForwardData)
  }

  val hourRecordForwarder = context.actorOf(Props(classOf[HourRecordForwarder], server, monitor),
    "hourForwarder")

  val minRecordForwarder = context.actorOf(Props(classOf[MinRecordForwarder], server, monitor),
    "minForwarder")

  val calibrationForwarder = context.actorOf(Props(classOf[CalibrationForwarder], server, monitor),
    "calibrationForwarder")

  val alarmForwarder = context.actorOf(Props(classOf[AlarmForwarder], server, monitor),
    "alarmForwarder")

  val instrumentStatusForwarder = context.actorOf(Props(classOf[InstrumentStatusForwarder], server, monitor),
    "instrumentStatusForwarder")

  val statusTypeForwarder = context.actorOf(Props(classOf[InstrumentStatusTypeForwarder], server, monitor),
      "statusTypeForwarder")
      
  def receive = handler(None, None)

  import play.api.libs.ws._
  def handler(latestHour: Option[Long], latestMin: Option[Long]): Receive = {
    case ForwardData =>
      calibrationForwarder ! ForwardCalibration
      alarmForwarder ! ForwardAlarm
      instrumentStatusForwarder ! ForwardInstrumentStatus
      statusTypeForwarder ! UpdateInstrumentStatusType

    case ForwardHour =>
      hourRecordForwarder ! ForwardHour
    
    case ForwardMin =>
      minRecordForwarder ! ForwardMin
      
    case ForwardCalibration =>
      calibrationForwarder ! ForwardCalibration
      
    case ForwardAlarm =>
      alarmForwarder ! ForwardAlarm
      
    case ForwardInstrumentStatus=>
      instrumentStatusForwarder ! ForwardInstrumentStatus
      
    case UpdateInstrumentStatusType=>
      statusTypeForwarder ! UpdateInstrumentStatusType
  }
  
  override def postStop(): Unit = {
    timer.cancel()
  }
}