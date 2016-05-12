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

case class InstrumentStatusTypeMap(instrumentId: String, statusTypeSeq: Seq[InstrumentStatusType])

class InstrumentStatusTypeForwarder(server: String, monitor: String) extends Actor {
  import ForwardManager._
  import Instrument._
  def receive = handler(None)
  def handler(instrumentStatusTypeIdOpt: Option[String]): Receive = {
    case UpdateInstrumentStatusType =>
      try {
        if (instrumentStatusTypeIdOpt.isEmpty) {
          val url = s"http://$server/InstrumentStatusTypeIds/$monitor"
          val f = WS.url(url).get().map {
            response =>
              val result = response.json.validate[String]
              result.fold(
                error => {
                  Logger.error(JsError.toJson(error).toString())
                },
                ids => {
                  context become handler(Some(ids))
                  self ! UpdateInstrumentStatusType
                })
          }
          f onFailure {
            case ex: Throwable =>
              ModelHelper.logException(ex)
          }
        } else {
          val recordFuture = Instrument.getAllInstrumentFuture
          for (records <- recordFuture) {
            val withStatusType = records.filter { _.statusType.isDefined }
            if (!withStatusType.isEmpty) {
              val myIds = withStatusType.map { inst =>
                inst._id + inst.statusType.get.mkString("")
              }.mkString("")

              if (myIds != instrumentStatusTypeIdOpt.get) {
                Logger.info("statusTypeId is not equal. updating...")
                val istMaps = withStatusType.map { inst =>
                  InstrumentStatusTypeMap(inst._id, inst.statusType.get)
                }
                val url = s"http://$server/InstrumentStatusTypeMap/$monitor"
                implicit val writer = Json.writes[InstrumentStatusTypeMap]
                val f = WS.url(url).put(Json.toJson(istMaps))
                f onSuccess {
                  case response =>
                    context become handler(Some(myIds))
                }
                f onFailure {
                  case ex: Throwable =>
                    ModelHelper.logException(ex)
                }

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