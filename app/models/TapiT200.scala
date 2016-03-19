package models
import play.api._

object TapiT200 extends TapiTxx(ModelConfig("T200", List("NOx", "NO", "NO2"))) {
  val modelReg = readModelSetting

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

  def findIdx(regValue: ModelRegValue, addr: Int) = {
    val dataReg = regValue.inputRegs.zipWithIndex.find(r_idx => r_idx._1._1.addr == addr)
    if (dataReg.isEmpty)
      throw new Exception("No data register!")

    dataReg.get._2
  }

  var regIdxNox: Option[Int] = None //30
  var regIdxNo: Option[Int] = None //34
  var regIdxNo2: Option[Int] = None //38

  override def reportData(regValue: ModelRegValue) = {
    def findIdx = findDataRegIdx(regValue)(_)
    val vNOx = regValue.inputRegs(regIdxNox.getOrElse({
      regIdxNox = Some(findIdx(30))
      regIdxNox.get
    }))

    val vNO = regValue.inputRegs(regIdxNo.getOrElse({
      regIdxNo = Some(findIdx(34))
      regIdxNo.get
    }))

    val vNO2 = regValue.inputRegs(regIdxNo2.getOrElse({
      regIdxNo2 = Some(findIdx(38))
      regIdxNo2.get
    }))

    context.parent ! ReportData(List(MonitorTypeData(NOx, vNOx._2.toDouble, collectorState),
      MonitorTypeData(NO, vNO._2.toDouble, collectorState),
      MonitorTypeData(NO2, vNO2._2.toDouble, collectorState)))
  }

  import com.serotonin.modbus4j.locator.BaseLocator
  import com.serotonin.modbus4j.code.DataType

  def triggerZeroCalibration(v: Boolean) {
    try {
      val locator = BaseLocator.coilStatus(config.slaveID, 20)
      masterOpt.get.setValue(locator, v)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def readCalibratingValue(): List[Double] = {
    try {
      val noxLoc = BaseLocator.inputRegister(config.slaveID, 18, DataType.FOUR_BYTE_FLOAT)
      val noxV = masterOpt.get.getValue(noxLoc)
      val noLoc = BaseLocator.inputRegister(config.slaveID, 22, DataType.FOUR_BYTE_FLOAT)
      val noV = masterOpt.get.getValue(noLoc)
      val no2Loc = BaseLocator.inputRegister(config.slaveID, 26, DataType.FOUR_BYTE_FLOAT)
      val no2V = masterOpt.get.getValue(no2Loc)
      List(noxV.floatValue(), noV.floatValue(), no2V.floatValue())
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
        throw ex
    }
  }

  def triggerSpanCalibration(v: Boolean) {
    try {
      if (v) {
        val noxSpanLocator = BaseLocator.holdingRegister(config.slaveID, 0, DataType.FOUR_BYTE_FLOAT)
        masterOpt.get.setValue(noxSpanLocator, 450f)
        val nonSpanLocator = BaseLocator.holdingRegister(config.slaveID, 2, DataType.FOUR_BYTE_FLOAT)
        masterOpt.get.setValue(noxSpanLocator, 450f)
      }

      val locator = BaseLocator.coilStatus(config.slaveID, 21)
      masterOpt.get.setValue(locator, v)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  def getSpanStandard(): List[Double] = {
    List(450, 450, 450)
  }
} 