package models

import scala.concurrent.ExecutionContext.Implicits.global
import com.github.nscala_time.time.Imports.DateTime
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
  def handler(latestCalibration: Option[Long]): Receive = {
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
                  context become handler(Some(latest.time))
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
                  context become handler(Some(records.last.startTime.getMillis))
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

}