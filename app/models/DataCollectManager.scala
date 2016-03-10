package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import com.github.nscala_time.time.Imports._
import play.api.Play.current
import Alarm._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._

object DataCollectManager {
  case class NewInstrument(inst:Instrument)
  case class RemoveInstrument(id:String)
  case class ReportData(mt:MonitorType.Value, value:Double, status:String)

  var manager:ActorRef = _
  def startup() = {
    manager = Akka.system.actorOf(Props[DataCollectManager], name = "dataCollectManager")
    val instrumentList = Instrument.getInstrumentList()
    instrumentList.foreach { manager ! NewInstrument(_) }
  }
  
  def startCollect(inst:Instrument){
    manager ! NewInstrument(inst) 
  }
  
  def stopCollect(id:String){
    manager ! RemoveInstrument(id)
  }
  
  def stop() = {
    val instrumentList = Instrument.getInstrumentList()
    instrumentList.foreach {inst=> manager ! RemoveInstrument(inst._id) }
  }
}

class DataCollectManager extends Actor {
  import DataCollectManager._
  var collectorMap = Map.empty[String, ActorRef]
  var dataMap = Map.empty[MonitorType.Value, List[(Double, String)]]
  
  def receive = {
    case NewInstrument(inst)=>
      val instType = InstrumentType.map(inst.instType)
      val collector = instType.driver.start(inst.protocol, inst.param)
      collectorMap += (inst._id -> collector)
      
    case RemoveInstrument(id:String)=>
      val actorOpt = collectorMap.get(id)
      if(actorOpt.isEmpty){
        Logger.error("unknown instrument ID")
      }else{
        val actor = actorOpt.get
        Logger.info(s"Stop collecting instrument $id ")
        actor ! PoisonPill
        collectorMap = collectorMap - (id)
      }
    case data:ReportData=>      
      Logger.info(s"${MonitorType.map(data.mt).desp} ${data.value} ${data.status}")
  }
}