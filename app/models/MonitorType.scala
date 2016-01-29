package models
import scala.collection.Map
import play.api.Logger
import EnumUtils._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import models.ModelHelper._
import com.github.nscala_time.time.Imports._
import scala.concurrent.ExecutionContext.Implicits.global

case class MonitorType(id: String, desp: String, unit: String,
                       std_law: Option[Double],
                       itemId: String,
                       prec: Int, order: Int)

object MonitorType extends Enumeration {
  import org.mongodb.scala.bson._
  import scala.concurrent._
  import scala.concurrent.duration._

  implicit val mtReads: Reads[MonitorType.Value] = EnumUtils.enumReads(MonitorType)
  implicit val mtWrites: Writes[MonitorType.Value] = EnumUtils.enumWrites
  implicit object TransformMonitorType extends BsonTransformer[MonitorType.Value] {
    def apply(mt: MonitorType.Value): BsonString = new BsonString(mt.toString)
  }
  val colName = "MonitorTypes"
  val collection = MongoDB.database.getCollection(colName)
  def BFName(mt: MonitorType.Value) = {
    val mtCase = map(mt)
    mtCase.id.replace(".", "_")
  }

  private def toMonitorType(d: Document) = {
    val std_lawOpt = if (d("std_law").isDouble())
      Some(d("std_law").asDouble().doubleValue())
    else
      None

    MonitorType(
      id = d("_id").asString().getValue,
      desp = d("desp").asString().getValue,
      unit = d("unit").asString().getValue,
      std_law = std_lawOpt,
      itemId = d("itemId").asString().getValue,
      prec = d("prec").asInt32().intValue(),
      order = d("order").asInt32().intValue())
  }

  private def mtList: List[MonitorType] =
    {
      val f = MongoDB.database.getCollection(colName).find().toFuture()
      val r = waitReadyResult(f)
      r.map { toMonitorType }.toList
    }

  var map: Map[Value, MonitorType] = Map(mtList.map { e => Value(e.id) -> e }: _*)
  //var itemIdMap = Map(mtList.map { e => e.itemId -> e }: _*)

  val mtvAllList = mtList.map(mt => MonitorType.withName(mt.id))

  def newMonitorType(mt: MonitorType) = {
    val doc = Document("_id" -> mt.id,
      "desp" -> mt.desp,
      "unit" -> mt.unit,
      "std_law" -> mt.std_law,
      "itemId" -> mt.itemId,
      "prec" -> mt.prec,
      "order" -> mt.order)
    import org.mongodb.scala._
    collection.insertOne(doc).subscribe((doOnNext: Completed) => {},
      (ex: Throwable) => {
        Logger.error(ex.getMessage)
        throw ex
      })
    map = map + (Value(mt.id) -> mt)
    //itemIdMap = itemIdMap + (mt.itemId -> mt)
  }

  val epaMap = {
    map.map(kv => (kv._2.itemId, kv._1))
  }

  def windDirList = {
    List(MonitorType.withName("WD_HR"))
  }

  def updateMonitorType(mt: MonitorType.Value, colname: String, newValue: String) = {
    import org.mongodb.scala._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._
    import scala.concurrent.ExecutionContext.Implicits.global
    val f =
      if (colname == "desp" || colname == "unit") {
        collection.findOneAndUpdate(equal("_id", map(mt).id), set(colname, newValue)).toFuture()
      } else if (colname == "prec" || colname == "order") {
        import java.lang.Integer
        val v = Integer.parseInt(newValue)
        collection.findOneAndUpdate(equal("_id", map(mt).id), set(colname, v)).toFuture()
      } else if (colname == "std_law") {
        if (newValue == "-")
          collection.findOneAndUpdate(equal("_id", map(mt).id), set(colname, null)).toFuture()
        else {
          import java.lang.Double
          collection.findOneAndUpdate(equal("_id", map(mt).id), set(colname, Double.parseDouble(newValue))).toFuture()
        }        
      } else
        throw new Exception(s"Unknown fieldname: $colname")

    val ret = waitReadyResult(f)

    val mtCase = toMonitorType(ret(0))
    map = map + (mt -> mtCase)
    
  }

  def format(mt: MonitorType.Value, v: Option[Float]) = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      s"%.${prec}f".format(v.get)
    }
  }

  def formatR(mt: MonitorType.Value, r: Record) = {
    val prec = map(mt).prec
    s"%.${prec}f".format(r.value)
  }
}