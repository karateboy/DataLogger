package controllers

object ReportUnit extends Enumeration {
  val Hour = Value("hour")
  val Day = Value("day")
  val Month = Value("month")
  val Quarter = Value("quarter")
  val Year = Value("year")
  val map = Map((Hour -> "小時"), (Day -> "日"), (Month -> "月"), (Quarter -> "季"), (Year -> "年"))
}