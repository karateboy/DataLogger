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

  var manager: ActorRef = _
  var count = 0
  def startup() = {
    val props = Props(classOf[ForwardManager], server, monitor)
    if (disable)
      Logger.info("forwarding is disabled.")

    if (!disable) {
      Logger.info(s"create forwarder $server")
      manager = Akka.system.actorOf(props, name = s"forward_$count")
      count += 1
    }
  }
}

class ForwardManager(server: String, monitor: String) extends Actor {
  import ForwardManager._
  val timer = {
    import scala.concurrent.duration._
    //Try to trigger at 30 sec
    val next40 = DateTime.now().withSecondOfMinute(40).plusMinutes(1)
    val postSeconds = new org.joda.time.Duration(DateTime.now, next40).getStandardSeconds
    Akka.system.scheduler.schedule(Duration(0, SECONDS), Duration(1, MINUTES), self, ForwardData)
  }

  val calibrationForwarder = context.actorOf(Props(classOf[CalibrationForwarder], server, monitor), "calibrationForwarder")
  val alarmForwarder = context.actorOf(Props(classOf[AlarmForwarder], server, monitor), "alarmForwarder")
  
  def receive = handler(None, None)

  import play.api.libs.ws._
  def handler(latestHour: Option[Long], latestMin: Option[Long]): Receive = {
    case ForwardData =>
      self ! ForwardHour
      self ! ForwardMin
      calibrationForwarder ! ForwardCalibration
      alarmForwarder ! ForwardAlarm

    case ForwardHour =>
      try {
        if (latestHour.isEmpty) {
          val url = s"http://$server/HourRecordRange/$monitor"
          val f = WS.url(url).get().map {
            response =>
              val result = response.json.validate[LatestRecordTime]
              result.fold(
                error => {
                  Logger.error(JsError.toJson(error).toString())
                },
                latest => {
                  Logger.info(s"server latest hour: ${new DateTime(latest.time).toString}")
                  context become handler(Some(latest.time), latestMin)
                  self ! ForwardHour
                })
          }
          f onFailure {
            case ex: Throwable =>
              ModelHelper.logException(ex)
          }
        } else {
          val recordFuture = Record.getRecordListFuture(Record.HourCollection)(new DateTime(latestHour.get + 1), DateTime.now)
          for (record <- recordFuture) {
            if (!record.isEmpty) {
              val url = s"http://$server/HourRecord/$monitor"
              val f = WS.url(url).put(Json.toJson(record))
              f onSuccess {
                case response =>
                  context become handler(Some(record.last.time), latestMin)
              }
              f onFailure {
                case ex: Throwable =>
                  ModelHelper.logException(ex)
              }
            }
          }
        }
      } catch {
        case ex: Throwable =>
          ModelHelper.logException(ex)
      }
    case ForwardMin =>
      try {
        if (latestMin.isEmpty) {
          val url = s"http://$server/MinRecordRange/$monitor"
          val f = WS.url(url).get().map {
            response =>
              val result = response.json.validate[LatestRecordTime]
              result.fold(
                error => {
                  Logger.error(JsError.toJson(error).toString())
                },
                latest => {
                  Logger.info(s"server latest min: ${new DateTime(latest.time).toString}")
                  context become handler(latestHour, Some(latest.time))
                  self ! ForwardMin
                })
          }
          f onFailure {
            case ex: Throwable =>
              ModelHelper.logException(ex)
          }
        } else {
          val recordFuture = Record.getRecordListFuture(Record.MinCollection)(new DateTime(latestMin.get + 1), DateTime.now)
          for (record <- recordFuture) {
            if (!record.isEmpty) {
              val url = s"http://$server/MinRecord/$monitor"
              val f = WS.url(url).put(Json.toJson(record))
              f onSuccess {
                case response =>
                  context become handler(latestHour, Some(record.last.time))
              }
              f onFailure {
                case ex: Throwable =>
                  ModelHelper.logException(ex)
              }
            }
          }
        }
      } catch {
        case ex: Throwable =>
          ModelHelper.logException(ex)
      }
 }
  override def postStop(): Unit = {
    timer.cancel()
  }
}