package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import com.github.nscala_time.time.Imports._
import play.api.Play.current
import Alarm._
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

  def receive = handler(None, None, None)

  import play.api.libs.ws._
  def handler(latestHour: Option[Long], latestMin: Option[Long], latestCalibration: Option[Long]): Receive = {
    case ForwardData =>
      self ! ForwardHour
      self ! ForwardMin
      self ! ForwardCalibration
    //self ! ForwardAlarm

    case ForwardHour =>
      try {
        if (latestHour.isEmpty) {
          val url = s"http://$server/HourRecordRange/$monitor"
          Logger.debug(url)
          val f = WS.url(url).get().map {
            response =>
              val result = response.json.validate[LatestRecordTime]
              result.fold(
                error => {
                  Logger.error(JsError.toJson(error).toString())
                },
                latest => {
                  Logger.info(s"server latest hour: ${new DateTime(latest.time).toString}")
                  context become handler(Some(latest.time), latestMin, latestCalibration)
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
              Logger.debug(s"put hour from ${new DateTime(record.head.time)} to ${new DateTime(record.last.time)}")
              val url = s"http://$server/HourRecord/$monitor"
              val f = WS.url(url).put(Json.toJson(record))
              f onSuccess {
                case response =>
                  context become handler(Some(record.last.time), latestMin, latestCalibration)
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
                  context become handler(latestHour, Some(latest.time), latestCalibration)
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
              Logger.debug(s"put min from ${new DateTime(record.head.time)} to ${new DateTime(record.last.time)}")
              val url = s"http://$server/MinRecord/$monitor"
              val f = WS.url(url).put(Json.toJson(record))
              f onSuccess {
                case response =>
                  context become handler(latestHour, Some(record.last.time), latestCalibration)
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

    case ForwardCalibration =>
      try {
        if (latestCalibration.isEmpty) {
          val url = s"http://$server/CalibrationRecordRange/$monitor"
          val f = WS.url(url).get().map {
            response =>
              val result = response.json.validate[LatestRecordTime]
              result.fold(
                error => {
                  Logger.error(JsError.toJson(error).toString())
                },
                latest => {
                  Logger.info(s"server latest calibration: ${new DateTime(latest.time).toString}")
                  context become handler(latestHour, latestMin, Some(latest.time))
                  self ! ForwardCalibration
                })
          }
          f onFailure {
            case ex: Throwable =>
              ModelHelper.logException(ex)
          }
        } else {
          import Calibration._
          val recordFuture = Calibration.calibrationReportFuture(new DateTime(latestCalibration.get + 1), DateTime.now)
          for (records <- recordFuture) {
            if (!records.isEmpty) {
              val recordJSON = records.map { _.toJSON }
              val url = s"http://$server/CalibrationRecord/$monitor"
              val f = WS.url(url).put(Json.toJson(recordJSON))
              f onSuccess {
                case response =>
                  context become handler(latestHour, latestMin, Some(records.last.startTime.getMillis))
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