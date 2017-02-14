package models
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api._

case class InstrumentCommand(cmd:String, var name:String, val instId:String)
object InstrumentCommand {
  implicit val write = Json.writes[InstrumentCommand]
  implicit val read = Json.reads[InstrumentCommand]
  
  val AutoCalibration = InstrumentCommand("AutoCalibration", "自動校正", "")
  val ManualZeroCalibration = InstrumentCommand("ManualZeroCalibration", "零點校正", "")
  val ManualSpanCalibration = InstrumentCommand("ManualSpanCalibration", "全幅校正", "")
  val BackToNormal = InstrumentCommand("BackToNormal", "中斷校正", "")
  
}