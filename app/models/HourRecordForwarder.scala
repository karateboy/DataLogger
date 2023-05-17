package models
import scala.concurrent.ExecutionContext.Implicits.global
//
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
    import com.github.nscala_time.time.Imports._
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
            val serverLatest =
              if (latest.time == 0) {
                DateTime.now() - 1.day
              } else {
                new DateTime(latest.time)
              }

            context become handler(Some(serverLatest.getMillis))
            uploadRecord(serverLatest.getMillis)
          })
    }
    f onFailure {
      case ex: Throwable =>
        ModelHelper.logException(ex)
    }
  }

  def uploadRecord(latestRecordTime: Long) = {
    import com.github.nscala_time.time.Imports._
    val recordFuture = 
      Record.getRecordWithLimitFuture(Record.HourCollection)(new DateTime(latestRecordTime + 1), DateTime.now, 60)
      
    for (recordLists <- recordFuture) {
      if (recordLists.nonEmpty) {
        recordLists.foreach {_.excludeNaN()}
        
        try{
          val url = s"http://$server/HourRecord/$monitor"
          val f = WS.url(url).put(Json.toJson(recordLists))
          f onSuccess {
            case response =>
              if (response.status == 200) {
                context become handler(Some(recordLists.last.time))

                // This shall stop when there is no more records...
                self ! ForwardHour
              } else {
                Logger.error(s"${response.status}:${response.statusText}")
                context become handler(None)
                delayForward
              }
          }
          f onFailure {
            case ex: Throwable =>
              context become handler(None)
              ModelHelper.logException(ex)
              delayForward
          }
        }catch {
          case ex:Throwable =>
            Logger.error("failed", ex)
        }

      }
    }
  }

  def delayForward = {
    val currentMin = {
      import com.github.nscala_time.time.Imports._
      val now = DateTime.now()
      now.getMinuteOfHour
    }
    import scala.concurrent.duration._
    import play.api.libs.concurrent.Akka
    
    if(currentMin < 58)
      Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ForwardHour)
  }
  
  import com.github.nscala_time.time.Imports._
  def uploadRecord(start: DateTime, end: DateTime) = {
    Logger.info(s"upload hour ${start.toString()} => ${end.toString}")

    val recordFuture = Record.getRecordListFuture(Record.HourCollection)(start, end)
    for (record <- recordFuture) {
      if (!record.isEmpty) {
        val url = s"http://$server/HourRecord/$monitor"
        val f = WS.url(url).put(Json.toJson(record))
        f onSuccess {
          case response =>
            if (response.status == 200)
              Logger.info("Success upload!")
            else
              Logger.error(s"${response.status}:${response.statusText}")
        }
        f onFailure {
          case ex: Throwable =>
            ModelHelper.logException(ex)
        }
      } else {
        Logger.info("No more hour record")
      }
    }
  }
  def handler(latestRecordTimeOpt: Option[Long]): Receive = {
    case ForwardHour =>
      if (latestRecordTimeOpt.isEmpty)
        checkLatest
      else
        uploadRecord(latestRecordTimeOpt.get)

    case ForwardHourRecord(start, end) =>
      uploadRecord(start, end)

  }
}