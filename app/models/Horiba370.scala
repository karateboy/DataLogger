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

}