package models
import play.api._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.github.nscala_time.time.Imports._
import com.github.nscala_time.time._
import models.ModelHelper._
import scala.concurrent.ExecutionContext.Implicits.global

case class Calibration(monitorType: MonitorType.Value, startTime: DateTime, endTime: DateTime, span: Double, z_val: Double,
                           s_std: Double, s_sval: Double) {
  def zd_val = Math.abs(z_val)
  def sd_val = Math.abs(s_sval - s_std)
  def sd_pnt = sd_val / s_std
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
        case _:Seq[_]=>
          val cf = collection.createIndex(ascending("monitorType", "startTime", "endTime")).toFuture()
          cf.onFailure(futureErrorHandler)
      })
    }
  }
  implicit val reads = Json.reads[Calibration]
  implicit val writes = Json.writes[Calibration]


  def toDocument(cal: Calibration) = {
    val json = Json.toJson(cal)
    Document(json.toString())
  }
  
  def toCalibration(doc: Document) = {
    Json.parse(doc.toJson()).validate[Calibration].asOpt.get
  }

  def calibrationReport(start: DateTime, end: DateTime) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(gte("startTime", start.toDate()), lt("endTime", end.toDate()))).sort(ascending("monitorType","startTime")).toFuture()
    val docs = waitReadyResult(f)
    docs.map { toCalibration }
  }

  def calibrationReport(mt:MonitorType.Value, start: DateTime, end: DateTime) = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val f = collection.find(and(equal("monitorType", mt.toString), gte("startTime", start.toDate()), lt("endTime", end.toDate()))).sort(ascending("monitorType","startTime")).toFuture()
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
}