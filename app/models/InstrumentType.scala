package models
import play.api.libs.json._

case class InstrumentType(id:InstrumentType.Value ,desp:String, mt:List[MonitorType.Value], protocol:List[Protocol.Value], needCalibration:Boolean, defaultTcpPort:Int)
object InstrumentType extends Enumeration{
  import Protocol._
  implicit val reader: Reads[InstrumentType.Value] = EnumUtils.enumReads(InstrumentType)
  implicit val writer: Writes[InstrumentType.Value] = EnumUtils.enumWrites

  val simulator = Value
  val baseline9000 = Value
  def map = Map(simulator->InstrumentType(simulator, "模擬器", List(MonitorType.withName("PM2.5")), List(serial), false, 1234),
      baseline9000->InstrumentType(baseline9000, "Baseline 9000 MNME Analyzer", List(MonitorType.withName("CH4")), List(tcp, serial), true, 2345)) 
}