package models
import play.api._

object TapiT200 extends TapiTxx(ModelConfig("T200", List("NOx", "NO", "NO2"))){
  val modelReg = readModelSetting
  
  import Protocol.ProtocolParam
  import akka.actor._
  def start(id:String, protocol:ProtocolParam, param:String)(implicit context:ActorContext)={
    val config = validateParam(param)
    val props = Props(classOf[T200Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)    
  }
}

import TapiTxx._
class T200Collector(instId:String, modelReg: ModelReg, config: TapiConfig) extends TapiTxxCollector(instId, modelReg, config){
  import DataCollectManager._
  import TapiTxx._
  val NO = MonitorType.withName("NO")
  val NO2 = MonitorType.withName("NO2")
  val NOx = MonitorType.withName("NOx")
  
  def findIdx(addr:Int)={
    val inputRegs = TapiT200.modelReg.inputRegs.zipWithIndex
    val idx = inputRegs.find(p=>(p._1.addr == addr))
    assert(idx.isDefined)
    idx.get._2    
  }
  
  val regIdxNox = findIdx(30)
  val regIdxNo = findIdx(34)
  val regIdxNo2 = findIdx(38)
  
  override def reportData(regValue:ModelRegValue)={
    val vNOx = regValue.inputRegs(regIdxNox)
    val vNO = regValue.inputRegs(regIdxNo)
    val vNO2 = regValue.inputRegs(regIdxNo2)    
    
    context.parent ! ReportData(List(MonitorTypeData(NOx, vNOx.toDouble, collectorState),
        MonitorTypeData(NO, vNO.toDouble, collectorState),
        MonitorTypeData(NO2, vNO2.toDouble, collectorState)))
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
      val noxLoc = BaseLocator.inputRegister(config.slaveID, 18, DataType.FOUR_BYTE_FLOAT)
      val noxV = master.get.getValue(noxLoc)
      val noLoc = BaseLocator.inputRegister(config.slaveID, 22, DataType.FOUR_BYTE_FLOAT)
      val noV = master.get.getValue(noLoc)
      val no2Loc = BaseLocator.inputRegister(config.slaveID, 26, DataType.FOUR_BYTE_FLOAT)
      val no2V = master.get.getValue(no2Loc)
      List(noxV.floatValue(), noV.floatValue(), no2V.floatValue())
    } catch{
      case ex:Exception=>
        ModelHelper.logException(ex)
        throw ex
    }
  }
  
  def triggerSpanCalibration(v:Boolean){
    try {      
      if(v){
      val noxSpanLocator = BaseLocator.holdingRegister(config.slaveID, 0, DataType.FOUR_BYTE_FLOAT)
      master.get.setValue(noxSpanLocator, 450f)
      val nonSpanLocator = BaseLocator.holdingRegister(config.slaveID, 2, DataType.FOUR_BYTE_FLOAT)
      master.get.setValue(noxSpanLocator, 450f)
      }
      
      val locator = BaseLocator.coilStatus(config.slaveID, 21)
      master.get.setValue(locator, v)
    } catch{
      case ex:Exception=>
        ModelHelper.logException(ex)
    }    
  }
      
  def getSpanStandard(): List[Double] ={
    List(450, 450, 450)
  }
} 