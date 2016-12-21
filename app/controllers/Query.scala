package controllers
import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Json
import play.api.Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import scala.concurrent.Future
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
import Highchart._
import models._

case class Stat(
    avg: Option[Double],
    min: Option[Double],
    max: Option[Double],
    count: Int,
    total: Int,
    overCount: Int) {
  val effectPercent = {
    if (total > 0)
      Some(count.toDouble * 100 / total)
    else
      None
  }

  val isEffective = {
    effectPercent.isDefined && effectPercent.get > 75
  }
  val overPercent = {
    if (count > 0)
      Some(overCount.toDouble * 100 / total)
    else
      None
  }
}

object Query extends Controller {
  def historyTrend = Security.Authenticated {
    implicit request =>
      Ok(views.html.historyTrend())
  }

  def windAvg(sum_sin: Double, sum_cos: Double) = {
    val degree = Math.toDegrees(Math.atan2(sum_sin, sum_cos))
    if (degree >= 0)
      degree
    else
      degree + 360
  }

  def windAvg(windSpeed: Seq[Record], windDir: Seq[Record]): Double = {
    if (windSpeed.length != windDir.length)
      Logger.error(s"windSpeed #=${windSpeed.length} windDir #=${windDir.length}")

    val windRecord = windSpeed.zip(windDir)
    val wind_sin = windRecord.map(v => v._1.value * Math.sin(Math.toRadians(v._2.value))).sum
    val wind_cos = windRecord.map(v => v._1.value * Math.cos(Math.toRadians(v._2.value))).sum
    windAvg(wind_sin, wind_cos)
  }

  def windAvg(windSpeed: List[Double], windDir: List[Double]): Double = {
    if (windSpeed.length != windDir.length)
      Logger.error(s"windSpeed #=${windSpeed.length} windDir #=${windDir.length}")

    val windRecord = windSpeed.zip(windDir)
    val wind_sin = windRecord.map(v => v._1 * Math.sin(Math.toRadians(v._2))).sum
    val wind_cos = windRecord.map(v => v._1 * Math.cos(Math.toRadians(v._2))).sum
    windAvg(wind_sin, wind_cos)
  }

  def getPeriods(start: DateTime, endTime: DateTime, d: Period): List[DateTime] = {
    import scala.collection.mutable.ListBuffer

    val buf = ListBuffer[DateTime]()
    var current = start
    while (current < endTime) {
      buf.append(current)
      current += d
    }

    buf.toList
  }

  def getPeriodCount(start: DateTime, endTime: DateTime, p: Period) = {
    var count = 0
    var current = start
    while (current < endTime) {
      count += 1
      current += p
    }

    count
  }

  def getPeriodReportMap(mt: MonitorType.Value, tabType: TableType.Value, period: Period,
                         statusFilter: MonitorStatusFilter.Value = MonitorStatusFilter.ValidData)(start: DateTime, end: DateTime) = {
    val recordList = Record.getRecordMap(TableType.mapCollection(tabType))(List(mt), start, end)(mt)
    def periodSlice(period_start: DateTime, period_end: DateTime) = {
      recordList.dropWhile { _.time < period_start }.takeWhile { _.time < period_end }
    }
    val pairs =
      if (period.getHours == 1) {
        recordList.filter { r => MonitorStatusFilter.isMatched(statusFilter, r.status) }.map { r => r.time -> r.value }
      } else {
        for {
          period_start <- getPeriods(start, end, period)
          records = periodSlice(period_start, period_start + period) if records.length > 0
        } yield {
          if (mt == MonitorType.WIN_DIRECTION) {
            val windDir = records
            val windSpeed = Record.getRecordMap(Record.HourCollection)(List(MonitorType.WIN_SPEED), period_start, period_start + period)(mt)
            period_start -> windAvg(windSpeed, windDir)
          } else {
            val values = records.map { r => r.value }
            period_start -> values.sum / values.length
          }
        }
      }

    Map(pairs: _*)
  }

  def getPeriodStatReportMap(recordListMap: Map[MonitorType.Value, Seq[Record]], period: Period, statusFilter: List[String] = List("010"))(start: DateTime, end: DateTime) = {
    val mTypes = recordListMap.keys.toList
    if (mTypes.contains(MonitorType.WIN_DIRECTION)) {
      if (!mTypes.contains(MonitorType.WIN_SPEED))
        throw new Exception("風速和風向必須同時查詢")
    }

    if (period.getHours == 1) {
      throw new Exception("小時區間無Stat報表")
    }

    def periodSlice(recordList: Seq[Record], period_start: DateTime, period_end: DateTime) = {
      recordList.dropWhile { _.time < period_start }.takeWhile { _.time < period_end }
    }

    def getPeriodStat(records: Seq[Record], mt: MonitorType.Value, period_start: DateTime) = {
      if (records.length == 0)
        Stat(None, None, None, 0, 0, 0)
      else {
        val values = records.map { r => r.value }
        val min = values.min
        val max = values.max
        val sum = values.sum
        val count = records.length
        val total = new Duration(period_start, period_start + period).getStandardHours.toInt
        val overCount = if (MonitorType.map(mt).std_law.isDefined) {
          values.count { _ > MonitorType.map(mt).std_law.get }
        } else
          0

        val avg = if (mt == MonitorType.WIN_DIRECTION) {
          val windDir = records
          val windSpeed = periodSlice(recordListMap(MonitorType.WIN_SPEED), period_start, period_start + period)
          windAvg(windSpeed, windDir)
        } else {
          sum / total
        }
        Stat(
          avg = Some(avg),
          min = Some(min),
          max = Some(max),
          total = total,
          count = count,
          overCount = overCount)
      }
    }
    val pairs = {
      for {
        mt <- mTypes
      } yield {
        val timePairs =
          for {
            period_start <- getPeriods(start, end, period)
            records = periodSlice(recordListMap(mt), period_start, period_start + period)
          } yield {
            period_start -> getPeriodStat(records, mt, period_start)
          }
        mt -> Map(timePairs: _*)
      }
    }

    Map(pairs: _*)
  }

  def trendHelper(monitorTypes: Array[MonitorType.Value], tabType: TableType.Value,
                  reportUnit: ReportUnit.Value, start: DateTime, end: DateTime)(statusFilter: MonitorStatusFilter.Value) = {

    val windMtv = MonitorType.WIN_DIRECTION
    val period: Period =
      reportUnit match {
        case ReportUnit.Min =>
          1.minute
        case ReportUnit.TenMin =>
          10.minute
        case ReportUnit.Hour =>
          1.hour
        case ReportUnit.Day =>
          1.day
        case ReportUnit.Month =>
          1.month
        case ReportUnit.Quarter =>
          3.month
        case ReportUnit.Year =>
          1.year
      }

    val timeList = getPeriods(start, end, period)
    val timeSeq = timeList

    def getSeries() = {

      val mtReportPairs =
        for {
          mt <- monitorTypes
          reportMap = getPeriodReportMap(mt, tabType, period, statusFilter)(start, end)
        } yield {
          mt -> reportMap
        }

      val mtReportMap = Map(mtReportPairs: _*)
      for {
        mt <- monitorTypes
        valueMap = mtReportMap(mt)
        timeData = timeSeq.map { time =>

          if (valueMap.contains(time))
            Seq(Some(time.getMillis.toDouble), Some(valueMap(time).toDouble))
          else
            Seq(Some(time.getMillis.toDouble), None)
        }
      } yield {
        if (monitorTypes.length > 1 && monitorTypes.contains(windMtv)) {
          if (mt != windMtv)
            seqData(MonitorType.map(mt).desp, timeData)
          else
            seqData(MonitorType.map(mt).desp, timeData, 1, Some("scatter"))
        } else {
          seqData(MonitorType.map(mt).desp, timeData)
        }
      }
    }

    val series = getSeries()

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map { MonitorType.map(_).desp }
      startName + mtNames.mkString
    }

    val title =
      reportUnit match {
        case ReportUnit.Min =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.TenMin =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Hour =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Day =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Month =>
          s"趨勢圖 (${start.toString("YYYY年MM月")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Quarter =>
          s"趨勢圖 (${start.toString("YYYY年MM月")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Year =>
          s"趨勢圖 (${start.toString("YYYY年")}~${end.toString("YYYY年")})"
      }

    def getAxisLines(mt: MonitorType.Value) = {
      val mtCase = MonitorType.map(mt)
      val std_law_line =
        if (mtCase.std_law.isEmpty)
          None
        else
          Some(AxisLine("#FF0000", 2, mtCase.std_law.get, Some(AxisLineLabel("right", "法規值"))))

      val lines = Seq(std_law_line, None).filter { _.isDefined }.map { _.get }
      if (lines.length > 0)
        Some(lines)
      else
        None
    }

    val xAxis = {
      val duration = new Duration(start, end)
      if (duration.getStandardDays > 2)
        XAxis(None, gridLineWidth = Some(1), None)
      else
        XAxis(None)
    }

    val windMtCase = MonitorType.map(windMtv)
    val windYaxis = YAxis(None, AxisTitle(Some(Some(s"${windMtCase.desp} (${windMtCase.unit})"))), None,
      opposite = true,
      floor = Some(0),
      ceiling = Some(360),
      min = Some(0),
      max = Some(360),
      tickInterval = Some(45),
      gridLineWidth = Some(1),
      gridLineColor = Some("#00D800"))

    val chart =
      if (monitorTypes.length == 1) {
        val mt = monitorTypes(0)
        val mtCase = MonitorType.map(monitorTypes(0))

        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,
          if (!monitorTypes.contains(windMtv))
            Seq(YAxis(None, AxisTitle(Some(Some(s"${mtCase.desp} (${mtCase.unit})"))), getAxisLines(mt)))
          else
            Seq(windYaxis),
          series,
          Some(downloadFileName))
      } else {
        val yAxis =
          if (monitorTypes.contains(windMtv)) {
            if (monitorTypes.length == 2) {
              val mt = monitorTypes.filter { _ != windMtv }(0)
              val mtCase = MonitorType.map(monitorTypes.filter { MonitorType.WIN_DIRECTION != _ }(0))
              Seq(YAxis(None,
                AxisTitle(Some(Some(s"${mtCase.desp} (${mtCase.unit})"))),
                getAxisLines(mt),
                gridLineWidth = Some(0)),
                windYaxis)
            } else {
              Seq(YAxis(None, AxisTitle(Some(None)), None, gridLineWidth = Some(0)),
                windYaxis)
            }
          } else {
            Seq(YAxis(None, AxisTitle(Some(None)), None))
          }

        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,
          yAxis,
          series,
          Some(downloadFileName))
      }

    chart
  }

  def historyTrendChart(monitorTypeStr: String, reportUnitStr: String, statusFilterStr: String,
                        startStr: String, endStr: String, outputTypeStr: String) = Security.Authenticated {
    implicit request =>
      import scala.collection.JavaConverters._
      val monitorTypeStrArray = monitorTypeStr.split(':')
      val monitorTypes = monitorTypeStrArray.map { MonitorType.withName }
      val reportUnit = ReportUnit.withName(reportUnitStr)
      val statusFilter = MonitorStatusFilter.withName(statusFilterStr)
      val (tabType, start, end) =
        if (reportUnit == ReportUnit.Hour || reportUnit == ReportUnit.Min || reportUnit == ReportUnit.TenMin) {
          val tab = if (reportUnit == ReportUnit.Hour)
            TableType.hour
          else
            TableType.min

          (tab, DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")),
            DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm")))
        } else if (reportUnit == ReportUnit.Day) {
          (TableType.hour, DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd")),
            DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd")))
        } else {
          (TableType.hour, DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-M")),
            DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-M")))
        }

      val outputType = OutputType.withName(outputTypeStr)

      val chart = trendHelper(monitorTypes, tabType, reportUnit, start, end)(statusFilter)

      if (outputType == OutputType.excel) {
        import java.nio.file.Files
        val excelFile = ExcelUtility.exportChartData(chart, monitorTypes)
        val downloadFileName =
          if (chart.downloadFileName.isDefined)
            chart.downloadFileName.get
          else
            chart.title("text")

        Ok.sendFile(excelFile, fileName = _ =>
          play.utils.UriEncoding.encodePathSegment(downloadFileName + ".xlsx", "UTF-8"),
          onClose = () => { Files.deleteIfExists(excelFile.toPath()) })
      } else {
        Results.Ok(Json.toJson(chart))
      }
  }

  def history = Security.Authenticated {
    implicit request =>
      Ok(views.html.history())
  }

  def historyReport(monitorTypeStr: String, tabTypeStr: String,
                    startStr: String, endStr: String) = Security.Authenticated {
    implicit request =>
      import scala.collection.JavaConverters._
      val monitorTypeStrArray = monitorTypeStr.split(':')
      val monitorTypes = monitorTypeStrArray.map { MonitorType.withName }
      val tabType = TableType.withName(tabTypeStr)
      val (start, end) =
        if (tabType == TableType.hour) {
          val orignal_start = DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
          val orignal_end = DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))

          (orignal_start.withMinuteOfHour(0), orignal_end.withMinute(0) + 1.hour)
        } else {
          val timeStart = DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
          tabType match {
            case TableType.min =>
              (timeStart, timeStart + 1.hour)
            case TableType.second =>
              (timeStart, timeStart + 5.minute)
          }
        }
      val timeList = tabType match { 
        case TableType.hour =>
          getPeriods(start, end, 1.hour)
        case TableType.min =>
          getPeriods(start, end, 1.minute)
        case TableType.second =>
          getPeriods(start, end, 1.second)
      }

      val recordMap = Record.getRecordMap(TableType.mapCollection(tabType))(monitorTypes.toList, start, end)
      val recordTimeMap = recordMap.map { p =>
        val recordSeq = p._2
        val timePair = recordSeq.map { r => r.time -> r }
        p._1 -> Map(timePair: _*)
      }

      val explain = monitorTypes.map { t =>
        val mtCase = MonitorType.map(t)
        s"${mtCase.desp}(${mtCase.unit})"
      }.mkString(",")
      val output =
        if(tabType == TableType.second)
          views.html.historyReport(monitorTypes, explain, start, end, timeList, recordTimeMap, true)
        else
          views.html.historyReport(monitorTypes, explain, start, end, timeList, recordTimeMap)
      Ok(output)
  }

  def calibration() = Security.Authenticated {
    Ok(views.html.calibration())
  }

  def calibrationReport(startStr: String, endStr: String) = Security.Authenticated {
    val (start, end) =
      (DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd")),
        DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd")) + 1.day)
    val report = Calibration.calibrationReport(start, end)
    Ok(views.html.calibrationReport(report, "校正報表", start, end))
  }

  def alarm() = Security.Authenticated {
    Ok(views.html.alarm())
  }

  def alarmReport(level:Int, startStr: String, endStr: String) = Security.Authenticated {
    val (start, end) =
      (DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd")),
        DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd")) + 1.day)
    val report = Alarm.getAlarms(level, start, end + 1.day)
    Ok(views.html.alarmReport(start, end, report))
  }

  def instrumentStatus() = Security.Authenticated {
    Ok(views.html.instrumentStatus())
  }

  def instrumentStatusReport(id: String, startStr: String, endStr: String) = Security.Authenticated {
    val (start, end) =
      (DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd")),
        DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd")))

    val report = InstrumentStatus.query(id, start, end + 1.day)
    val keyList = if (report.isEmpty)
      List.empty[String]
    else
      report.map { _.statusList }.maxBy { _.length }.map { _.key }

    val reportMap = for {
      record <- report
      time = record.time
    } yield {
      (time, record.statusList.map { s => (s.key -> s.value) }.toMap)
    }

    val statusTypeMap = Instrument.getStatusTypeMap(id)
    Ok(views.html.instrumentStatusReport(id, start, end, reportMap.toList, keyList, statusTypeMap))
  }

  def manualAudit() = Security.Authenticated {
    Ok(views.html.manualAudit(""))
  }

  def recordList(mtStr: String, startLong: Long, endLong: Long) = Security.Authenticated {
    val monitorType = MonitorType.withName(mtStr)

    val (start, end) = (new DateTime(startLong), new DateTime(endLong))

    val recordMap = Record.getRecordMap(Record.HourCollection)(List(monitorType), start, end)
    Ok(Json.toJson(recordMap(monitorType)))
  }

  case class ManualAuditParam(reason: String, updateList: Seq[UpdateRecordParam])
  case class UpdateRecordParam(time: Long, status: String)
  def updateRecord(monitorTypeStr: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val user = request.user
      implicit val read = Json.reads[UpdateRecordParam]
      implicit val maParamRead = Json.reads[ManualAuditParam]
      val result = request.body.validate[ManualAuditParam]

      val monitorType = MonitorType.withName(monitorTypeStr)

      result.fold(err => {
        Logger.error(JsError.toJson(err).toString())
        BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(err).toString()))
      },
        maParam => {
          for (param <- maParam.updateList) {
            Record.updateRecordStatus(param.time, monitorType, param.status)(Record.HourCollection)
            val log = ManualAuditLog(new DateTime(param.time), mt = monitorType, modifiedTime = DateTime.now(),
              operator = user.name, changedStatus = param.status, reason = maParam.reason)
            Logger.debug(log.toString)
            ManualAuditLog.upsertLog(log)
          }
        })
      Ok(Json.obj("ok" -> true))
  }

  def manualAuditHistory = Security.Authenticated {
    Ok(views.html.manualAuditHistory(""))
  }

  import scala.concurrent.ExecutionContext.Implicits.global
  def manualAuditHistoryReport(start: Long, end: Long) = Security.Authenticated.async {
    val startTime = new DateTime(start)
    val endTime = new DateTime(end)

    val logFuture = ManualAuditLog.queryLog(startTime, endTime)
    val resultF =
      for {
        logList <- logFuture
      } yield {
        Ok(Json.toJson(logList))
      }

    resultF
  }

  // FIXME Bypass security check
  def hourRecordList(start: Long, end: Long) = Action.async {
    implicit request =>
      val startTime = new DateTime(start)
      val endTime = new DateTime(end)
      val recordListF = Record.getRecordListFuture(Record.HourCollection)(startTime, endTime)
      for (recordList <- recordListF) yield {
        Ok(Json.toJson(recordList))
      }
  }

  // FIXME Bypass security check
  def minRecordList(start: Long, end: Long) = Action.async {
    implicit request =>
      val startTime = new DateTime(start)
      val endTime = new DateTime(end)
      val recordListF = Record.getRecordListFuture(Record.MinCollection)(startTime, endTime)
      for (recordList <- recordListF) yield {
        Ok(Json.toJson(recordList))
      }
  }
  
  def calibrationRecordList(start: Long, end: Long) = Action.async {
    implicit request =>
      val startTime = new DateTime(start)
      val endTime = new DateTime(end)
      val recordListF = Calibration.calibrationReportFuture(startTime, endTime)
      for (recordList <- recordListF) yield {
        Ok(Json.toJson(recordList))
      }
  }
  
  def alertRecordList(start: Long, end: Long) = Action.async {
    implicit request =>
      val startTime = new DateTime(start)
      val endTime = new DateTime(end)
      val recordListF = Alarm.getAlarmsFuture(startTime, endTime)
      for (recordList <- recordListF) yield {
        Ok(Json.toJson(recordList))
      }
  }
  
  //
  //  def windRose() = Security.Authenticated {
  //    implicit request =>
  //      Ok(views.html.windRose(false))
  //  }
  //
  //  def monitorTypeRose() = Security.Authenticated {
  //    implicit request =>
  //      Ok(views.html.windRose(true))
  //  }
  //
  //  def windRoseReport(monitorStr: String, monitorTypeStr: String, nWay: Int, startStr: String, endStr: String) = Security.Authenticated {
  //    val monitor = EpaMonitor.withName(monitorStr)
  //    val monitorType = MonitorType.withName(monitorTypeStr)
  //    val start = DateTime.parse(startStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
  //    val end = DateTime.parse(endStr, DateTimeFormat.forPattern("YYYY-MM-dd HH:mm"))
  //    val mtCase = MonitorType.map(monitorType)
  //    assert(nWay == 8 || nWay == 16 || nWay == 32)
  //
  //    try {
  //      val level = List(1f, 2f, 5f, 15f)
  //      val windMap = Record.getMtRose(monitor, monitorType, start, end, level, nWay)
  //      val nRecord = windMap.values.map { _.length }.sum
  //
  //      val dirMap =
  //        Map(
  //          (0 -> "北"), (1 -> "北北東"), (2 -> "東北"), (3 -> "東北東"), (4 -> "東"),
  //          (5 -> "東南東"), (6 -> "東南"), (7 -> "南南東"), (8 -> "南"),
  //          (9 -> "南南西"), (10 -> "西南"), (11 -> "西西南"), (12 -> "西"),
  //          (13 -> "西北西"), (14 -> "西北"), (15 -> "北北西"))
  //
  //      val dirStrSeq =
  //        for {
  //          dir <- 0 to nWay - 1
  //          dirKey = if (nWay == 8)
  //            dir * 2
  //          else if (nWay == 32) {
  //            if (dir % 2 == 0) {
  //              dir / 2
  //            } else
  //              dir + 16
  //          } else
  //            dir
  //        } yield dirMap.getOrElse(dirKey, "")
  //
  //      var last = 0f
  //      val speedLevel = level.flatMap { l =>
  //        if (l == level.head) {
  //          last = l
  //          List(s"< ${l} ${mtCase.unit}")
  //        } else if (l == level.last) {
  //          val ret = List(s"${last}~${l} ${mtCase.unit}", s"> ${l} ${mtCase.unit}")
  //          last = l
  //          ret
  //        } else {
  //          val ret = List(s"${last}~${l} ${mtCase.unit}")
  //          last = l
  //          ret
  //        }
  //      }
  //
  //      import Highchart._
  //
  //      val series = for {
  //        level <- 0 to level.length
  //      } yield {
  //        val data =
  //          for (dir <- 0 to nWay - 1)
  //            yield Seq(Some(dir.toDouble), Some(windMap(dir)(level).toDouble))
  //
  //        seqData(speedLevel(level), data)
  //      }
  //
  //      val title =
  //        if (monitorType == "")
  //          "風瑰圖"
  //        else {
  //          mtCase.desp + "玫瑰圖"
  //        }
  //
  //      val chart = HighchartData(
  //        scala.collection.immutable.Map("polar" -> "true", "type" -> "column"),
  //        scala.collection.immutable.Map("text" -> title),
  //        XAxis(Some(dirStrSeq)),
  //        Seq(YAxis(None, AxisTitle(Some(Some(""))), None)),
  //        series)
  //
  //      Results.Ok(Json.toJson(chart))
  //    } catch {
  //      case e: AssertionError =>
  //        Logger.error(e.toString())
  //        BadRequest("無資料")
  //    }
  //  }

}