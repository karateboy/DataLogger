package models
import play.api._
import EnumUtils._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import models.ModelHelper._
import com.github.nscala_time.time.Imports._
import scala.concurrent.ExecutionContext.Implicits.global

case class MonitorType(_id: String, desp: String, unit: String, std_law: Option[Double],
                       prec: Int, order: Int, signalType: Boolean = false,
                       std_internal: Option[Double] = None,
                       zd_internal: Option[Double] = None, zd_law: Option[Double] = None,
                       span: Option[Double] = None, span_dev_internal: Option[Double] = None, span_dev_law: Option[Double] = None,
                       var measuringBy: Option[List[String]] = None) {
  def addMeasuring(instrumentId: String, append: Boolean) = {
    val newMeasuringBy =
      if (measuringBy.isEmpty)
        List(instrumentId)
      else {
        val currentMeasuring = measuringBy.get
        if (currentMeasuring.contains(instrumentId))
          currentMeasuring
        else {
          if (append)
            measuringBy.get ++ List(instrumentId)
          else
            instrumentId :: measuringBy.get
        }
      }

    MonitorType(_id, desp, unit, std_law,
      prec, order, signalType, std_internal,
      zd_internal, zd_law,
      span, span_dev_internal, span_dev_law,
      Some(newMeasuringBy))
  }

  def stopMeasuring(instrumentId: String) = {
    val newMeasuringBy =
      if (measuringBy.isEmpty)
        None
      else
        Some(measuringBy.get.filter { id => id != instrumentId })

    MonitorType(_id, desp, unit, std_law,
      prec, order, signalType, std_internal,
      zd_internal, zd_law,
      span, span_dev_internal, span_dev_law,
      newMeasuringBy)
  }
}
//MeasuredBy => History...
//MeasuringBy => Current...

object MonitorType extends Enumeration {
  import org.mongodb.scala.bson._
  import scala.concurrent._
  import scala.concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val mtvRead: Reads[MonitorType.Value] = EnumUtils.enumReads(MonitorType)
  implicit val mtvWrite: Writes[MonitorType.Value] = EnumUtils.enumWrites
  implicit val mtWrite = Json.writes[MonitorType]
  implicit val mtRead = Json.reads[MonitorType]
  implicit object TransformMonitorType extends BsonTransformer[MonitorType.Value] {
    def apply(mt: MonitorType.Value): BsonString = new BsonString(mt.toString)
  }
  val colName = "monitorTypes"
  val collection = MongoDB.database.getCollection(colName)

  val mtUpgrade = Play.current.configuration.getBoolean("monitorType.upgrade").getOrElse(true)
  Logger.info(s"MonitorType upgrade = $mtUpgrade")

  var rangeOrder = 0
  def rangeType(_id: String, desp: String, unit: String, prec: Int) = {
    rangeOrder += 1
    MonitorType(_id, desp, unit, None, prec, rangeOrder)
  }

  var signalOrder = 1000
  def signalType(_id: String, desp: String) = {
    signalOrder += 1
    MonitorType(_id, desp, "N/A", None, 0, signalOrder, true, None,
      None, None,
      None, None, None, None)
  }

  val defaultMonitorTypes = List(
    rangeType("SO2", "二氧化硫", "ppb", 1),
    rangeType("NOx", "氮氧化物", "ppb", 1),
    rangeType("NO2", "二氧化氮", "ppb", 1),
    rangeType("NO", "一氧化氮", "ppb", 1),
    rangeType("CO", "一氧化碳", "ppm", 1),
    rangeType("CO2", "二氧化碳", "ppm", 1),
    rangeType("O3", "臭氧", "ppb", 1),
    rangeType("THC", "總碳氫化合物", "ppm", 1),
    rangeType("TS", "總硫", "ppb", 1),
    rangeType("CH4", "甲烷", "ppm", 1),
    rangeType("NMHC", "非甲烷碳氫化合物", "ppm", 1),
    rangeType("NH3", "氨", "ppb", 1),
    rangeType("TSP", "TSP", "μg/m3", 1),
    rangeType("PM10", "PM10懸浮微粒", "μg/m3", 1),
    rangeType("PM25", "PM2.5細懸浮微粒", "μg/m3", 1),
    rangeType("WD_SPEED", "風速", "m/sec", 1),
    rangeType("WD_DIR", "風向", "degrees", 1),
    rangeType("TEMP", "溫度", "℃", 1),
    rangeType("HUMID", "濕度", "%", 1),
    rangeType("PRESS", "氣壓", "hPa", 1),
    rangeType("RAIN", "雨量", "mm/h", 1),
    rangeType("LAT", "緯度", "度", 4),
    rangeType("LNG", "經度", "度", 4),
    rangeType("HCl", "氯化氫", "ppm", 1),
    rangeType("H2O", "水", "ppm", 1),
    rangeType("RT", "室內溫度", "℃", 1),
    /////////////////////////////////////////////////////
    signalType("DOOR", "門禁"),
    signalType("SMOKE", "煙霧"),
    signalType("FLOW", "採樣流量"))

  lazy val WIN_SPEED = MonitorType.withName("WD_SPEED")
  lazy val WIN_DIRECTION = MonitorType.withName("WD_DIR")
  lazy val RAIN = MonitorType.withName("RAIN")
  lazy val PM25 = MonitorType.withName("PM25")
  lazy val PM10 = MonitorType.withName("PM10")
  lazy val LAT = MonitorType.withName("LAT")
  lazy val LNG = MonitorType.withName("LNG")

  val DOOR = Value("DOOR")
  val SMOKE = Value("SMOKE")
  val FLOW = Value("FLOW")

  var signalMtOldValue = Map.empty[MonitorType.Value, Boolean]

  def logDiMonitorType(mt: MonitorType.Value, v: Boolean) = {
    if (!signalMtvList.contains(mt))
      Logger.warn(s"${mt} is not DI monitor type!")

    if (v) {
      val mtCase = MonitorType.map(mt)
      Alarm.log(Alarm.Src(), Alarm.Level.WARN, s"${mtCase.desp}=>觸發", 1)
    }

  }

  def init(colNames: Seq[String]): Future[Any] = {
    def insertMt = {
      val f = collection.insertMany(defaultMonitorTypes.map { toDocument }).toFuture()
      f.onFailure(errorHandler)
      f.onComplete { x =>
        refreshMtv
      }
      f
    }

    if (!colNames.contains(colName)) { // New
      val f = MongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
      val f2 =
        for (ret <- f) yield insertMt

      f2.flatMap { x => x }
    } else if (mtUpgrade) {
      val upgradeFutureList = defaultMonitorTypes.map { mt => upsertMonitorTypeFuture(mt) }
      val upgradeF = Future.sequence(upgradeFutureList)
      for (upgrade <- upgradeF) yield {
        val instrumentListFuture = Instrument.getAllInstrumentFuture

        for (instList <- instrumentListFuture) {
          for (inst <- instList) {
            val instType = InstrumentType.map(inst.instType)
            val mtList = instType.driver.getMonitorTypes(inst.param)
            for (mt <- mtList) {
              MonitorType.addMeasuring(mt, inst._id, instType.analog)
            }
          }
        }
      }
    } else
      Future {}

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
  }

  private def mtList: List[MonitorType] =
    {
      val f = MongoDB.database.getCollection(colName).find().toFuture()
      val r = waitReadyResult(f)
      r.map { toMonitorType }.toList
    }

  def exist(mt: MonitorType) = {
    try {
      MonitorType.withName(mt._id)
      true
    } catch {
      case _: NoSuchElementException =>
        false
    }
  }

  def refreshMtv: (List[MonitorType.Value], List[MonitorType.Value], Map[MonitorType.Value, MonitorType]) = {
    val list = mtList.sortBy { _.order }
    val mtPair =
      for (mt <- list) yield {
        try {
          val mtv = MonitorType.withName(mt._id)
          (mtv -> mt)
        } catch {
          case _: NoSuchElementException =>
            (Value(mt._id) -> mt)
        }
      }

    val rangeList = list.filter { mt => mt.signalType == false }
    val rangeMtvList = rangeList.map(mt => MonitorType.withName(mt._id))
    val signalList = list.filter { mt => mt.signalType }
    val signalMvList = signalList.map(mt => MonitorType.withName(mt._id))
    mtvList = rangeMtvList
    signalMtvList = signalMvList
    map = mtPair.toMap
    (rangeMtvList, signalMvList, mtPair.toMap)
  }

  var (mtvList, signalMtvList, map) = refreshMtv
  def allMtvList = mtvList ++ signalMtvList
  def diMtvList = List(RAIN) ++ signalMtvList

  def activeMtvList = mtvList.filter { mt => map(mt).measuringBy.isDefined }
  def realtimeMtvList = mtvList.filter { mt =>
    val measuringBy = map(mt).measuringBy
    measuringBy.isDefined && (!measuringBy.get.isEmpty)
  }

  def newMonitorType(mt: MonitorType) = {
    val doc = toDocument(mt)
    import org.mongodb.scala._
    collection.insertOne(doc).subscribe((doOnNext: Completed) => {},
      (ex: Throwable) => {
        Logger.error(ex.getMessage, ex)
        throw ex
      })
    map = map + (Value(mt._id) -> mt)
  }

  def addMeasuring(mt: MonitorType.Value, instrumentId: String, append: Boolean) = {
    val newMt = map(mt).addMeasuring(instrumentId, append)
    map = map + (mt -> newMt)
    upsertMonitorTypeFuture(newMt)
  }

  def stopMeasuring(instrumentId: String) = {
    for {
      mt <- realtimeMtvList
      instrumentList = map(mt).measuringBy.get if instrumentList.contains(instrumentId)
    } {
      val newMt = map(mt).stopMeasuring(instrumentId)
      map = map + (mt -> newMt)
      upsertMonitorTypeFuture(newMt)
    }
  }

  import org.mongodb.scala.model.Filters._
  def upsertMonitorType(mt: MonitorType) = {
    import org.mongodb.scala.model.UpdateOptions
    import org.mongodb.scala.bson.BsonString
    val f = collection.replaceOne(equal("_id", mt._id), toDocument(mt), UpdateOptions().upsert(true)).toFuture()
    waitReadyResult(f)
    true
  }

  def upsertMonitorTypeFuture(mt: MonitorType) = {
    import org.mongodb.scala.model.UpdateOptions
    import org.mongodb.scala.bson.BsonString
    val f = collection.replaceOne(equal("_id", mt._id), toDocument(mt), UpdateOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
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
        if (newValue == "-")
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
      if (overInternal || overLaw)
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

  def displayMeasuringBy(mt: MonitorType.Value) = {
    val mtCase = map(mt)
    if (mtCase.measuringBy.isDefined) {
      val instrumentList = mtCase.measuringBy.get
      if (instrumentList.isEmpty)
        "外部儀器"
      else
        instrumentList.mkString(",")
    } else
      "-"
  }
}