package models
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
case class ProtocolInfo(id:Protocol.Value, desp:String)
case class InstrumentTypeInfo(id:InstrumentType.Value ,desp:String, protocolInfo:List[ProtocolInfo])
case class InstrumentType(id:InstrumentType.Value ,desp:String, protocol:List[Protocol.Value], driver:DriverOps)

trait DriverOps {
  import Protocol.ProtocolParam
  import akka.actor._
  
  def verifyParam(param:String):String
  def getMonitorTypes(param:String):List[MonitorType.Value]
  def getCalibrationTime(param:String):Option[LocalTime]
  def start(id:String, protocol:ProtocolParam, param:String)(implicit context:ActorContext):ActorRef
}

object InstrumentType extends Enumeration{
  import Protocol._
  implicit val reader: Reads[InstrumentType.Value] = EnumUtils.enumReads(InstrumentType)
  implicit val writer: Writes[InstrumentType.Value] = EnumUtils.enumWrites

  implicit val prtocolWrite = Json.writes[ProtocolInfo]
  implicit val write = Json.writes[InstrumentTypeInfo]

  //val baseline9000 = Value
  val adam4017 = Value
  val t100 = Value
  val t200 = Value
  val t300 = Value
  val t360 = Value
  val t400 = Value
  val t700 = Value

  val verewa_f701 = Value
  val map = Map(
      //baseline9000->InstrumentType(baseline9000, "Baseline 9000 MNME Analyzer", List(tcp, serial)),
      adam4017->InstrumentType(adam4017, "Adam 4017", List(serial), Adam4017),
      t100->InstrumentType(t100, "TAPI T100", List(tcp), TapiT100),
      t200->InstrumentType(t200, "TAPI T200", List(tcp), TapiT200),
      t300->InstrumentType(t300, "TAPI T300", List(tcp), TapiT300),
      t360->InstrumentType(t360, "TAPI T360", List(tcp), TapiT360),
      t400->InstrumentType(t400, "TAPI T400", List(tcp), TapiT400),
      t700->InstrumentType(t700, "TAPI T700", List(tcp), TapiT700),

      verewa_f701->InstrumentType(verewa_f701, "Verewa F701-20", List(serial), VerewaF701_20)
      ) 
}

