package models
import play.api._

object TapiT400 extends TapiTxx(ModelConfig("T400", List("O3"))){
  val modelReg = readModelSetting
  
  import Protocol.ProtocolParam
  import akka.actor._
  def start(id:String, protocol:ProtocolParam, param:String)(implicit context:ActorContext)={
    val config = validateParam(param)
    TapiTxxCollector.start(id, protocol, config, modelReg, Props[T400Collector])    
  }
}

class T400Collector extends TapiTxxCollector{
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
    
    context.parent ! ReportData(List(MonitorTypeData(O3, vO3.toDouble, currentStatus)))
    
  }
} 