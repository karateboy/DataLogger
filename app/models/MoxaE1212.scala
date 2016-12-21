package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.actor._

object MoxaE1212 extends DriverOps {
  case class ChannelCfg(enable: Boolean, mt: Option[MonitorType.Value], scale: Option[Double])
  case class MoxaE1212Param(addr:Int, ch: Seq[ChannelCfg])

  implicit val cfgReads = Json.reads[ChannelCfg]
  implicit val reads = Json.reads[MoxaE1212Param]

  override def getMonitorTypes(param: String) = {
    val e1212Param = MoxaE1212.validateParam(param)
    e1212Param.ch.filter { _.enable }.flatMap { _.mt }.toList
  }

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[MoxaE1212Param]
    ret.fold(
      error => {
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
          if (param.ch.length != 8) {
            throw new Exception("ch # shall be 8")
          }
          
          for (cfg <- param.ch) {
            if (cfg.enable) {
              assert(cfg.mt.isDefined)
              assert(cfg.scale.isDefined && cfg.scale.get != 0)
            }
          }
        json
      })
  }

  import Protocol.ProtocolParam

  override def start(id: String, protocolParam: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val driverParam = MoxaE1212.validateParam(param)

    MoxaE1212Collector.start(id, protocolParam, driverParam)
  }

  def stop = {

  }

  
  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[MoxaE1212Param]
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