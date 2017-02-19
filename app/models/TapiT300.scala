package models
import play.api._

object TapiT300 extends TapiTxx(ModelConfig("T300", List("CO"))) {
  lazy val modelReg = readModelSetting

  val scaleOpt = Play.current.configuration.getDouble("T300.scale")
  val offsetOpt = Play.current.configuration.getDouble("T300.offset")

  if(scaleOpt.isDefined)
    Logger.info(s"T300.scale=${scaleOpt.get}")
  
  if(offsetOpt.isDefined)
    Logger.info(s"T300.offset=${offsetOpt.get}")
  
  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T300Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }
}

import TapiTxx._
class T300Collector(instId: String, modelReg: ModelReg, config: TapiConfig) extends TapiTxxCollector(instId, modelReg, config) {
  import DataCollectManager._
  import TapiTxx._
  val CO = MonitorType.withName("CO")

  var regIdxCO: Option[Int] = None

  override def reportData(regValue: ModelRegValue) = {
    def findIdx = findDataRegIdx(regValue)(_)
    val vCO = regValue.inputRegs(regIdxCO.getOrElse({
      regIdxCO = Some(findIdx(18))
      regIdxCO.get
    }))

    val adjustedMeasure =
      for {
        scale <- TapiT300.scaleOpt
        offset <- TapiT300.offsetOpt
      } yield scale * (vCO._2.toDouble - offset)

    val measure = if(adjustedMeasure.isEmpty)
      vCO._2.toDouble
    else
      adjustedMeasure.get
      
    ReportData(List(MonitorTypeData(CO, measure, collectorState)))

  }

  import com.serotonin.modbus4j.locator.BaseLocator
  import com.serotonin.modbus4j.code.DataType

  override def triggerZeroCalibration(v: Boolean) {
    try {
      super.triggerZeroCalibration(v)

      if (config.skipInternalVault != Some(true)) {
        val locator = BaseLocator.coilStatus(config.slaveID, 20)
        masterOpt.get.setValue(locator, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def triggerSpanCalibration(v: Boolean) {
    try {
      super.triggerSpanCalibration(v)

      if (config.skipInternalVault != Some(true)) {
        val locator = BaseLocator.coilStatus(config.slaveID, 21)
        masterOpt.get.setValue(locator, v)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def resetToNormal = {
    try {
      super.resetToNormal

      if (config.skipInternalVault != Some(true)) {
        masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 20), false)
        masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 21), false)
      }
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }
} 