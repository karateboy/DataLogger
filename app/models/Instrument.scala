package models

import com.github.nscala_time.time.Imports._
import play.api._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.ExecutionContext.Implicits.global
import org.joda.time.LocalTime
case class InstrumentInfo(_id: String, instType: InstrumentType.Value, 
    protocol: Protocol.Value, protocolParam:String, monitorTypes:String)
    
case class Instrument(_id: String, instType: InstrumentType.Value, 
    protocol: Protocol.Value, tcp_url: String, serial_port: Int, param: String){
  def getMonitorTypes:List[MonitorType.Value] = {
    instType match{
      case InstrumentType.adam4017=>
        val p = Adam4017.validateParam(param)
        p.ch.flatMap  { _.mt }.toList
      case InstrumentType.baseline9000=>
        List(MonitorType.withName("CH4"))
    }
  }
  
  def getInfoClass={
    val mtStr = getMonitorTypes.map { MonitorType.map(_).desp }.mkString(",")
    val protocolParam =
      protocol match{
      case Protocol.tcp=>
        tcp_url
      case Protocol.serial=>
        s"COM$serial_port" 
    }
    InstrumentInfo(_id, instType, protocol, protocolParam, mtStr) 
  }
    
}
import org.mongodb.scala._
import ModelHelper._

object Instrument {
  implicit val reader = Json.reads[Instrument]
  implicit val writer = Json.writes[Instrument]
  implicit val infoWrites = Json.writes[InstrumentInfo]
  
  val collectionName = "instruments"
  val collection = MongoDB.database.getCollection(collectionName)
  def toDocument(inst: Instrument) = {
    val json = Json.toJson(inst)
    Document(json.toString())
  }

  def toInstrument(doc: Document) = {
    val ret = Json.parse(doc.toJson()).validate[Instrument]
    ret.fold(error => {
      throw new Exception(JsError.toJson(error).toString)
    },
      v => { v })
  }
  
  def init(colNames:Seq[String]){
    if(!colNames.contains(collectionName)){
      val f = MongoDB.database.createCollection(collectionName).toFuture()
      f.onFailure(futureErrorHandler)
    }      
  }
  
  def newInstrument(inst: Instrument) = {
    val f = collection.insertOne(toDocument(inst)).toFuture()
    waitReadyResult(f)
    true
  }
  
  def getInstrumentList() = {
    val f = collection.find().toFuture()
    waitReadyResult(f).map { toInstrument }
  }
  
  import org.mongodb.scala.model.Filters._
  def delete(id:String) = {
    val f = collection.deleteOne(equal("_id", id)).toFuture()
    waitReadyResult(f)
    true
  }
}