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
  val overTimeLimit = 6
  case class MonitorTypeStatus(desp: String, value: String, unit: String, instrument: String, status: String, classStr: String, order: Int)
  def MonitorTypeStatusList() = Security.Authenticated.async {
    implicit request =>
      import MonitorType._

      implicit val mtsWrite = Json.writes[MonitorTypeStatus]

      val result =
        for (dataMap <- DataCollectManager.getLatestData()) yield {
          val list =
            for {
              mt <- MonitorType.realtimeMtvList
              recordOpt = dataMap.get(mt)
            } yield {
              val mCase = map(mt)
              val measuringByStr = mCase.measuringBy.map {
                instmentList =>
                  instmentList.mkString(",")
              }.getOrElse("??")

              if (recordOpt.isDefined) {
                val record = recordOpt.get
                val duration = new Duration(record.time, DateTime.now())
                val (overInternal, overLaw) = overStd(mt, record.value)
                val status = if (duration.getStandardSeconds <= overTimeLimit)
                  MonitorStatus.map(record.status).desp
                else
                  "通訊中斷"

                MonitorTypeStatus(mCase.desp, format(mt, Some(record.value)), mCase.unit, measuringByStr,
                  MonitorStatus.map(record.status).desp,
                  MonitorStatus.getCssClassStr(record.status, overInternal, overLaw), mCase.order)
              } else {
                MonitorTypeStatus(mCase.desp, format(mt, None), mCase.unit, measuringByStr,
                  "通訊中斷",
                  "abnormal_status", mCase.order)
              }
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