package controllers

object ReportUnit extends Enumeration {
  val Min = Value
  val TenMin = Value
  val Hour = Value
  val Day = Value
  val Month = Value
  val Quarter = Value
  val Year = Value
  val map = Map((Min->"分"), (TenMin->"十分"), (Hour -> "小時"), (Day -> "日"), (Month -> "月"), (Quarter -> "季"), (Year -> "年"))
}