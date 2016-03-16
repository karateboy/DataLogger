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