package models
import com.github.nscala_time.time.Imports._

import ModelHelper._
import play.api._
import play.api.libs.json._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions

object InstrumentStatus {
  import org.mongodb.scala._
  val collectionName = "instrumentStatus"
  val collection = MongoDB.database.getCollection(collectionName)

  case class Status(key: String, value: Double)
  case class InstrumentStatusJSON(time:Long, instID: String, statusList: List[Status])
  case class InstrumentStatus(time: DateTime, instID: String, statusList: List[Status]) {
    def excludeNaN = {
      val validList = statusList.filter { s => !(s.value.isNaN() || s.value.isInfinite() || s.value.isNegInfinity) }
      InstrumentStatus(time, instID, validList)
    }
    def toJSON = {
      val validList = statusList.filter { s => !(s.value.isNaN() || s.value.isInfinite() || s.value.isNegInfinity) }
      InstrumentStatusJSON(time.getMillis, instID, validList)
    }
  }

  implicit val stRead = Json.reads[Status]
  implicit val isRead = Json.reads[InstrumentStatus]
  implicit val stWrite = Json.writes[Status]
  implicit val isWrite = Json.writes[InstrumentStatus]
  implicit val jsonWrite = Json.writes[InstrumentStatusJSON]

  def init(colNames: Seq[String]) {
    import org.mongodb.scala.model.Indexes._
    if (!colNames.contains(collectionName)) {
      val f = MongoDB.database.createCollection(collectionName).toFuture()
      f.onFailure(errorHandler)
      f.onSuccess({
        case _: Seq[_] =>
          collection.createIndex(ascending("time", "instID"))
      })
    }
  }

  def toDocument(is: InstrumentStatus) = {
    import org.mongodb.scala.bson._
    val jsonStr = Json.toJson(is).toString()
    Document(jsonStr) ++ Document("time" -> (is.time: BsonDateTime))
  }

  def toInstrumentStatus(doc: Document) = {
    //Workaround time bug
    val time = new DateTime(doc.get("time").get.asDateTime().getValue)
    val instID = doc.get("instID").get.asString().getValue
    val statusList = doc.get("statusList").get.asArray()
    val it = statusList.iterator()
    import scala.collection.mutable.ListBuffer
    val lb = ListBuffer.empty[Status]
    while (it.hasNext()) {
      val statusDoc = it.next().asDocument()
      val key = statusDoc.get("key").asString().getValue
      val value = statusDoc.get("value").asNumber().doubleValue()
      lb.append(Status(key, value))
    }

    InstrumentStatus(time, instID, lb.toList)
  }

  def log(is: InstrumentStatus) = {
    //None blocking...
    val f = collection.insertOne(toDocument(is)).toFuture()
    f map {_=> ForwardManager.forwardInstrumentStatus}
  }

  def query(id: String, start: DateTime, end: DateTime) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._
    import scala.concurrent._
    import scala.concurrent.duration._

    val f = collection.find(and(equal("instID", id), gte("time", start.toDate()), lt("time", end.toDate()))).sort(ascending("time")).toFuture()
    waitReadyResult(f).map { toInstrumentStatus }
  }

  def queryFuture(start: DateTime, end: DateTime) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._
    import scala.concurrent._
    import scala.concurrent.duration._

    val recordFuture = collection.find(and(gte("time", start.toDate()), lt("time", end.toDate()))).sort(ascending("time")).toFuture()
    for (f <- recordFuture)
      yield f.map { toInstrumentStatus }
  }

  def formatValue(v: Double) = {
    if (Math.abs(v) < 10)
      s"%.2f".format(v)
    else if (Math.abs(v) < 100)
      s"%.1f".format(v)
    else
      s"%.0f".format(v)
  }
}