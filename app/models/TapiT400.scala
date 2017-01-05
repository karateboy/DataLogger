package models
import play.api._

object TapiT400 extends TapiTxx(ModelConfig("T400", List("O3"))) {
  lazy val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T400Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }
}

import TapiTxx._
class T400Collector(instId: String, modelReg: ModelReg, config: TapiConfig) extends TapiTxxCollector(instId, modelReg, config) {
  import DataCollectManager._
  import TapiTxx._
  val O3 = MonitorType.withName("O3")

  var regIdxO3: Option[Int] = None

  override def reportData(regValue: ModelRegValue) = {
    def findIdx = findDataRegIdx(regValue)(_)

    val vO3 = regValue.inputRegs(regIdxO3.getOrElse({
      regIdxO3 = Some(findIdx(18))
      regIdxO3.get
    }))

    ReportData(List(MonitorTypeData(O3, vO3._2.toDouble, collectorState)))

  }

  import com.serotonin.modbus4j.locator.BaseLocator
  import com.serotonin.modbus4j.code.DataType

  override def triggerZeroCalibration(v: Boolean) {
    try {
      super.triggerZeroCalibration(v)
      
      val locator = BaseLocator.coilStatus(config.slaveID, 20)
      masterOpt.get.setValue(locator, v)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def triggerSpanCalibration(v: Boolean) {
    try {
      super.triggerSpanCalibration(v)

      val locator = BaseLocator.coilStatus(config.slaveID, 21)
      masterOpt.get.setValue(locator, v)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def resetToNormal = {
    try {
      context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
      masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 20), false)
      masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 21), false)      
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

} 