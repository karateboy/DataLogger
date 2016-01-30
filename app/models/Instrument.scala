package models

import com.github.nscala_time.time.Imports._
import play.api._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.ExecutionContext.Implicits.global
import org.joda.time.LocalTime
case class Instrument(_id: String, instType: InstrumentType.Value, protocol: Protocol.Value, tcp_url: String, serial_port: Int, calibrationTime: Option[LocalTime])
import org.mongodb.scala._
import ModelHelper._

object Instrument {
  implicit val reader = Json.reads[Instrument]
  implicit val writer = Json.writes[Instrument]
  val collection = MongoDB.database.getCollection("instruments")
  def toDocument(inst: Instrument) = {
    val json = Json.toJson(inst)
    Document(json.toString())
  }
  def newInstrument(inst: Instrument) = {
    val f = collection.insertOne(toDocument(inst)).toFuture()
    waitReadyResult(f)
    true
  }
  
  def getInstrumentList() = {
    val f = collection.find().toFuture()
    waitReadyResult(f)
  }
  
  import org.mongodb.scala.model.Filters._
  def delete(id:String) = {
    val f = collection.deleteOne(equal("_id", id)).toFuture()
    waitReadyResult(f)
    true
  }
}