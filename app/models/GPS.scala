package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.actor._

object GPS extends DriverOps {
  override def getMonitorTypes(param: String) = {
    val lat = MonitorType.withName("LAT")
    val lng = MonitorType.withName("LNG")
    List(lat, lng)
  }

  override def verifyParam(json: String) = {
    json
  }

  import Protocol.ProtocolParam

  override def start(id: String, protocolParam: ProtocolParam, param: String)(implicit context: ActorContext) = {
    GpsCollector.start(id, protocolParam)
  }

  override def getCalibrationTime(param: String) = None
}