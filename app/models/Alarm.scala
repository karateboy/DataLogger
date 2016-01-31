package models

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions

import org.mongodb.scala._

import com.github.nscala_time.time.Imports._

import ModelHelper._
import play.api._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.json.Reads._

//alarm src format: 'T':"MonitorType"
//                  'I':"Instrument"
//                  'S':"System"

object Alarm {
  def getSrc(mt: MonitorType.Value) = s"T:${mt.toString}"
  def getSrc(inst: Instrument) = s"I:${inst._id}"
  def getSrc() = "S:System"
  case class Alarm(time: DateTime, src: String, alarmType: String, trigger: Boolean)


  implicit val format = Json.format[Alarm]

  val collectionName = "alarms"
  val collection = MongoDB.database.getCollection(collectionName)
  def toDocument(ar: Alarm) = {
    val json = Json.toJson[Alarm](ar)
    Logger.debug("toDoc" + json.toString())
    Document(json.toString())
  }

  def toAlarm(doc: Document) = {
    val time = new DateTime(doc.get("time").get.asInt64().getValue)
    val src = doc.get("src").get.asString().getValue
    val alarmType = doc.get("alarmType").get.asString().getValue
    val trigger = doc.get("trigger").get.asBoolean().getValue
    Alarm(time, src, alarmType, trigger)    
  }

  val defaultAlarm = List(
    Alarm(DateTime.now, getSrc(), "", true),
    Alarm(DateTime.now, "T:WIN_SPEED", "", true))

  def init(colNames: Seq[String]) {
    import org.mongodb.scala.model.Indexes._
    if (!colNames.contains(collectionName)) {
      val f = MongoDB.database.createCollection(collectionName).toFuture()
      f.onFailure(futureErrorHandler)
      f.onSuccess({
        case _: Seq[_] =>
          collection.createIndex(ascending("time", "src"))
          collection.insertMany(defaultAlarm.map { toDocument }).toFuture()
      })
    }
  }

  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Projections._
  import org.mongodb.scala.model.Sorts._

  def getAlarms(start: DateTime, end: DateTime) = {
    val f = collection.find(and(gte("time", start.getMillis), lt("time", end.getMillis))).sort(ascending("time")).toFuture()
    //val f = collection.find().toFuture()
    val docs = waitReadyResult(f)
    docs.map { toAlarm }
  }

}