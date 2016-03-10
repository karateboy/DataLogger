package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.github.nscala_time.time.Imports._

case class TapiConfig(slaveID: String, calibrationTime: Option[LocalTime], monitorTypes: Option[List[MonitorType.Value]])
object TapiTxx {
  case class ModelConfig(model: String, monitorTypeIDs: List[String])

  val modelMap = Map(
    "T100" -> ModelConfig("T100", List("SO2")),
    "T200" -> ModelConfig("T200", List("NO", "NO2", "NOx")),
    "T300" -> ModelConfig("T300", List("CO")))

  def getModelDriver(model: String) = {
    val config = modelMap(model)
    new TapiTxx(config)
  }

  class TapiTxx(modelConfig: ModelConfig) extends DriverOps {
    implicit val cfgReads = Json.reads[TapiConfig]
    implicit val cfgWrites = Json.writes[TapiConfig]
    import Protocol.ProtocolParam

    override def verifyParam(json: String) = {
      val ret = Json.parse(json).validate[TapiConfig]
      ret.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          throw new Exception(JsError.toJson(error).toString())
        },
        param => {
          //Append monitor Type into config
          val mt = modelConfig.monitorTypeIDs.map { MonitorType.withName(_)}
          val newParam = TapiConfig(param.slaveID, param.calibrationTime, Some(mt))

          Json.toJson(newParam).toString()
        })
    }

    override def getMonitorTypes(param: String): List[MonitorType.Value] = {
      val config = validateParam(param)
      if (config.monitorTypes.isDefined)
        config.monitorTypes.get
      else
        List.empty[MonitorType.Value]
    }

    import akka.actor.ActorContext
    override def start(protocolParam: ProtocolParam, param: String)(implicit context: ActorContext) = {
      val config = validateParam(param)
      TapiTxxCollector.start(protocolParam, config)
    }

    def validateParam(json: String) = {
      val ret = Json.parse(json).validate[TapiConfig]
      ret.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          throw new Exception(JsError.toJson(error).toString())
        },
        param => param)
    }
    
    override def getCalibrationTime(param:String)={
      val config = validateParam(param)
      config.calibrationTime
    }
  }
}

