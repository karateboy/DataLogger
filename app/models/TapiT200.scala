package models
import play.api._

object TapiT200 extends TapiTxx(ModelConfig("T200", List("NO", "NO2", "NOx"))){
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
  
  def triggerZeroCalibration(){
    
  }
  
  def readZeroValue(): List[Double]={
    List(0, 0, 0)
  }
  
  def exitZeroCalibration(){
    
  }

  def triggerSpanCalibration(){
    
  }
  def readSpanValue(): List[Double]={
    List(450, 500, 500)
  }
  
  def exitSpanCalibration(){
    
  }
  
  def getSpanStandard(): List[Double] ={
    List(450, 500, 500)
  }

} 