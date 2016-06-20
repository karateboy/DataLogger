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

class HourRecordForwarder(server: String, monitor: String) extends Actor {
  import ForwardManager._
  def receive = handler(None)
  def checkLatest = {
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
            context become handler(Some(latest.time))
            uploadRecord(latest.time)
          })
    }
    f onFailure {
      case ex: Throwable =>
        ModelHelper.logException(ex)
    }
  }
  def uploadRecord(latestRecordTime: Long) = {
    val recordFuture = Record.getRecordListFuture(Record.HourCollection)(new DateTime(latestRecordTime + 1), DateTime.now)
    for (record <- recordFuture) {
      if (!record.isEmpty) {
        val url = s"http://$server/HourRecord/$monitor"
        val f = WS.url(url).put(Json.toJson(record))
        f onSuccess {
          case response =>
            context become handler(Some(record.last.time))
        }
        f onFailure {
          case ex: Throwable =>
            context become handler(None)            
            ModelHelper.logException(ex)
        }
      }
    }
  }

  def handler(latestRecordTimeOpt: Option[Long]): Receive = {
    case ForwardHour =>
      if (latestRecordTimeOpt.isEmpty)
        checkLatest
      else
        uploadRecord(latestRecordTimeOpt.get)
  }
}