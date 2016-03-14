package models
import play.api._

object TapiT100 extends TapiTxx(ModelConfig("T100", List("SO2"))){
  val modelReg = readModelSetting
  
  import Protocol.ProtocolParam
  import akka.actor._
  def start(id:String, protocol:ProtocolParam, param:String)(implicit context:ActorContext)={
    val config = validateParam(param)
    TapiTxxCollector.start(id, protocol, config, modelReg, Props[T100Collector])    
  }
}

class T100Collector extends TapiTxxCollector{
  import DataCollectManager._
  import TapiTxx._
  
  val so2RegIdx = {
    val inputRegs = TapiT100.modelReg.inputRegs.zipWithIndex
    val so2Idx = inputRegs.find(p=>(p._1.addr == 22))
    assert(so2Idx.isDefined)
    so2Idx.get._2
  }
  override def reportData(regValue:ModelRegValue)={
    val v = regValue.inputRegs(so2RegIdx)
    context.parent ! ReportData(List(MonitorTypeData(MonitorType.withName("SO2"), v.toDouble, currentStatus)))
  }
}