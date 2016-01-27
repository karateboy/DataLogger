package controllers
import play.api._
import play.api.mvc._
import play.api.Logger
import models._
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import models.ModelHelper._
import play.api.Play.current

object Highchart {
  case class XAxis(categories: Option[Seq[String]], gridLineWidth: Option[Int]=None, tickInterval:Option[Int]=None)
  case class AxisLineLabel(align: String, text: String)
  case class AxisLine(color: String, width: Int, value: Double, label: Option[AxisLineLabel])
  case class AxisTitle(text: Option[Option[String]])
  case class YAxis(labels: Option[String], title: AxisTitle, plotLines: Option[Seq[AxisLine]], opposite:Boolean=false, 
      floor:Option[Int]=None, ceiling:Option[Int]=None, min:Option[Int]=None, max:Option[Int]=None, tickInterval:Option[Int]=None, 
      gridLineWidth:Option[Int]=None, gridLineColor:Option[String]=None)
      
  case class seqData(name: String, data: Seq[Seq[Option[Double]]], yAxis:Int=0, chartType:Option[String]=None)
  case class HighchartData(chart: Map[String, String],
                           title: Map[String, String],
                           xAxis: XAxis,
                           yAxis: Seq[YAxis],
                           series: Seq[seqData],
                           downloadFileName: Option[String]=None)
  case class FrequencyTab(header:Seq[String], body:Seq[Seq[String]], footer:Seq[String])                         
  case class WindRoseReport(chart:HighchartData, table:FrequencyTab)
  implicit val xaWrite = Json.writes[XAxis]
  implicit val axisLineLabelWrite = Json.writes[AxisLineLabel]
  implicit val axisLineWrite = Json.writes[AxisLine]
  implicit val axisTitleWrite = Json.writes[AxisTitle]
  implicit val yaWrite = Json.writes[YAxis]
  type lof = (Long, Option[Float])
          
  implicit val seqDataWrite:Writes[seqData] = (
    (__ \ "name").write[String] and
    (__ \ "data").write[Seq[Seq[Option[Double]]]] and
    (__ \ "yAxis").write[Int] and
    (__ \ "type").write[Option[String]]
  )(unlift(seqData.unapply))
  implicit val hcWrite = Json.writes[HighchartData]
  implicit val feqWrite = Json.writes[FrequencyTab]
  implicit val wrWrite = Json.writes[WindRoseReport]


}