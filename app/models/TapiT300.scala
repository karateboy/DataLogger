package models
import play.api._

object TapiT300 extends TapiTxx(ModelConfig("T300", List("CO"))) {
  lazy val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T300Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }
  
  var vCO:Option[Double] = None
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

    val measure = vCO._2.toDouble    
    if(MonitorTypeCollectorStatus.map.get(CO).isEmpty ||
        MonitorTypeCollectorStatus.map(CO) != collectorState){
      MonitorTypeCollectorStatus.map = MonitorTypeCollectorStatus.map + (CO->collectorState) 
    }
      
    if(TapiT300.vCO.isDefined)
      ReportData(List(MonitorTypeData(CO, TapiT300.vCO.get, collectorState)))
    else
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