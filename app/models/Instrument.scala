package models

import com.github.nscala_time.time.Imports._
import play.api._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.ExecutionContext.Implicits.global
import org.joda.time.LocalTime
import Protocol.ProtocolParam
case class InstrumentInfo(_id: String, instType: String, active:Boolean,
    protocol: String, protocolParam:String, monitorTypes:String, calibrationTime:Option[String])
    
case class Instrument(_id: String, instType: InstrumentType.Value, 
    protocol: ProtocolParam, param: String, active:Boolean=true){
  
  def getMonitorTypes:List[MonitorType.Value] = {
    val instTypeCase = InstrumentType.map(instType)
    instTypeCase.driver.getMonitorTypes(param)
  }
  
  def getCalibrationTime = {
    val instTypeCase = InstrumentType.map(instType)
    instTypeCase.driver.getCalibrationTime(param)
  }
  
  def getInfoClass={
    val mtStr = getMonitorTypes.map { MonitorType.map(_).desp }.mkString(",")
    val protocolParam =
      protocol.protocol match{
      case Protocol.tcp=>
        protocol.host.get
      case Protocol.serial=>
        s"COM${protocol.comPort.get}" 
    }
    val calibrationTime = getCalibrationTime.map { t => t.toString("HH:mm") }
      
    InstrumentInfo(_id, InstrumentType.map(instType).desp, active, Protocol.map(protocol.protocol), protocolParam, mtStr, calibrationTime) 
  }
  
  def replaceParam(newParam:String)={
    Instrument(_id, instType, protocol, newParam)
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
    val doc = Document(json.toString())
    val param = doc.get("param").get.asString().getValue

    val paramDoc = Document(param.toString())
    
    doc ++ Document("param" -> paramDoc)
  }

  def toInstrument(doc: Document) = {
    val param = doc.get("param").get.asDocument().toJson()
    val doc1 = doc ++ Document("param"->param)
    
    val ret = Json.parse(doc1.toJson()).validate[Instrument]
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
  def getInstrument(id:String) = {
    val f = collection.find(equal("_id", id)).toFuture()
    waitReadyResult(f).map { toInstrument }
  }
  
  def delete(id:String) = {
    val f = collection.deleteOne(equal("_id", id)).toFuture()
    waitReadyResult(f)
    true
  }
  
  def activate(id:String) = {
    import org.mongodb.scala.model.Updates._
    val f = collection.updateOne(equal("_id", id), set("active", true)).toFuture()
    waitReadyResult(f)
    true
  }

  def deactivate(id:String) = {
    import org.mongodb.scala.model.Updates._
    val f = collection.updateOne(equal("_id", id), set("active", false)).toFuture()
    waitReadyResult(f)
    true
  }

}