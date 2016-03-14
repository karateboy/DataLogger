package models
import play.api._

object TapiT200 extends TapiTxx(ModelConfig("T200", List("NO", "NO2", "NOx"))){
  val modelReg = readModelSetting
  
  import Protocol.ProtocolParam
  import akka.actor._
  def start(id:String, protocol:ProtocolParam, param:String)(implicit context:ActorContext)={
    val config = validateParam(param)
    TapiTxxCollector.start(id, protocol, config, modelReg, Props[T200Collector])    
  }
}

class T200Collector extends TapiTxxCollector{
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
    
    context.parent ! ReportData(List(MonitorTypeData(NOx, vNOx.toDouble, currentStatus),
        MonitorTypeData(NO, vNO.toDouble, currentStatus),
        MonitorTypeData(NO2, vNO2.toDouble, currentStatus)))
  }
} 