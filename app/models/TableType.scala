package models

object TableType extends Enumeration{
  val second = Value
  val hour = Value
  val min = Value
  val map = Map(second->"秒資料",hour->"小時資料", min->"分鐘資料")
  val mapCollection = Map(second->Record.SecCollection, hour->Record.HourCollection, min->Record.MinCollection)
}