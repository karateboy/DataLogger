package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.actor._

object Apax5056 extends DriverOps {
  case class Adam5056Param(addr: String)

  implicit val reads = Json.reads[Adam5056Param]

  override def getMonitorTypes(param: String) = List.empty[MonitorType.Value]

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[List[Adam5056Param]]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      params => {
        json
      })
  }

  import Protocol.ProtocolParam

  override def start(id: String, protocolParam: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val driverParam = Adam4017.validateParam(param)
    Adam4017Collector.start(id, protocolParam, driverParam)
  }

  
  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[List[Adam5056Param]]
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