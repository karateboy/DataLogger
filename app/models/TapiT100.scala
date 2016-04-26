package models
import play.api._
import TapiTxx._
object TapiT100 extends TapiTxx(ModelConfig("T100", List("SO2"))) {
  lazy val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T100Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }
}

class T100Collector(instId: String, modelReg: ModelReg, config: TapiConfig) extends TapiTxxCollector(instId, modelReg, config) {
  import DataCollectManager._
  import TapiTxx._
  import com.serotonin.modbus4j.locator.BaseLocator
  import com.serotonin.modbus4j.code.DataType

  var so2Idx: Option[Int] = None

  override def reportData(regValue: ModelRegValue) = {
    val idx = so2Idx.getOrElse({
      so2Idx = Some(findDataRegIdx(regValue)(22))
      so2Idx.get
    })

    val v = regValue.inputRegs(idx)
    ReportData(List(MonitorTypeData(MonitorType.withName("SO2"), v._2.toDouble, collectorState)))
  }

  override def triggerZeroCalibration(v: Boolean) {
    try {      
      context.parent ! ExecuteSeq(config.calibrateZeoSeq.get, v)
      val locator = BaseLocator.coilStatus(config.slaveID, 20)
      masterOpt.get.setValue(locator, v)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def triggerSpanCalibration(v: Boolean) {
    try {      
      context.parent ! ExecuteSeq(config.calibrateSpanSeq.get, v)
      val locator = BaseLocator.coilStatus(config.slaveID, 21)
      masterOpt.get.setValue(locator, v)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override def resetToNormal = {
    try {      
      masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 20), false)
      masterOpt.get.setValue(BaseLocator.coilStatus(config.slaveID, 21), false)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }
}