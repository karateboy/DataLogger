package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import akka.actor._

object ModuleType extends Enumeration {
  val AiModule = Value
  val DiModule = Value
  val DoModule = Value
}

object Adam5000 extends DriverOps {
  case class ChannelCfg(enable: Boolean, mt: Option[MonitorType.Value], max: Option[Double], mtMax: Option[Double],
                        min: Option[Double], mtMin: Option[Double], var repairMode: Option[Boolean])
  case class ModuleCfg(moduleID: String, addr: Int, ch: Seq[ChannelCfg])
  case class Adam5000Param(moduleList: Seq[ModuleCfg])
  case class ModuleInfo(id: String, desp: String, moduleType: ModuleType.Value, channelNumber: Int) {
    def infoPair = id -> this
  }

  import ModuleType._
  val moduleMap = Map(
    ModuleInfo("5051", "DI 5051", DiModule, 16).infoPair,
    ModuleInfo("5056", "DO 5056", DoModule, 16).infoPair,
    ModuleInfo("5017", "AI 5017", AiModule, 8).infoPair
  )

  implicit val docfgReads = Json.reads[ChannelCfg]
  implicit val cfgReads = Json.reads[ModuleCfg]
  implicit val reads = Json.reads[Adam5000Param]

  override def getMonitorTypes(json: String) = {
    val param = Adam5000.validateParam(json)
    val aiModuleList = param.moduleList.filter { m => moduleMap(m.moduleID).moduleType == ModuleType.AiModule }
    aiModuleList.flatMap { module =>
      val activeChannel = module.ch.filter { _.enable }
      activeChannel.flatMap { _.mt }
    }.toList
  }

  override def verifyParam(json: String) = {
    val ret = validateParam(json)
    json
  }

  import Protocol.ProtocolParam

  override def start(id: String, protocolParam: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val driverParam = Adam5000.validateParam(param)

    Adam5000Collector.start(id, protocolParam, driverParam)
  }

  def stop = {

  }

  def validateParam(json: String) = {
    Logger.debug(json)
    val ret = Json.parse(json).validate[Adam5000Param]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      params => {
        for (module <- params.moduleList) {
          if (!moduleMap.contains(module.moduleID)) {
            val errMsg = s"未知的module:${module.moduleID}"
            Logger.error(errMsg)
            Logger.debug(moduleMap.toString())
            throw new Exception(errMsg)
          }
        }

        params
      })
  }

  override def getCalibrationTime(param: String) = None
}

