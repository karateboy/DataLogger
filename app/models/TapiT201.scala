package models
import play.api._

object TapiT201 extends TapiTxx(ModelConfig("T201", List("TNX", "NH3", "NOx", "NO", "NO2"))) {
  lazy val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T201Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }
}

import TapiTxx._
class T201Collector(instId: String, modelReg: ModelReg, config: TapiConfig) extends TapiTxxCollector(instId, modelReg, config) {
  import DataCollectManager._
  import TapiTxx._
  val TNX = MonitorType.withName("TNX")
  val NH3 = MonitorType.withName("NH3")
  val NO = MonitorType.withName("NO")
  val NO2 = MonitorType.withName("NO2")
  val NOx = MonitorType.withName("NOx")

  def findIdx(regValue: ModelRegValue, addr: Int) = {
    val dataReg = regValue.inputRegs.zipWithIndex.find(r_idx => r_idx._1._1.addr == addr)
    if (dataReg.isEmpty)
      throw new Exception("No data register!")

    dataReg.get._2
  }

  var regIdxTNX: Option[Int] = None //46
  var regIdxNH3: Option[Int] = None //50  
  var regIdxNOx: Option[Int] = None //54
  var regIdxNO: Option[Int] = None //58
  var regIdxNO2: Option[Int] = None //62

  override def reportData(regValue: ModelRegValue) =
    for {
      idxTNX <- findDataRegIdx(regValue)(46)
      idxNH3 <- findDataRegIdx(regValue)(50)
      idxNOx <- findDataRegIdx(regValue)(54)
      idxNO <- findDataRegIdx(regValue)(58)
      idxNO2 <- findDataRegIdx(regValue)(62)
      vTNX = regValue.inputRegs(idxTNX)
      vNH3 = regValue.inputRegs(idxNH3)
      vNOx = regValue.inputRegs(idxNOx)
      vNO = regValue.inputRegs(idxNO)
      vNO2 = regValue.inputRegs(idxNO2)
    } yield {
      ReportData(List(
        MonitorTypeData(TNX, vTNX._2.toDouble, collectorState),
        MonitorTypeData(NH3, vNH3._2.toDouble, collectorState),
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