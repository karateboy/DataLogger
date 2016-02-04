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
       inst.instType match{
         case InstrumentType.adam4017=>
           try{
             import Adam4017Collector._
             val param = Adam4017.validateParam(inst.param)
             val collector = Adam4017Collector.start(inst.serial_port, param)
             
             collectorMap += (inst._id -> collector)
           }catch{
             case ex:Throwable=>
               Logger.error(ex.getMessage) 
               throw ex
           }
         case InstrumentType.baseline9000=>
           //FIXME...
           Logger.info("Bypass collecting for now")
       }
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