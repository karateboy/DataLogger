package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
 
object EventOperation extends Enumeration{
  val OverThreshold = Value
  
  implicit val read: Reads[EventOperation.Value] = EnumUtils.enumReads(EventOperation)
  val map = Map{
    OverThreshold -> "高值觸發"
  }
}