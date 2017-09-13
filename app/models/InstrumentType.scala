package models
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
case class ProtocolInfo(id: Protocol.Value, desp: String)
case class InstrumentTypeInfo(id: InstrumentType.Value, desp: String, protocolInfo: List[ProtocolInfo])
case class InstrumentType(id: InstrumentType.Value, desp: String, protocol: List[Protocol.Value],
                          driver: DriverOps, analog: Boolean = false) {
  def infoPair = id -> this
}

trait DriverOps {
  import Protocol.ProtocolParam
  import akka.actor._

  def verifyParam(param: String): String
  def getMonitorTypes(param: String): List[MonitorType.Value]
  def getCalibrationTime(param: String): Option[LocalTime]
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext): ActorRef
}

object InstrumentType extends Enumeration {
  import Protocol._
  implicit val reader: Reads[InstrumentType.Value] = EnumUtils.enumReads(InstrumentType)
  implicit val writer: Writes[InstrumentType.Value] = EnumUtils.enumWrites

  implicit val prtocolWrite = Json.writes[ProtocolInfo]
  implicit val write = Json.writes[InstrumentTypeInfo]

  val baseline9000 = Value
  val adam4017 = Value
  val adam4068 = Value
  val adam5000 = Value
  
  val t100 = Value
  val t200 = Value
  val t300 = Value
  val t360 = Value
  val t400 = Value
  val t700 = Value

  val TapiTypes = List(t100, t200, t300, t360, t400, t700)

  val verewa_f701 = Value

  val moxaE1240 = Value
  val moxaE1212 = Value

  val horiba370 = Value
  val gps = Value

  def getInstInfoPair(instType: InstrumentType) = {
    instType.id -> instType
  }

  val map = Map(
    InstrumentType(baseline9000, "Baseline 9000 MNME Analyzer", List(serial), Baseline9000).infoPair,
    InstrumentType(adam4017, "Adam 4017", List(serial), Adam4017, true).infoPair,
    InstrumentType(adam4068, "Adam 4068", List(serial), Adam4068, true).infoPair,
    InstrumentType(adam5000, "Adam 5000L", List(tcp), Adam5000, true).infoPair,
    InstrumentType(t100, "TAPI T100", List(tcp), TapiT100).infoPair,
    InstrumentType(t200, "TAPI T200", List(tcp), TapiT200).infoPair,
    InstrumentType(t300, "TAPI T300", List(tcp), TapiT300).infoPair,
    InstrumentType(t360, "TAPI T360", List(tcp), TapiT360).infoPair,
    InstrumentType(t400, "TAPI T400", List(tcp), TapiT400).infoPair,
    InstrumentType(t700, "TAPI T700", List(tcp), TapiT700).infoPair,

    InstrumentType(verewa_f701, "Verewa F701-20", List(serial), VerewaF701_20).infoPair,
    InstrumentType(moxaE1240, "MOXA E1240", List(tcp), MoxaE1240).infoPair,
    InstrumentType(moxaE1212, "MOXA E1212", List(tcp), MoxaE1212).infoPair,
    InstrumentType(horiba370, "Horiba APXX-370", List(tcp), Horiba370).infoPair,
    InstrumentType(gps, "GPS", List(serial), GPS).infoPair)
}

