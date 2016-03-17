package models
import play.api._
import TapiTxx._
object TapiT360 extends TapiTxx(ModelConfig("T360", List("CO2"))) {
  val modelReg = readModelSetting

  import Protocol.ProtocolParam
  import akka.actor._
  def start(id: String, protocol: ProtocolParam, param: String)(implicit context: ActorContext) = {
    val config = validateParam(param)
    val props = Props(classOf[T360Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)
  }
}

class T360Collector(instId: String, modelReg: ModelReg, config: TapiConfig) extends TapiTxxCollector(instId, modelReg, config) {
  import DataCollectManager._
  import TapiTxx._
  import com.serotonin.modbus4j.locator.BaseLocator
  import com.serotonin.modbus4j.code.DataType

  val co2RegIdx = {
    val inputRegs = TapiT360.modelReg.inputRegs.zipWithIndex
    val co2Idx = inputRegs.find(p => (p._1.addr == 18))
    assert(co2Idx.isDefined)
    co2Idx.get._2
  }
  override def reportData(regValue: ModelRegValue) = {
    val v = regValue.inputRegs(co2RegIdx)
    context.parent ! ReportData(List(MonitorTypeData(MonitorType.withName("CO2"), v.toDouble, collectorState)))
  }

  def triggerZeroCalibration(v:Boolean) {
    try {
      val locator = BaseLocator.coilStatus(config.slaveID, 20)
      master.get.setValue(locator, v)
    } catch{
      case ex:Exception=>
        ModelHelper.logException(ex)
    }
  }
  
  def readCalibratingValue(): List[Double]={
    try {
      val locator = BaseLocator.inputRegister(config.slaveID, 14, DataType.FOUR_BYTE_FLOAT)
      val v = master.get.getValue(locator)
      List(v.floatValue())
    } catch{
      case ex:Exception=>
        ModelHelper.logException(ex)
        throw ex
    }
  }
  
  def triggerSpanCalibration(v:Boolean){
    try {
      if(v){
        val targetSpanLocator = BaseLocator.holdingRegister(config.slaveID, 0, DataType.FOUR_BYTE_FLOAT)
        master.get.setValue(targetSpanLocator, 450f)
      }
      val locator = BaseLocator.coilStatus(config.slaveID, 21)
      master.get.setValue(locator, v)
    } catch{
      case ex:Exception=>
        ModelHelper.logException(ex)
    }    
  }
  
  def getSpanStandard(): List[Double] ={
    List(450)
  }

}