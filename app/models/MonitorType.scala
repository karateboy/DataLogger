package models
import scala.collection.Map
import play.api.Logger
import EnumUtils._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import models.ModelHelper._
import com.github.nscala_time.time.Imports._
import scala.concurrent.ExecutionContext.Implicits.global

case class MonitorType(_id: String, desp: String, unit: String, std_law: Option[Double],
                       prec: Int, order: Int, std_internal: Option[Double] = None,
                       zd_internal: Option[Double] = None, zd_law: Option[Double] = None,
                       span: Option[Double]=None, span_dev_internal: Option[Double] = None, span_dev_law: Option[Double] = None,
                       measuredBy: Option[String] = None, measuringBy: Option[String] = None)
//MeasuredBy => History...
//MeasuringBy => Current...
                       
object MonitorType extends Enumeration {
  import org.mongodb.scala.bson._
  import scala.concurrent._
  import scala.concurrent.duration._

  implicit val mtvRead: Reads[MonitorType.Value] = EnumUtils.enumReads(MonitorType)
  implicit val mtvWrite: Writes[MonitorType.Value] = EnumUtils.enumWrites
  implicit val mtWrite = Json.writes[MonitorType]
  implicit val mtRead = Json.reads[MonitorType]
  implicit object TransformMonitorType extends BsonTransformer[MonitorType.Value] {
    def apply(mt: MonitorType.Value): BsonString = new BsonString(mt.toString)
  }
  val colName = "monitorTypes"
  val collection = MongoDB.database.getCollection(colName)
  val defaultMonitorTypes = List(
    MonitorType("SO2", "二氧化硫", "ppb", None, 1, 1),
    MonitorType("NOx", "氮氧化物", "ppb", None, 1, 2),
    MonitorType("NO2", "二氧化氮", "ppb", None, 1, 3),
    MonitorType("NO", "一氧化氮", "ppb", None, 1, 4),
    MonitorType("CO", "一氧化碳", "ppm", None, 1, 5),
    MonitorType("CO2", "二氧化碳", "ppm", None, 1, 6),
    MonitorType("O3", "臭氧", "ppb", None, 1, 7),
    MonitorType("THC", "總碳氫化合物", "ppm", None, 1, 8),
    MonitorType("TS", "總硫", "ppb", None, 1, 9),
    MonitorType("CH4", "甲烷", "ppm", None, 1, 10),
    MonitorType("NMHC", "非甲烷碳氫化合物", "ppm", None, 1, 11),
    MonitorType("NH4", "氨", "ppb", None, 1, 12),
    MonitorType("TSP", "TSP", "μg/m3", None, 1, 13),
    MonitorType("PM10", "PM10懸浮微粒", "μg/m3", None, 1, 14),
    MonitorType("PM2_5", "PM2.5細懸浮微粒", "μg/m3", None, 1, 15),
    MonitorType("WD_SPEED", "風速", "m/sec", None, 1, 16),
    MonitorType("WD_DIR", "風向", "degrees", None, 1, 17),
    MonitorType("TEMP", "溫度", "℃", None, 1, 18),
    MonitorType("HUMID", "濕度", "%", None, 1, 19),
    MonitorType("PRESS", "氣壓", "hPa", None, 1, 20),
    MonitorType("RAIN", "雨量", "mm/h", None, 1, 21))

  lazy val WIN_SPEED = MonitorType.withName("WD_SPEED")
  lazy val WIN_DIRECTION = MonitorType.withName("WD_DIR")
  def init(colNames: Seq[String])={
    def insertMt={
      val f = collection.insertMany(defaultMonitorTypes.map { toDocument }).toFuture()
      f.onFailure(futureErrorHandler)
      f.onSuccess({
        case _: Seq[t] =>
          refreshMtv
      })
      f.mapTo[Unit]
    }

    if (!colNames.contains(colName)) {
      val f = MongoDB.database.createCollection(colName).toFuture()
      f.onFailure(futureErrorHandler)
      f.onSuccess({
        case _: Seq[t] =>
          insertMt
      })
      Some(f.mapTo[Unit])
    }else
      None
  }

  def BFName(mt: MonitorType.Value) = {
    val mtCase = map(mt)
    mtCase._id.replace(".", "_")
  }

  def toDocument(mt: MonitorType) = {
    val json = Json.toJson(mt)
    Document(json.toString())
  }

  def toMonitorType(d: Document) = {
    val ret = Json.parse(d.toJson()).validate[MonitorType]

    ret.fold(error => {
      Logger.error(JsError.toJson(error).toString())
      throw new Exception(JsError.toJson(error).toString)
    },
      mt =>
        mt)
    /*
    val std_lawOpt = if (d("std_law").isDouble())
      Some(d("std_law").asDouble().doubleValue())
    else
      None

    MonitorType(
      id = d("_id").asString().getValue,
      desp = d("desp").asString().getValue,
      unit = d("unit").asString().getValue,
      std_law = std_lawOpt,
      prec = d("prec").asInt32().intValue(),
      order = d("order").asInt32().intValue())
      * 
      */
  }

  private def mtList: List[MonitorType] =
    {
      val f = MongoDB.database.getCollection(colName).find().toFuture()
      val r = waitReadyResult(f)
      r.map { toMonitorType }.toList
    }

  def refreshMtv = {
    val list = mtList
    for (mt <- list) {
      try {
        MonitorType.withName(mt._id)
      } catch {
        case _: NoSuchElementException =>
          map = map + (Value(mt._id) -> mt)
      }
    }
    mtvList = list.sortBy { _.order }.map(mt => MonitorType.withName(mt._id))
  }

  var map: Map[Value, MonitorType] = Map(mtList.map { e => Value(e._id) -> e }: _*)
  var mtvList = mtList.sortBy { _.order }.map(mt => MonitorType.withName(mt._id))
  def activeMtvList = mtvList.filter { mt => map(mt).measuredBy.isDefined }
  def realtimeMtvList = mtvList.filter { mt => map(mt).measuringBy.isDefined }

  def newMonitorType(mt: MonitorType) = {
    val doc = toDocument(mt)
    import org.mongodb.scala._
    collection.insertOne(doc).subscribe((doOnNext: Completed) => {},
      (ex: Throwable) => {
        Logger.error(ex.getMessage)
        throw ex
      })
    map = map + (Value(mt._id) -> mt)
  }

  def setMeasuringBy(mt:MonitorType.Value, id:String)={
    updateMonitorType(mt, "measuringBy", id)
    updateMonitorType(mt, "measuredBy", id)
  }
  
  def stopMeasuring(instrumentId:String)={
    for(mt <- realtimeMtvList.filter { mt => map(mt).measuringBy == Some(instrumentId) }){
      Logger.debug("stoping $mt")
      updateMonitorType(mt, "measuringBy", "-")
    }
  }
   
  import org.mongodb.scala.model.Filters._
  def upsertMonitorType(mt:MonitorType) = {
      import org.mongodb.scala.model.UpdateOptions
      import org.mongodb.scala.bson.BsonString
      val f = collection.replaceOne(equal("_id", mt._id), toDocument(mt), UpdateOptions().upsert(true)).toFuture()
      waitReadyResult(f)
      true
  }
  
  def updateMonitorType(mt: MonitorType.Value, colname: String, newValue: String) = {
    import org.mongodb.scala._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._
    import org.mongodb.scala.model.FindOneAndUpdateOptions

    import scala.concurrent.ExecutionContext.Implicits.global
    val idFilter = equal("_id", map(mt)._id)
    val opt = FindOneAndUpdateOptions().returnDocument(com.mongodb.client.model.ReturnDocument.AFTER)
    val f =
      if (colname == "desp" || colname == "unit" || colname == "measuringBy" || colname == "measuredBy") {
        if( newValue == "-")
          collection.findOneAndUpdate(idFilter, set(colname, null), opt).toFuture()
        else
          collection.findOneAndUpdate(idFilter, set(colname, newValue), opt).toFuture()
      } else if (colname == "prec" || colname == "order") {
        import java.lang.Integer
        val v = Integer.parseInt(newValue)
        collection.findOneAndUpdate(idFilter, set(colname, v), opt).toFuture()
      } else {
        if (newValue == "-")
          collection.findOneAndUpdate(idFilter, set(colname, null), opt).toFuture()
        else {
          import java.lang.Double
          collection.findOneAndUpdate(idFilter, set(colname, Double.parseDouble(newValue)), opt).toFuture()
        }
      }

    val ret = waitReadyResult(f)

    val mtCase = toMonitorType(ret(0))
    Logger.debug(mtCase.toString)
    map = map + (mt -> mtCase)
  }

  def format(mt: MonitorType.Value, v: Option[Double]) = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      s"%.${prec}f".format(v.get)
    }
  }

  def overStd(mt: MonitorType.Value, v: Double) = {
    val mtCase = MonitorType.map(mt)
    val overInternal =
      if (mtCase.std_internal.isDefined) {
        if (v > mtCase.std_internal.get)
          true
        else
          false
      } else
        false
    val overLaw =
      if (mtCase.std_law.isDefined) {
        if (v > mtCase.std_law.get)
          true
        else
          false
      } else
        false
    (overInternal, overLaw)
  }

  def getOverStd(mt: MonitorType.Value, r: Option[Record]) = {
    if (r.isEmpty)
      false
    else {
      val (overInternal, overLaw) = overStd(mt, r.get.value)
      overInternal || overLaw
    }
  }

  def formatRecord(mt: MonitorType.Value, r: Option[Record]) = {
    if (r.isEmpty)
      "-"
    else {
      val (overInternal, overLaw) = overStd(mt, r.get.value)
      val prec = map(mt).prec
      val value = s"%.${prec}f".format(r.get.value)
      if(overInternal || overLaw)
        s"<i class='fa fa-exclamation-triangle'></i>$value"
      else
        s"$value"
    }
  }

  def getCssClassStr(mt: MonitorType.Value, r: Option[Record]) = {
    if (r.isEmpty)
      ""
    else {
      val v = r.get.value
      val (overInternal, overLaw) = overStd(mt, v)
      MonitorStatus.getCssClassStr(r.get.status, overInternal, overLaw)
    }
  }

  def displayMeasuredBy(mt: MonitorType.Value) = {
    val mtCase = map(mt)
    if (mtCase.measuringBy.isDefined){
      val id = mtCase.measuringBy.get
      s"$id"
    }else {
      if(mtCase.measuredBy.isDefined){
        val id = mtCase.measuredBy.get
        s"$id (停用)"
      }else
        "未曾使用"
    }
  }
}