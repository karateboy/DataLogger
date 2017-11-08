package models
import play.api._

object TapiT200 extends TapiTxx(ModelConfig("T200", List("NOx", "NO", "NO2"))) {
  lazy val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T200Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }
}

import TapiTxx._
class T200Collector(instId: String, modelReg: ModelReg, config: TapiConfig) extends TapiTxxCollector(instId, modelReg, config) {
  import DataCollectManager._
  import TapiTxx._
  val NO = MonitorType.withName("NO")
  val NO2 = MonitorType.withName("NO2")
  val NOx = MonitorType.withName("NOx")

  override def reportData(regValue: ModelRegValue) =
    for {
      idxNox <- findDataRegIdx(regValue)(30)
      idxNo <- findDataRegIdx(regValue)(34)
      idxNo2 <- findDataRegIdx(regValue)(38)
      vNOx = regValue.inputRegs(idxNox)
      vNO = regValue.inputRegs(idxNo)
      vNO2 = regValue.inputRegs(idxNo2)
    } yield {
      ReportData(List(
        MonitorTypeData(NOx, vNOx._2.toDouble, collectorState),
        MonitorTypeData(NO, vNO._2.toDouble, collectorState),
        MonitorTypeData(NO2, vNO2._2.toDouble, collectorState)))

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