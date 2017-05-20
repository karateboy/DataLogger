package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.actor._

object MoxaE1240 extends DriverOps {
  case class ChannelCfg(enable: Boolean, mt: Option[MonitorType.Value], max: Option[Double], mtMax: Option[Double],
                        min: Option[Double], mtMin: Option[Double], repairMode: Option[Boolean])
  case class MoxaE1240Param(addr:Int, ch: Seq[ChannelCfg])

  implicit val cfgReads = Json.reads[ChannelCfg]
  implicit val reads = Json.reads[MoxaE1240Param]

  override def getMonitorTypes(param: String) = {
    val e1240Param = MoxaE1240.validateParam(param)
    e1240Param.ch.filter { _.enable }.flatMap { _.mt }.toList
  }

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[MoxaE1240Param]
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
              assert(cfg.max.get > cfg.min.get)
              assert(cfg.mtMax.get > cfg.mtMin.get)
            }
          }
        json
      })
  }

  import Protocol.ProtocolParam

  override def start(id: String, protocolParam: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val driverParam = MoxaE1240.validateParam(param)

    MoxaE1240Collector.start(id, protocolParam, driverParam)
  }

  def stop = {

  }

  
  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[MoxaE1240Param]
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