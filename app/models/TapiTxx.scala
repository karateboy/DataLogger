package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.github.nscala_time.time.Imports._
import com.typesafe.config.ConfigFactory

case class TapiConfig(slaveID: String, calibrationTime: Option[LocalTime], monitorTypes: Option[List[MonitorType.Value]])
case class ModelConfig(model: String, monitorTypeIDs: List[String])

object TapiTxx {
  var count = 0
  val modelMap = Map(
    "T100" -> ModelConfig("T100", List("SO2")),
    "T200" -> ModelConfig("T200", List("NO", "NO2", "NOx")),
    "T300" -> ModelConfig("T300", List("CO")))

  case class InputReg(addr: Int, desc: String, unit: String)
  case class HoldingReg(addr: Int, desc: String, unit: String)
  case class DiscreteInputReg(addr: Int, desc: String)
  case class CoilReg(addr: Int, desc: String)
  case class ModelReg(inputRegs:List[InputReg], holdingRegs:List[HoldingReg], 
      modeRegs:List[DiscreteInputReg], warnRegs:List[DiscreteInputReg], coilRegs:List[CoilReg])
  
}

class TapiTxx(modelConfig: ModelConfig) extends DriverOps {
  implicit val cfgReads = Json.reads[TapiConfig]
  implicit val cfgWrites = Json.writes[TapiConfig]
  import Protocol.ProtocolParam
  import TapiTxx._
  
  def readModelSetting = {
    val model = modelConfig.model
    val driverConfig = ConfigFactory.load(model)
    import java.util.ArrayList

    val inputRegList = {
      val inputRegAnyList = driverConfig.getAnyRefList(s"TAPI.$model.Input.reg")
      for {
        i <- 0 to inputRegAnyList.size() - 1
        reg = inputRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        InputReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String], v.get(2).asInstanceOf[String])
      }
    }

    val holdingRegList = {
      val holdingRegAnyList = driverConfig.getAnyRefList(s"TAPI.$model.Holding.reg")
      for {
        i <- 0 to holdingRegAnyList.size() - 1
        reg = holdingRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        HoldingReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String], v.get(2).asInstanceOf[String])
      }
    }
    
    val modeRegList = {
      val modeRegAnyList = driverConfig.getAnyRefList(s"TAPI.$model.DiscreteInput.mode")
      for {
        i <- 0 to modeRegAnyList.size() - 1
        reg = modeRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        DiscreteInputReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String])
      }
    }

    val warnRegList = {
      val warnRegAnyList = driverConfig.getAnyRefList(s"TAPI.$model.DiscreteInput.warning")
      for {
        i <- 0 to warnRegAnyList.size() - 1
        reg = warnRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        DiscreteInputReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String])
      }
    }
        
    val coilRegList = {
      val coilRegAnyList = driverConfig.getAnyRefList(s"TAPI.$model.Coil.reg")
      for {
        i <- 0 to coilRegAnyList.size() - 1
        reg = coilRegAnyList.get(i)
        v = reg.asInstanceOf[ArrayList[Any]]
      } yield {
        CoilReg(v.get(0).asInstanceOf[Int], v.get(1).asInstanceOf[String])
      }
    }    
    
    ModelReg(inputRegList.toList, holdingRegList.toList, modeRegList.toList, warnRegList.toList, coilRegList.toList) 
  }

  override def verifyParam(json: String) = {
    val ret = Json.parse(json).validate[TapiConfig]
    ret.fold(
      error => {
        Logger.error(JsError.toJson(error).toString())
        throw new Exception(JsError.toJson(error).toString())
      },
      param => {
        //Append monitor Type into config
        val mt = modelConfig.monitorTypeIDs.map { MonitorType.withName(_) }
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

  override def getCalibrationTime(param: String) = {
    val config = validateParam(param)
    config.calibrationTime
  }
}