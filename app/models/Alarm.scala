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
  object Level {
    val INFO = 1
    val WARN = 2
    val ERR = 3
    val map = Map(INFO -> "資訊", WARN -> "警告", ERR -> "嚴重")
  }
  val alarmLevelList = Level.INFO to Level.ERR

  def Src(mt: MonitorType.Value) = s"T:${mt.toString}"
  def Src(inst: Instrument) = s"I:${inst._id}"
  def instStr(id: String) = s"I:$id"
  def Src() = "S:System"

  def getSrcForDisplay(src: String) = {
    val part = src.split(':')
    if (part.length >= 2) {
      val srcType = part(0) match {
        case "S" =>
          "系統"
        case "I" =>
          "設備:" + part(1)
        case "T" =>
          "測項:" + MonitorType.map(MonitorType.withName(part(1))).desp
      }
      srcType
    }else{
      Logger.error(s"Invalid format $src")
      src
    }
  }

  case class Alarm2JSON(time: Long, src: String, level: Int, info: String)

  case class Alarm(time: DateTime, src: String, level: Int, desc: String) {
    def toJson = Alarm2JSON(time.getMillis, src, level, desc)
  }

  implicit val write = Json.writes[Alarm]
  implicit val jsonWrite = Json.writes[Alarm2JSON]
  //implicit val format = Json.format[Alarm]

  val collectionName = "alarms"
  val collection = MongoDB.database.getCollection(collectionName)
  def toDocument(ar: Alarm) = {
    import org.mongodb.scala.bson._
    Document("time" -> (ar.time: BsonDateTime), "src" -> ar.src, "level" -> ar.level, "desc" -> ar.desc)
  }

  def toAlarm(doc: Document) = {
    val time = new DateTime(doc.get("time").get.asDateTime().getValue)
    val src = doc.get("src").get.asString().getValue
    val level = doc.get("level").get.asInt32().getValue
    val desc = doc.get("desc").get.asString().getValue
    Alarm(time, src, level, desc)
  }

  def init(colNames: Seq[String]) {
    import org.mongodb.scala.model.Indexes._
    if (!colNames.contains(collectionName)) {
      val f = MongoDB.database.createCollection(collectionName).toFuture()
      f.onFailure(errorHandler)
      f.onSuccess({
        case _: Seq[_] =>
          collection.createIndex(ascending("time", "level", "src"))
      })
    }
  }

  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Projections._
  import org.mongodb.scala.model.Sorts._

  def getAlarms(level: Int, start: DateTime, end: DateTime) = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end
    val f = collection.find(and(gte("time", startB), lt("time", endB), gte("level", level))).sort(ascending("time")).toFuture()

    val docs = waitReadyResult(f)
    docs.map { toAlarm }
  }

  def getAlarmsFuture(start: DateTime, end: DateTime) = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end
    val f = collection.find(and(gte("time", startB), lt("time", endB))).sort(ascending("time")).toFuture()

    for (docs <- f)
      yield docs.map { toAlarm }
  }

  private def log(ar: Alarm) {
    import org.mongodb.scala.bson.BsonDateTime
    //None blocking...
    val start: BsonDateTime = ar.time - 30.minutes
    val end: BsonDateTime = ar.time

    val countObserver = collection.count(and(gte("time", start), lt("time", end),
      equal("src", ar.src), equal("level", ar.level), equal("desc", ar.desc)))

    countObserver.subscribe(
      (count: Long) => {
        if (count == 0){
          val f = collection.insertOne(toDocument(ar)).toFuture()
        }
      }, // onNext
      (ex: Throwable) => Logger.error("Alarm failed:", ex), // onError
      () => {} // onComplete
      )

  }

  def log(src: String, level: Int, desc: String) {
    val ar = Alarm(DateTime.now(), src, level, desc)
    log(ar)
  }
}