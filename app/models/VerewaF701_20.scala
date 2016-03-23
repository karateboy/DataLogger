package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.github.nscala_time.time.Imports._

case class F701_20Config(monitorType:MonitorType.Value)
object VerewaF701_20 extends DriverOps {
  lazy val supportedMonitorTypes = List(MonitorType.withName("PM2.5"), MonitorType.withName("PM10"))
  implicit val configRead = Json.reads[F701_20Config]
  implicit val configWrite = Json.writes[F701_20Config]
  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[F701_20Config]
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
    val config = validateParam(param)
    List(config.monitorType)
  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[F701_20Config]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => param)
  }

  override def getCalibrationTime(param: String) = {
    None
  }
  
  import Protocol.ProtocolParam
  import akka.actor._

  def start(id:String, protocol:ProtocolParam, param:String)(implicit context:ActorContext):ActorRef={
    val mtList = getMonitorTypes(param)
    assert(mtList.length == 1)
    VerewaF701Collector.start(id, protocol, mtList(0))
  }
}