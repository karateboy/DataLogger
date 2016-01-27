package models
import play.api._
import play.api.mvc._
import models._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Json
import java.sql.Date
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import scala.concurrent.Future
import play.api.Play.current
import scala.concurrent.ExecutionContext.Implicits.global

case class EpaHourData(
  SiteId: String,
  SiteName: String,
  ItemId: String,
  ItemName: String,
  ItemEngName: String,
  ItemUnit: String,
  MonitorDate: String,
  MonitorValue00: Option[String],
  MonitorValue01: Option[String], MonitorValue02: Option[String], MonitorValue03: Option[String], MonitorValue04: Option[String], MonitorValue05: Option[String],
  MonitorValue06: Option[String], MonitorValue07: Option[String], MonitorValue08: Option[String], MonitorValue09: Option[String], MonitorValue10: Option[String],
  MonitorValue11: Option[String], MonitorValue12: Option[String], MonitorValue13: Option[String], MonitorValue14: Option[String], MonitorValue15: Option[String],
  MonitorValue16: Option[String], MonitorValue17: Option[String], MonitorValue18: Option[String], MonitorValue19: Option[String], MonitorValue20: Option[String],
  MonitorValue21: Option[String], MonitorValue22: Option[String], MonitorValue23: Option[String])

object EpaHourData {
  import org.json4s._
  import org.json4s.native.JsonMethods._
  implicit val formats = DefaultFormats
  def test = {
    val jsonStr = """
    [{"SiteId":"83","SiteName":"麥寮","ItemId":"144","ItemName":"小時風向值","ItemEngName":"WD_HR","ItemUnit":"degrees","MonitorDate":"2016-01-03","MonitorValue00":"355","MonitorValue01":"33","MonitorValue02":"35","MonitorValue03":"45","MonitorValue04":"47","MonitorValue05":"55","MonitorValue06":"46","MonitorValue07":"32","MonitorValue08":"57","MonitorValue09":"43","MonitorValue10":"37","MonitorValue11":"48","MonitorValue12":"58","MonitorValue13":"44","MonitorValue14":"41","MonitorValue15":"17","MonitorValue16":"1.4","MonitorValue17":"42","MonitorValue18":"45","MonitorValue19":"339","MonitorValue20":"44","MonitorValue21":"24","MonitorValue22":"34","MonitorValue23":"38"}]  
      """

    val json = parse(jsonStr)
    json.extract[Seq[EpaHourData]]
  }

  def getEpaData(offset: Int, count: Int) = {
    //val url = s"http://opendata.epa.gov.tw/ws/Data/AQXHour/?$$skip=$offset&$$top=$count&format=json"
    val url = s"http://opendata.epa.gov.tw/ws/Data/AQXHour/?$$skip=$offset&format=json"
    Logger.debug(url)
    WS.url(url).get().map {
      response =>
        parse(response.json.toString()).extract[Seq[EpaHourData]]
    }
  }
}