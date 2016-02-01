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
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
import Highchart._
import models._

object Realtime extends Controller {
  case class MonitorTypeStatus(desp: String, value: String, unit: String, instrument: String, state: String, lastUpdate: String)
  def MonitorTypeStatusList() = Security.Authenticated {
    implicit request =>
      import MonitorType._
      val user = request.user
      val userProfileOpt = User.getUserByEmail(user.id)
      def mtStatusList(include: List[MonitorType.Value]) = {
        for (mt <- mtvList if include.contains(mt)) yield {
          val ran = (Math.random()*3).toInt
          MonitorTypeStatus(map(mt).desp, format(mt, Some(Math.random() * 10)), map(mt).unit, "模擬器", "正常", 
              s"${ran}秒前" )
        }
      }
      implicit val mtsWrite = Json.writes[MonitorTypeStatus]
      val mtsList =
        if (userProfileOpt.isDefined) {
          val widgetOpt = userProfileOpt.get.widgets
          if (widgetOpt.isEmpty)
            mtStatusList(List.empty[MonitorType.Value])
          else
            mtStatusList(widgetOpt.get)

        } else {
          mtStatusList(List.empty[MonitorType.Value])
        }
      Ok(Json.toJson(mtsList))
  }

  def realtimeStatus() = Security.Authenticated {
    implicit request =>
      import MonitorType._
      val user = request.user
      val userProfileOpt = User.getUserByEmail(user.id)
    Ok(views.html.realtimeStatus(""))  
  }
}