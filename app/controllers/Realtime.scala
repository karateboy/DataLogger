package controllers
import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
import Highchart._
import models._

object Realtime extends Controller {
  case class MonitorTypeStatus(desp: String, value: String, unit: String, instrument: String, status: String, lastUpdate: String, classStr:String)
  def MonitorTypeStatusList() = Security.Authenticated.async {
    implicit request =>
      import MonitorType._

      implicit val mtsWrite = Json.writes[MonitorTypeStatus]

      val result =
        for (dataMap <- DataCollectManager.getLatestData()) yield {
          val list =
            for {
              kv <- dataMap
              mt = kv._1
              record = kv._2
            } yield {
              val mCase = map(mt)
              val duration = new Duration(record.time, DateTime.now())
              val (overInternal, overLaw) = overStd(mt, record.value)
              MonitorTypeStatus(mCase.desp, format(mt, Some(record.value)), mCase.unit, mCase.measuredBy.getOrElse("??"),
                MonitorStatus.map(record.status).desp, s"${duration.getStandardSeconds}秒前", 
                MonitorStatus.getCssClassStr(record.status, overInternal, overLaw))
            }
          Ok(Json.toJson(list))
        }

      result
  }

  def realtimeStatus() = Security.Authenticated {
    implicit request =>
      import MonitorType._
      val user = request.user
      val userProfileOpt = User.getUserByEmail(user.id)
      Ok(views.html.realtimeStatus(""))
  }
}