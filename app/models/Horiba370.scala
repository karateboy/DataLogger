package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.github.nscala_time.time.Imports._
import com.typesafe.config.ConfigFactory

case class Horiba370Config(calibrationTime: LocalTime,
                           raiseTime: Int, downTime: Int, holdTime: Int,
                           calibrateZeoSeq: Option[Int], calibrateSpanSeq: Option[Int],
                           calibratorPurgeSeq: Option[Int], calibratorPurgeTime: Option[Int])

object Horiba370 extends DriverOps {
  implicit val cfgRead = Json.reads[Horiba370Config]
  implicit val cfgWrite = Json.writes[Horiba370Config]

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[Horiba370Config]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        Json.toJson(param).toString()
      })
  }

  override def getMonitorTypes(param: String): List[MonitorType.Value] = {
    List(MonitorType.withName("CH4"), MonitorType.withName("NMHC"), MonitorType.withName("THC"))
  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[Horiba370Config]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  override def getCalibrationTime(param: String) = {
    val config = validateParam(param)
    Some(config.calibrationTime)
  }

  import Protocol.ProtocolParam
  import akka.actor._

  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext): ActorRef = {
    val config = validateParam(param)

    Horiba370Collector.start(id, protocol, config)
  }

  val FlameStatus = "FlameStatus"
  val Press = "Press"
  val Flow = "Flow"
  val Temp = "Temp"

  val InstrumentStatusTypeList = List(
    InstrumentStatusType(FlameStatus, 10, "Flame Status", "0:Extinguishing/1:Ignition sequence/2:Ignition"),
    InstrumentStatusType(Press + 0, 37, "Presssure 0", "kPa"),
    InstrumentStatusType(Press + 1, 37, "Presssure 1", "kPa"),
    InstrumentStatusType(Press + 2, 37, "Presssure 2", "kPa"),
    InstrumentStatusType(Flow + 0, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 1, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 2, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 3, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 4, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 5, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 6, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 7, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 8, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Flow + 9, 38, "Flow 0", "L/min"),
    InstrumentStatusType(Temp + 0, 39, "Temperature 0", "C"),
    InstrumentStatusType(Temp + 1, 39, "Temperature 1", "C"),
    InstrumentStatusType(Temp + 2, 39, "Temperature 2", "C"),
    InstrumentStatusType(Temp + 3, 39, "Temperature 3", "C"),
    InstrumentStatusType(Temp + 4, 39, "Temperature 4", "C"),
    InstrumentStatusType(Temp + 5, 39, "Temperature 5", "C"),
    InstrumentStatusType(Temp + 6, 39, "Temperature 6", "C"),
    InstrumentStatusType(Temp + 7, 39, "Temperature 7", "C"),
    InstrumentStatusType(Temp + 8, 39, "Temperature 8", "C"),
    InstrumentStatusType(Temp + 9, 39, "Temperature 9", "C"))
}