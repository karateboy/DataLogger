package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.Play.current
import com.github.nscala_time.time.Imports._
import models._
import PdfUtility._

object PeriodReport extends Enumeration {
  val DailyReport = Value("daily")
  val MonthlyReport = Value("monthly")
  val YearlyReport = Value("yearly")
  def map = Map(DailyReport -> "日報", MonthlyReport -> "月報",
    YearlyReport -> "年報")

}

object Report extends Controller {

  def monitorReport() = Security.Authenticated { implicit request =>
    Ok(views.html.monitorReport(""))
  }

  def getMinMonthlySocket = WebSocket.acceptWithActor[String, String] { request =>
    out =>
      MinMonthlyReportWorker.props(out)
  }

  def getOverallStatMap(statMap: Map[MonitorType.Value, Map[DateTime, Stat]]) = {
    val mTypes = statMap.keys.toList
    statMap.map { pair =>
      val mt = pair._1
      val dateMap = pair._2
      val values = dateMap.values.toList
      val total = values.size
      val count = values.count(_.avg.isDefined)
      val overCount = values.map { _.overCount }.sum
      val max = values.map { _.avg }.max
      val min = values.map { _.avg }.min
      val avg =
        if (mt != MonitorType.WIN_DIRECTION) {
          if (total == 0 || count == 0)
            None
          else {
            Some(values.filter { _.avg.isDefined }.map { s => s.avg.get * s.total }.sum / (values.map(_.total).sum))
          }
        } else {
          val winSpeedMap = statMap(MonitorType.WIN_SPEED)
          val dates = dateMap.keys.toList
          val windDir = dates.map { dateMap }
          val windSpeed = dates.map { winSpeedMap }
          def windAvg(): Option[Double] = {
            val windRecord = windSpeed.zip(windDir).filter(w => w._1.avg.isDefined && w._2.avg.isDefined)
            if (windRecord.length == 0)
              None
            else {
              val wind_sin = windRecord.map {
                v => v._1.avg.get * Math.sin(Math.toRadians(v._2.avg.get))
              }.sum

              val wind_cos = windRecord.map(v => v._1.avg.get * Math.cos(Math.toRadians(v._2.avg.get))).sum
              Some(Query.windAvg(wind_sin, wind_cos))
            }
          }
          windAvg()
        }

      mt -> Stat(
        avg = avg,
        min = min,
        max = max,
        total = total,
        count = count,
        overCount = overCount)
    }
  }

  def getMonitorReport(reportTypeStr: String, startDateStr: String, outputTypeStr: String) = Security.Authenticated {
    implicit request =>
      val reportType = PeriodReport.withName(reportTypeStr)
      val start = DateTime.parse(startDateStr)
      val outputType = OutputType.withName(outputTypeStr)

      if (outputType == OutputType.html || outputType == OutputType.pdf) {
        val (title, output) =
          reportType match {
            case PeriodReport.DailyReport =>
              val periodMap = Record.getRecordMap(Record.HourCollection)(MonitorType.mtvList, start, start + 1.day)
              val mtTimeMap = periodMap.map { pair =>
                val k = pair._1
                val v = pair._2
                k -> Map(v.map { r => r.time -> r }: _*)
              }
              val statMap = Query.getPeriodStatReportMap(periodMap, 1.day)(start, start + 1.day)

              ("日報", views.html.dailyReport(start, MonitorType.activeMtvList, mtTimeMap, statMap))

            case PeriodReport.MonthlyReport =>
              val periodMap = Record.getRecordMap(Record.HourCollection)(MonitorType.activeMtvList, start, start + 1.month)
              val statMap = Query.getPeriodStatReportMap(periodMap, 1.day)(start, start + 1.month)
              val overallStatMap = getOverallStatMap(statMap)
              ("月報", views.html.monthlyReport(start, MonitorType.activeMtvList, statMap, overallStatMap))

            case PeriodReport.YearlyReport =>
              val periodMap = Record.getRecordMap(Record.HourCollection)(MonitorType.activeMtvList, start, start + 1.year)
              val statMap = Query.getPeriodStatReportMap(periodMap, 1.month)(start, start + 1.year)
              val overallStatMap = getOverallStatMap(statMap)
              ("年報", views.html.yearlyReport(start, MonitorType.activeMtvList, statMap, overallStatMap))

            //case PeriodReport.MonthlyReport =>
            //val nDays = monthlyReport.typeArray(0).dataList.length
            //("月報", "")
          }

        outputType match {
          case OutputType.html =>
            Ok(output)
          case OutputType.pdf =>
            Ok.sendFile(creatPdfWithReportHeader(title, output),
              fileName = _ =>
                play.utils.UriEncoding.encodePathSegment(title + start.toString("YYYYMM") + ".pdf", "UTF-8"))
        }
      } else {
        import java.io.File
        import java.nio.file.Files
        Ok("")
        //                val (title, excelFile) =
        //                  reportType match {
        //                    case PeriodReport.DailyReport =>
        //                      //val dailyReport = Record.getDailyReport(monitor, startTime)
        //                      //("日報" + startTime.toString("YYYYMMdd"), ExcelUtility.createDailyReport(monitor, startTime, dailyReport))
        //        
        //        }      
        //            case PeriodReport.MonthlyReport =>
        //              val adjustStartDate = DateTime.parse(startTime.toString("YYYY-MM-1"))
        //              val monthlyReport = getMonthlyReport(monitor, adjustStartDate)
        //              val nDay = monthlyReport.typeArray(0).dataList.length
        //              ("月報" + startTime.toString("YYYYMM"), ExcelUtility.createMonthlyReport(monitor, adjustStartDate, monthlyReport, nDay))
        //
        //          }
        //
        //                Ok.sendFile(excelFile, fileName = _ =>
        //                  play.utils.UriEncoding.encodePathSegment(title + ".xlsx", "UTF-8"),
        //                  onClose = () => { Files.deleteIfExists(excelFile.toPath()) })
      }
  }

  def monitorMonthlyHourReport = Security.Authenticated {
    Ok(views.html.monitorMonthlyHourReport())
  }

  def monthlyHourReport(monitorTypeStr: String, startDateStr: String, outputTypeStr: String) = Security.Authenticated {
    val mt = MonitorType.withName(monitorTypeStr)
    val start = DateTime.parse(startDateStr)
    val outputType = OutputType.withName(outputTypeStr)
    val title = "月份時報表"
    if (outputType == OutputType.html || outputType == OutputType.pdf) {
      val recordList = Record.getRecordMap(Record.HourCollection)(List(mt), start, start + 1.month)(mt)
      val timePair = recordList.map { r => r.time -> r }
      val timeMap = Map(timePair: _*)

      def getHourPeriodStat(records: Seq[Record], hourList: List[DateTime]) = {
        if (records.length == 0)
          Stat(None, None, None, 0, 0, 0)
        else {
          val values = records.map { r => r.value }
          val min = values.min
          val max = values.max
          val sum = values.sum
          val count = records.length
          val total = new Duration(start, start + 1.month).getStandardDays.toInt
          val overCount = if (MonitorType.map(mt).std_law.isDefined) {
            values.count { _ > MonitorType.map(mt).std_law.get }
          } else
            0

          val avg = if (mt == MonitorType.WIN_DIRECTION) {
            val windDir = records
            val windSpeed = hourList.map(timeMap)
            Query.windAvg(windSpeed, windDir)
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

      val hourValues =
        for {
          h <- 0 to 23
          hourList = Query.getPeriods(start + h.hour, start + 1.month, 1.day)
        } yield {
          h -> getHourPeriodStat(hourList.flatMap { timeMap.get }, hourList)
        }
      val hourStatMap = Map(hourValues: _*)
      val dayStatMap = Query.getPeriodStatReportMap(Map(mt -> recordList), 1.day)(start, start + 1.month)
      val overallStat = Query.getPeriodStatReportMap(Map(mt -> recordList), 1.day)(start, start + 1.month)(mt)(start)
      Ok(views.html.monthlyHourReport(mt, start, timeMap, hourStatMap, dayStatMap(mt), overallStat))
    } else {
      import java.io.File
      import java.nio.file.Files
      Ok("")
    }
  }
}