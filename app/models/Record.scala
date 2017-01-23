package models
import play.api._
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models._
import scala.concurrent.ExecutionContext.Implicits.global
import org.mongodb.scala._

case class Record(time: DateTime, value: Double, status: String)

object Record {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  implicit val writer = Json.writes[Record]

  val HourCollection = "hour_data"
  val MinCollection = "min_data"
  val SecCollection = "sec_data"
  
  def init(colNames: Seq[String]) {
    if (!colNames.contains(HourCollection)) {
      val f = MongoDB.database.createCollection(HourCollection).toFuture()
      f.onFailure(errorHandler)
    }

    if (!colNames.contains(MinCollection)) {
      val f = MongoDB.database.createCollection(MinCollection).toFuture()
      f.onFailure(errorHandler)
    }
    
    if (!colNames.contains(SecCollection)) {
      val f = MongoDB.database.createCollection(SecCollection).toFuture()
      f.onFailure(errorHandler)
    }

  }

  def toDocument(dt: DateTime, dataList: List[(MonitorType.Value, (Double, String))]) = {
    import org.mongodb.scala.bson._
    val bdt: BsonDateTime = dt
    var doc = Document("_id" -> bdt)
    for {
      data <- dataList
      mt = data._1
      (v, s) = data._2
    } {
      doc = doc ++ Document(MonitorType.BFName(mt) -> Document("v" -> v, "s" -> s))
    }

    doc
  }

  def insertRecord(doc: Document)(colName: String) = {
    val col = MongoDB.database.getCollection(colName)
    val f = col.insertOne(doc).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }
  
  def insertManyRecord(docs: Seq[Document])(colName: String) = {
    val col = MongoDB.database.getCollection(colName)
    val f = col.insertMany(docs).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }
  
  import org.mongodb.scala.model.Filters._
  def upsertRecord(doc: Document)(colName: String) = {    
    import org.mongodb.scala.model.UpdateOptions
    import org.mongodb.scala.bson.BsonString
    val col = MongoDB.database.getCollection(colName)

    val f = col.replaceOne(equal("_id", doc("_id")), doc, UpdateOptions().upsert(true)).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }
  
  def updateRecordStatus(dt: Long, mt: MonitorType.Value, status: String)(colName: String) = {
    import org.mongodb.scala.bson._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._

    val col = MongoDB.database.getCollection(colName)
    val bdt = new BsonDateTime(dt)
    val fieldName = s"${MonitorType.BFName(mt)}.s"

    val f = col.updateOne(equal("_id", bdt), set(fieldName, status)).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def getRecordMap(colName: String)(mtList: List[MonitorType.Value], startTime: DateTime, endTime: DateTime) = {
    import org.mongodb.scala.bson._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._
    import scala.concurrent._
    import scala.concurrent.duration._

    val col = MongoDB.database.getCollection(colName)
    val proj = include(mtList.map { MonitorType.BFName(_) }: _*)
    val f = col.find(and(gte("_id", startTime.toDate()), lt("_id", endTime.toDate()))).projection(proj).sort(ascending("_id")).toFuture()
    val docs = waitReadyResult(f)

    val pairs =
      for {
        mt <- mtList
        mtBFName = MonitorType.BFName(mt)
      } yield {
        val list =
          for {
            doc <- docs
            time = doc("_id").asDateTime()
            mtDocOpt = doc.get(mtBFName) if mtDocOpt.isDefined && mtDocOpt.get.isDocument()
            mtDoc = mtDocOpt.get.asDocument()
            v = mtDoc.get("v") if v.isDouble()
            s = mtDoc.get("s") if s.isString()
          } yield {
            Record(time, v.asDouble().doubleValue(), s.asString().getValue)
          }

        mt -> list
      }
    Map(pairs: _*)
  }

  case class MtRecord(mtName: String, value: Double, status: String)
  case class RecordList(time: Long, mtDataList: Seq[MtRecord])

  implicit val mtRecordWrite = Json.writes[MtRecord]
  implicit val recordListWrite = Json.writes[RecordList]
  
  def getRecordListFuture(colName: String)(startTime: DateTime, endTime: DateTime) = {
    import org.mongodb.scala.bson._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._
    import scala.concurrent._
    import scala.concurrent.duration._

    val mtList = MonitorType.activeMtvList
    val col = MongoDB.database.getCollection(colName)
    val proj = include(mtList.map { MonitorType.BFName(_) }: _*)
    val f = col.find(and(gte("_id", startTime.toDate()), lt("_id", endTime.toDate()))).projection(proj).sort(ascending("_id")).toFuture()

    for {
      docs <- f
    } yield {
      for {
        doc <- docs
        time = doc("_id").asDateTime()
      } yield {

        val mtDataList =
          for {
            mt <- mtList
            mtBFName = MonitorType.BFName(mt)

            mtDocOpt = doc.get(mtBFName) if mtDocOpt.isDefined && mtDocOpt.get.isDocument()
            mtDoc = mtDocOpt.get.asDocument()
            v = mtDoc.get("v") if v.isDouble()
            s = mtDoc.get("s") if s.isString()
          } yield {
            MtRecord(mtBFName, v.asDouble().doubleValue(), s.asString().getValue)
          }
        RecordList(time.getMillis, mtDataList)
      }
    }
  }
  
  def getRecordWithLimitFuture(colName: String)(startTime: DateTime, endTime: DateTime, limit:Int) = {
    import org.mongodb.scala.bson._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._
    import scala.concurrent._
    import scala.concurrent.duration._

    val mtList = MonitorType.activeMtvList
    val col = MongoDB.database.getCollection(colName)
    val proj = include(mtList.map { MonitorType.BFName(_) }: _*)
    val f = col.find(and(gte("_id", startTime.toDate()), lt("_id", endTime.toDate()))).limit(limit).projection(proj).sort(ascending("_id")).toFuture()

    for {
      docs <- f
    } yield {
      for {
        doc <- docs
        time = doc("_id").asDateTime()
      } yield {

        val mtDataList =
          for {
            mt <- mtList
            mtBFName = MonitorType.BFName(mt)

            mtDocOpt = doc.get(mtBFName) if mtDocOpt.isDefined && mtDocOpt.get.isDocument()
            mtDoc = mtDocOpt.get.asDocument()
            v = mtDoc.get("v") if v.isDouble()
            s = mtDoc.get("s") if s.isString()
          } yield {
            MtRecord(mtBFName, v.asDouble().doubleValue(), s.asString().getValue)
          }
        RecordList(time.getMillis, mtDataList)
      }
    }
  }
}