package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.actor._

object Adam4068 extends DriverOps {
  case class Adam4068Param(addr: String)

  implicit val reads = Json.reads[Adam4068Param]

  override def getMonitorTypes(param: String) = {
    List.empty[MonitorType.Value]
  }

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[Adam4068Param]
    ret.fold(
      error => {
        throw new Exception(JsError.toJson(error).toString())
      },
      paramList => 
        json
      )
  }

  import Protocol.ProtocolParam

  override def start(id: String, protocolParam: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val driverParam = Adam4068.validateParam(param)
    Adam4068Collector.start(id, protocolParam, driverParam)
  }

  def stop = {

  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[Adam4068Param]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      params => {
        params
      })
  }

  override def getCalibrationTime(param: String) = None
}