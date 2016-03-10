package models
import play.api.libs.json._
import com.github.nscala_time.time.Imports._

case class InstrumentTypeInfo(id:InstrumentType.Value ,desp:String, protocol:List[Protocol.Value])
case class InstrumentType(id:InstrumentType.Value ,desp:String, protocol:List[Protocol.Value], driver:DriverOps)

trait DriverOps {
  import Protocol.ProtocolParam
  import akka.actor._

  def verifyParam(param:String):String
  def getMonitorTypes(param:String):List[MonitorType.Value]
  def getCalibrationTime(param:String):Option[LocalTime]
  def start(protocol:ProtocolParam, param:String)(implicit context:ActorContext):ActorRef
}

object InstrumentType extends Enumeration{
  import Protocol._
  implicit val reader: Reads[InstrumentType.Value] = EnumUtils.enumReads(InstrumentType)
  implicit val writer: Writes[InstrumentType.Value] = EnumUtils.enumWrites

  //val baseline9000 = Value
  val adam4017 = Value
  val t100 = Value
  def map = Map(
      //baseline9000->InstrumentType(baseline9000, "Baseline 9000 MNME Analyzer", List(tcp, serial)),
      adam4017->InstrumentType(adam4017, "Adam 4017", List(serial), Adam4017),
      t100->InstrumentType(t100, "TAPI T100", List(tcp), TapiTxx.getModelDriver("T100"))
      ) 
}

