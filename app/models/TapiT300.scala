package models
import play.api._

object TapiT300 extends TapiTxx(ModelConfig("T300", List("CO"))){
  val modelReg = readModelSetting
  
  import Protocol.ProtocolParam
  import akka.actor._
  def start(id:String, protocol:ProtocolParam, param:String)(implicit context:ActorContext)={
    val config = validateParam(param)
    val props = Props(classOf[T300Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)    
  }
}

import TapiTxx._
class T300Collector(instId:String, modelReg: ModelReg, config: TapiConfig) extends TapiTxxCollector(instId, modelReg, config){
  import DataCollectManager._
  import TapiTxx._
  val CO = MonitorType.withName("CO")
  
  def findIdx(addr:Int)={
    val inputRegs = TapiT300.modelReg.inputRegs.zipWithIndex
    val idx = inputRegs.find(p=>(p._1.addr == addr))
    assert(idx.isDefined)
    idx.get._2    
  }
  
  val regIdxCO = findIdx(18)
  
  override def reportData(regValue:ModelRegValue)={
    val vCO = regValue.inputRegs(regIdxCO)
    
    context.parent ! ReportData(List(MonitorTypeData(CO, vCO.toDouble, collectorState)))
    
  }
  
  import com.serotonin.modbus4j.locator.BaseLocator
  import com.serotonin.modbus4j.code.DataType

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