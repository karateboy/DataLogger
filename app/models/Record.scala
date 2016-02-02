package models
import play.api._
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models._
import scala.concurrent.ExecutionContext.Implicits.global
case class Record(time: DateTime, value: Double, status: String)

object Record {
  val HourCollection = "hour_data"
  val MinCollection = "min_data"
  def init(colNames:Seq[String]){
    if(!colNames.contains(HourCollection)){
      val f = MongoDB.database.createCollection(HourCollection).toFuture()
      f.onFailure(futureErrorHandler)
    }  
    
    if(!colNames.contains(MinCollection)){
      val f = MongoDB.database.createCollection(MinCollection).toFuture()
      f.onFailure(futureErrorHandler)
    }
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

  def getRecordList(colName: String)(mt: MonitorType.Value, startTime: DateTime, endTime: DateTime) = {
      getRecordMap(colName)(List(mt), startTime, endTime)(mt)
  }
}