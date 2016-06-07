package models
import play.api._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.github.nscala_time.time.Imports._
import com.github.nscala_time.time._
import models.ModelHelper._
import scala.concurrent.ExecutionContext.Implicits.global

case class CalibrationJSON(monitorType: String, startTime: Long, endTime: Long, zero_val: Option[Double],
                           span_std: Option[Double], span_val: Option[Double])

case class Calibration(monitorType: MonitorType.Value, startTime: DateTime, endTime: DateTime, zero_val: Option[Double],
                       span_std: Option[Double], span_val: Option[Double]) {
  def zero_dev = zero_val.map(Math.abs)
  def span_dev =
    for (span <- span_val; std <- span_std)
      yield Math.abs(span_val.get - span_std.get)

  def span_dev_ratio = for (s_dev <- span_dev; std <- span_std)
    yield s_dev / std

  def toJSON = {
    assert(zero_val.isDefined && span_std.isDefined && span_val.isDefined)

    CalibrationJSON(monitorType.toString, startTime.getMillis, endTime.getMillis, zero_val,
      span_std, span_val)
  }
}

object Calibration {

  val collectionName = "calibration"
  val collection = MongoDB.database.getCollection(collectionName)
  import org.mongodb.scala._
  import org.mongodb.scala.model.Indexes._
  def init(colNames: Seq[String]) {
    if (!colNames.contains(collectionName)) {
      val f = MongoDB.database.createCollection(collectionName).toFuture()
      f.onFailure(errorHandler)
      f.onSuccess({
        case _: Seq[_] =>
          val cf = collection.createIndex(ascending("monitorType", "startTime", "endTime")).toFuture()
          cf.onFailure(errorHandler)
      })
    }
  }
  implicit val reads = Json.reads[Calibration]
  implicit val writes = Json.writes[Calibration]
  implicit val jsonWrites = Json.writes[CalibrationJSON]

  def toDocument(cal: Calibration) = {
    import org.mongodb.scala.bson._
    Document("monitorType" -> cal.monitorType, "startTime" -> (cal.startTime: BsonDateTime),
      "endTime" -> (cal.endTime: BsonDateTime), "zero_val" -> cal.zero_val,
      "span_std" -> cal.span_std, "span_val" -> cal.span_val)
  }

  def toCalibration(doc: Document) = {
    import org.mongodb.scala.bson.BsonDouble
    def doublePf: PartialFunction[org.mongodb.scala.bson.BsonValue, Double] = {
      case t: BsonDouble =>
        t.getValue
    }

    val startTime = new DateTime(doc.get("startTime").get.asDateTime().getValue)
    val endTime = new DateTime(doc.get("endTime").get.asDateTime().getValue)
    val monitorType = MonitorType.withName(doc.get("monitorType").get.asString().getValue)
    val zero_val = doc.get("zero_val").collect(doublePf)

    val span_std = doc.get("span_std").collect(doublePf)
    val span_val = doc.get("span_val").collect(doublePf)
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

  def calibrationReportFuture(start: DateTime, end: DateTime) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(gte("startTime", start.toDate()), lt("endTime", end.toDate()))).sort(ascending("monitorType", "startTime")).toFuture()
    for (docs <- f)
      yield docs.map { toCalibration }
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
    f map {_=> ForwardManager.forwardCalibration}
  }

  def getZeroCalibrationStyle(cal: Calibration) = {
    val styleOpt =
      for {
        zero_val <- cal.zero_val
        zd_internal <- MonitorType.map(cal.monitorType).zd_internal
        zd_law <- MonitorType.map(cal.monitorType).zd_law
      } yield if (zero_val > zd_law)
        "red"
      else if (zero_val > zd_internal)
        "blue"
      else
        ""

    styleOpt.getOrElse("")
  }

  def getSpanCalibrationStyle(cal: Calibration) = {
    val styleOpt =
      for {
        span_dev_ratio <- cal.span_dev_ratio
        span_dev_internal <- MonitorType.map(cal.monitorType).span_dev_internal
        span_dev_law <- MonitorType.map(cal.monitorType).span_dev_law
      } yield if (span_dev_ratio > span_dev_law)
        "red"
      else if (span_dev_ratio > span_dev_internal)
        "blue"
      else
        ""

    styleOpt.getOrElse("")
  }

  def getResultStyle(cal: Calibration) = {
    val zeroStyleOpt =
      for {
        zero_val <- cal.zero_val
        zd_internal <- MonitorType.map(cal.monitorType).zd_internal
        zd_law <- MonitorType.map(cal.monitorType).zd_law
      } yield if (zero_val > zd_law)
        "red"
      else if (zero_val > zd_internal)
        "blue"
      else
        ""

    val spanStyleOpt =
      for {
        span_dev_ratio <- cal.span_dev_ratio
        span_dev_internal <- MonitorType.map(cal.monitorType).span_dev_internal
        span_dev_law <- MonitorType.map(cal.monitorType).span_dev_law
      } yield if (span_dev_ratio > span_dev_law)
        "red"
      else if (span_dev_ratio > span_dev_internal)
        "blue"
      else
        ""

    val styleOpt =
      for (zeroStyle <- zeroStyleOpt; spanStyle <- spanStyleOpt)
        yield if (zeroStyle == "red" || spanStyle == "red")
        "red"
      else if (zeroStyle == "blue" || spanStyle == "blue")
        "blue"
      else
        ""

    styleOpt.getOrElse("")
  }

  def getResult(cal: Calibration) = {
    val zeroResultOpt =
      for {
        zero_val <- cal.zero_val
        zd_internal <- MonitorType.map(cal.monitorType).zd_internal
        zd_law <- MonitorType.map(cal.monitorType).zd_law
      } yield if (zero_val > zd_law)
        false
      else
        true

    val spanResultOpt =
      for {
        span_dev_ratio <- cal.span_dev_ratio
        span_dev_internal <- MonitorType.map(cal.monitorType).span_dev_internal
        span_dev_law <- MonitorType.map(cal.monitorType).span_dev_law
      } yield if (span_dev_ratio > span_dev_law)
        false
      else
        true

    val resultOpt =
      if (zeroResultOpt.isDefined) {
        if (spanResultOpt.isDefined)
          Some(zeroResultOpt.get && spanResultOpt.get)
        else
          Some(zeroResultOpt.get)
      } else {
        if (spanResultOpt.isDefined)
          Some(spanResultOpt.get)
        else
          None
      }

    val resultStrOpt = resultOpt map { v => if (v) "成功" else "失敗" }

    resultStrOpt.getOrElse("-")
  }
}