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

  var co2RegIdx:Option[Int] = None
  
  override def reportData(regValue: ModelRegValue) = {
    def findIdx = findDataRegIdx(regValue)(_)
    val v = regValue.inputRegs(co2RegIdx.getOrElse({
      co2RegIdx = Some(findIdx(18))
      co2RegIdx.get
    }))
    
    context.parent ! ReportData(List(MonitorTypeData(MonitorType.withName("CO2"), v._2.toDouble, collectorState)))
  }

  def triggerZeroCalibration(v:Boolean) {
    try {
      val locator = BaseLocator.coilStatus(config.slaveID, 20)
      masterOpt.get.setValue(locator, v)
    } catch{
      case ex:Exception=>
        ModelHelper.logException(ex)
    }
  }
  
  def readCalibratingValue(): List[Double]={
    try {
      val locator = BaseLocator.inputRegister(config.slaveID, 14, DataType.FOUR_BYTE_FLOAT)
      val v = masterOpt.get.getValue(locator)
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
        masterOpt.get.setValue(targetSpanLocator, 450f)
      }
      val locator = BaseLocator.coilStatus(config.slaveID, 21)
      masterOpt.get.setValue(locator, v)
    } catch{
      case ex:Exception=>
        ModelHelper.logException(ex)
    }    
  }
  
  def getSpanStandard(): List[Double] ={
    List(450)
  }

}