package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.Play.current

object PeriodReport extends Enumeration {
  val DailyReport = Value("daily")
  val MonthlyReport = Value("monthly")
  val MinMonthlyReport = Value("MinMonthly")
  val YearlyReport = Value("yearly")
  def map = Map(DailyReport -> "日報", MonthlyReport -> "月報", MinMonthlyReport->"分鐘月報", YearlyReport -> "年報")
}

object Report extends Controller {
  
  def monitorReport() = Security.Authenticated { implicit request =>
    Ok(views.html.monitorReport(""))
  }
  
  def getMinMonthlySocket = WebSocket.acceptWithActor[String, String] { request =>
    out =>
      MinMonthlyReportWorker.props(out)
  }
}