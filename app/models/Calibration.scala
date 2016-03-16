package models
import play.api._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.github.nscala_time.time.Imports._
import com.github.nscala_time.time._
import models.ModelHelper._
import scala.concurrent.ExecutionContext.Implicits.global

case class Calibration(monitorType: MonitorType.Value, startTime: DateTime, endTime: DateTime, zero_val: Double,
                       span_std: Double, span_val: Double) {
  def zero_dev = Math.abs(zero_val)
  def span_dev = Math.abs(span_val - span_std)
  def span_dev_ratio = span_dev / span_std
}

object Calibration {

  val collectionName = "calibration"
  val collection = MongoDB.database.getCollection(collectionName)
  import org.mongodb.scala._
  import org.mongodb.scala.model.Indexes._
  def init(colNames: Seq[String]) {
    if (!colNames.contains(collectionName)) {
      val f = MongoDB.database.createCollection(collectionName).toFuture()
      f.onFailure(futureErrorHandler)
      f.onSuccess({
        case _: Seq[_] =>
          val cf = collection.createIndex(ascending("monitorType", "startTime", "endTime")).toFuture()
          cf.onFailure(futureErrorHandler)
      })
    }
  }
  implicit val reads = Json.reads[Calibration]
  implicit val writes = Json.writes[Calibration]

  def toDocument(cal: Calibration) = {
    import org.mongodb.scala.bson._
    Document("monitorType" -> cal.monitorType, "startTime" -> (cal.startTime: BsonDateTime),
      "endTime" -> (cal.endTime: BsonDateTime), "zero_val" -> cal.zero_val,
      "span_std" -> cal.span_std, "span_val" -> cal.span_val)
  }

  def toCalibration(doc: Document) = {
    val startTime = new DateTime(doc.get("startTime").get.asDateTime().getValue)
    val endTime = new DateTime(doc.get("endTime").get.asDateTime().getValue)
    val monitorType = MonitorType.withName(doc.get("monitorType").get.asString().getValue)
    val zero_val = doc.get("zero_val").get.asDouble().getValue
    val span_std = doc.get("span_std").get.asDouble().getValue
    val span_val = doc.get("span_val").get.asDouble().getValue
    Calibration(monitorType, startTime, endTime, zero_val, span_std, span_val)
  }

  def calibrationReport(start: DateTime, end: DateTime) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(gte("startTime", start.toDate()), lt("endTime", end.toDate()))).sort(ascending("monitorType", "startTime")).toFuture()
    val docs = waitReadyResult(f)
    docs.map { toCalibration }
  }

  def calibrationReport(mt: MonitorType.Value, start: DateTime, end: DateTime) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(equal("monitorType", mt.toString), gte("startTime", start.toDate()), lt("endTime", end.toDate()))).sort(ascending("monitorType", "startTime")).toFuture()
    val docs = waitReadyResult(f)
    docs.map { toCalibration }
  }

  def calibrationMonthly(monitorType: MonitorType.Value, start: DateTime) = {
    val end = start + 1.month
    val report = List.empty[Calibration]
    val pairs =
      for { r <- report } yield {
        r.startTime.toString("d") -> r
      }
    Map(pairs: _*)
  }

  def insert(cal: Calibration) = {
    import ModelHelper._
    val f = collection.insertOne(toDocument(cal)).toFuture()
    f onFailure ({
      case ex: Exception =>
        logException(ex)
    })
  }
}