package models
import play.api._

object TapiT700 extends TapiTxx(ModelConfig("T700", List.empty[String])) {
  lazy val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T700Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }
}

import TapiTxx._
class T700Collector(instId: String, modelReg: ModelReg, config: TapiConfig) extends TapiTxxCollector(instId, modelReg, config) {
  import DataCollectManager._
  import TapiTxx._

  override def reportData(regValue: ModelRegValue) = {
    ReportData(List.empty[MonitorTypeData])
  }

  import com.serotonin.modbus4j.locator.BaseLocator
  import com.serotonin.modbus4j.code.DataType
  override def executeSeq(seq: Int, on:Boolean) {
    Logger.info(s"T700 execute $seq sequence.")
    try {
      val locator = BaseLocator.coilStatus(config.slaveID, seq)
      masterOpt.get.setValue(locator, on)
    } catch {
      case ex: Exception =>
        ModelHelper.logException(ex)
    }
  }

  override  def triggerZeroCalibration(v: Boolean) {}

  override def triggerSpanCalibration(v: Boolean) {}

  override def resetToNormal {
    executeSeq(101, true)
  }
} 