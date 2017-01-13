package models

import scala.concurrent.ExecutionContext.Implicits.global
import com.github.nscala_time.time.Imports._
import akka.actor.Actor
import akka.actor.actorRef2Scala
import play.api.Logger
import play.api.Play.current
import play.api.libs.json.JsError
import play.api.libs.json.Json
import play.api.libs.ws.WS

class CalibrationForwarder(server: String, monitor: String) extends Actor {
  import ForwardManager._
  def receive = handler(None)

  def checkLatest = {
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
            val serverLatest =
              if (latest.time == 0) {
                DateTime.now() - 1.day
              } else {
                new DateTime(latest.time)
              }

            context become handler(Some(serverLatest.getMillis))
            uploadCalibration(serverLatest.getMillis)
          })
    }
    f onFailure {
      case ex: Throwable =>        
        ModelHelper.logException(ex)
    }
  }

  def uploadCalibration(latestCalibration: Long) = {
    import Calibration._
    val recordFuture = Calibration.calibrationReportFuture(new DateTime(latestCalibration + 1))
    for (records <- recordFuture) {
      if (!records.isEmpty) {
        val recordJSON = records.map { _.toJSON }
        val url = s"http://$server/CalibrationRecord/$monitor"
        val f = WS.url(url).put(Json.toJson(recordJSON))
        f onSuccess {
          case response =>
            context become handler(Some(records.last.startTime.getMillis))
        }
        f onFailure {
          case ex: Throwable =>
            context become handler(None)
            ModelHelper.logException(ex)
        }
      }
    }
  }

  def handler(latestCalibrationOpt: Option[Long]): Receive = {
    case ForwardCalibration =>
      try {
        if (latestCalibrationOpt.isEmpty)
          checkLatest
        else
          uploadCalibration(latestCalibrationOpt.get)
      } catch {
        case ex: Throwable =>
          ModelHelper.logException(ex)
          context become handler(None)
      }

  }

}