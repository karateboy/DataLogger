package models
import play.api._

object TapiT400 extends TapiTxx(ModelConfig("T400", List("O3"))){
  val modelReg = readModelSetting
  
  import Protocol.ProtocolParam
  import akka.actor._
  def start(id:String, protocol:ProtocolParam, param:String)(implicit context:ActorContext)={
    val config = validateParam(param)
    val props = Props(classOf[T400Collector], id, modelReg, config)
    TapiTxxCollector.start(protocol, props)    
  }
}

import TapiTxx._
class T400Collector(instId:String, modelReg: ModelReg, config: TapiConfig) extends TapiTxxCollector(instId, modelReg, config){
  import DataCollectManager._
  import TapiTxx._
  val O3 = MonitorType.withName("O3")
  
  def findIdx(addr:Int)={
    val inputRegs = TapiT300.modelReg.inputRegs.zipWithIndex
    val idx = inputRegs.find(p=>(p._1.addr == addr))
    assert(idx.isDefined)
    idx.get._2    
  }
  
  val regIdxO3 = findIdx(14)
  
  override def reportData(regValue:ModelRegValue)={
    val vO3 = regValue.inputRegs(regIdxO3)
    
    context.parent ! ReportData(List(MonitorTypeData(O3, vO3.toDouble, collectorState)))
    
  }
  
  def triggerZeroCalibration(){
    
  }
  
  def readZeroValue(): List[Double]={
    List(0)
  }
  
  def exitZeroCalibration(){
    
  }

  def triggerSpanCalibration(){
    
  }
  def readSpanValue(): List[Double]={
    List(450)
  }
  
  def exitSpanCalibration(){
    
  }
  
  def getSpanStandard(): List[Double] ={
    List(450)
  }

} 