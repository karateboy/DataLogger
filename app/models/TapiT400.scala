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

  var regIdxO3:Option[Int] = None

  override def reportData(regValue: ModelRegValue) = {
    def findIdx = findDataRegIdx(regValue)(_)

    val vO3 = regValue.inputRegs(regIdxO3.getOrElse({
      regIdxO3 = Some(findIdx(18))
      regIdxO3.get
    }))

    context.parent ! ReportData(List(MonitorTypeData(O3, vO3._2.toDouble, collectorState)))

  }

  import com.serotonin.modbus4j.locator.BaseLocator
  import com.serotonin.modbus4j.code.DataType

  def triggerZeroCalibration(v: Boolean) {
    try {
      if (v) {
        val targetZeroLocator = BaseLocator.holdingRegister(config.slaveID, 0, DataType.FOUR_BYTE_FLOAT)
        masterOpt.get.setValue(targetZeroLocator, 450f)
      }
      val locator = BaseLocator.coilStatus(config.slaveID, 20)
      masterOpt.get.setValue(locator, v)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def readCalibratingValue(): List[Double] = {
    try {
      val locator = BaseLocator.inputRegister(config.slaveID, 14, DataType.FOUR_BYTE_FLOAT)
      val v = masterOpt.get.getValue(locator)
      List(v.floatValue())
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
        throw ex
    }
  }

  def triggerSpanCalibration(v: Boolean) {
    try {
      if (v) {
        val targetSpanLocator = BaseLocator.holdingRegister(config.slaveID, 2, DataType.FOUR_BYTE_FLOAT)
        masterOpt.get.setValue(targetSpanLocator, 450f)
      }

      val locator = BaseLocator.coilStatus(config.slaveID, 21)
      masterOpt.get.setValue(locator, v)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def getSpanStandard(): List[Double] = {
    List(450)
  }

} 