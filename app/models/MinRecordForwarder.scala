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

class MinRecordForwarder(server: String, monitor: String) extends Actor {
  import ForwardManager._
  def receive = handler(None)
  def checkLatest = {
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
            val serverLatest =
              if(latest.time == 0){
                DateTime.now() - 1.day
              }else{
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

  def uploadRecord(latestRecordTime: Long) {
    val serverRecordStart = new DateTime(latestRecordTime + 1)
    val recordFuture =
        Record.getRecordWithLimitFuture(Record.MinCollection)(serverRecordStart, DateTime.now, 60)

    for (recordLists <- recordFuture) {
      if (recordLists.nonEmpty) {
        try{
          recordLists.foreach {
            _.excludeNaN()
          }

          val url = s"http://$server/MinRecord/$monitor"
          val f = WS.url(url).put(Json.toJson(recordLists))

          f onSuccess {
            case response =>
              if (response.status == 200) {
                if (recordLists.last.time > latestRecordTime) {
                  context become handler(Some(recordLists.last.time))
                }
              } else {
                Logger.error(s"${response.status}:${response.statusText}")
                context become handler(None)
              }
          }
          f onFailure {
            case ex: Throwable =>
              context become handler(None)
              ModelHelper.logException(ex)
          }
        }catch{
          case ex:Throwable=>
            Logger.error("failed", ex)
        }

      }
    }
  }

  def uploadRecord(start: DateTime, end: DateTime) = {
    Logger.info(s"upload min ${start.toString()} => ${end.toString}")

    val recordFuture = Record.getRecordListFuture(Record.MinCollection)(start, end)
    for (record <- recordFuture) {
      if (!record.isEmpty) {
        Logger.info(s"Total ${record.length} records")
        uploadSmallRecord(record)

        def uploadSmallRecord(recs: Seq[Record.RecordList]) {
          val (chunk, remain) = (recs.take(60), recs.drop(60))
          if (!remain.isEmpty)
            uploadSmallRecord(remain)

          val url = s"http://$server/MinRecord/$monitor"
          val f = WS.url(url).put(Json.toJson(chunk))

          f onSuccess {
            case response =>
              Logger.info(s"${response.status} : ${response.statusText}")
              Logger.info("Success upload")
          }
          f onFailure {
            case ex: Throwable =>
              ModelHelper.logException(ex)
          }
        }
      } else {
        Logger.error("No min record!")
      }

    }
  }

  def handler(latestRecordTimeOpt: Option[Long]): Receive = {
    case ForwardMin =>
      if (latestRecordTimeOpt.isEmpty)
        checkLatest
      else
        uploadRecord(latestRecordTimeOpt.get)

    case ForwardMinRecord(start, end) =>
      uploadRecord(start, end)

  }

}