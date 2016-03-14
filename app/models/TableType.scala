package models

object TableType extends Enumeration{
  val hour = Value
  val min = Value
  val map = Map(hour->"小時資料", min->"分鐘資料")
  val mapCollection = Map(hour->Record.HourCollection, min->Record.MinCollection)
}