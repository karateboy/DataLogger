package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.actor._

object Adam4017 extends DriverOps {
  case class ChannelCfg(enable: Boolean, mt: Option[MonitorType.Value], max: Option[Double], mtMax: Option[Double],
                        min: Option[Double], mtMin: Option[Double], repairMode: Option[Boolean])
  case class Adam4017Param(addr: String, ch: Seq[ChannelCfg])

  implicit val cfgReads = Json.reads[ChannelCfg]
  implicit val reads = Json.reads[Adam4017Param]

  override def getMonitorTypes(param: String) = {
    val paramList = Adam4017.validateParam(param)
    val mtList = paramList.flatMap { p => p.ch.filter { _.enable }.flatMap { _.mt }.toList }
    val rawMtList = mtList map {
      MonitorType.getRawMonitorType(_)
    }
    mtList ++ rawMtList
  }

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[List[Adam4017Param]]
    ret.fold(
      error => {
        throw new Exception(JsError.toJson(error).toString())
      },
      paramList => {
        for (param <- paramList) {
          if (param.ch.length != 8) {
            throw new Exception("ch # shall be 8")
          }
          for (cfg <- param.ch) {
            if (cfg.enable) {
              assert(cfg.mt.isDefined)
              assert(cfg.max.get > cfg.min.get)
              assert(cfg.mtMax.get > cfg.mtMin.get)
              MonitorType.ensureRawMonitorType(cfg.mt.get, "V")
            }
          }
        }
        json
      })
  }

  import Protocol.ProtocolParam

  override def start(id: String, protocolParam: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val driverParam = Adam4017.validateParam(param)
    Adam4017Collector.start(id, protocolParam, driverParam)
  }

  def stop = {

  }

  def validateParam(json: String) = {
    val ret = Json.parse(json).validate[List[Adam4017Param]]
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