package models
import play.api._
object MC3 extends ModbusBase(ModbusModelConfig("MC3", Map(
  MonitorType.withName("HCl") -> 1,
  MonitorType.withName("CO") -> 1,
  MonitorType.withName("NO") -> 1,
  MonitorType.withName("NO2") -> 1,
  MonitorType.withName("SO2") -> 1,
  MonitorType.withName("H2O") -> 1,
  MonitorType.withName("CO2") -> 1))) {
  lazy val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T100Collector], id, config.slaveID, modelReg, getModelConfig)
    TapiTxxCollector.start(protocol, props)
  }
}

class MC3Collector(instId: String, slaveID: Int, modelReg: ModelReg, config: ModbusModelConfig)
    extends ModbusBaseCollector(instId, slaveID, modelReg, config) {
  import DataCollectManager._
  import TapiTxx._
  import com.serotonin.modbus4j.locator.BaseLocator
  import com.serotonin.modbus4j.code.DataType

  var mtIdxMap = Map.empty[MonitorType.Value, Int]

  override def reportData(regValue: ModelRegValue) = {
    def findIdx = findDataRegIdx(regValue)(_)
    if (mtIdxMap.isEmpty) {
      for {
        mt_addr <- config.mtAddrMap
        mt = mt_addr._1
        addr = mt_addr._2
      } {
        mtIdxMap = mtIdxMap + (mt -> findIdx(addr))
      }
    }

    val mtValues =
      for {
        mt_idx <- mtIdxMap
        mt = mt_idx._1
        idx = mt_idx._2
      } yield mt -> regValue.inputRegs(idx)

    val mtDataList = mtValues map {
      v =>
        val mt = v._1
        val state = getMonitorTypeStatusMap(mt)
        val value = v._2._2
        MonitorTypeData(mt, value, state)
    }

    ReportData(mtDataList.toList)
  }
}