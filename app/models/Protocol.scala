package models
import play.api.libs.json._
import models.ModelHelper._

case class Protocol(desp:String)
object Protocol extends Enumeration{
  case class TcpCommParam(ip:String, port:Int)
  case class SerialCommParam(port:Int, speed:Int)
  
  implicit val reader: Reads[Protocol.Value] = EnumUtils.enumReads(Protocol)
  implicit val writer: Writes[Protocol.Value] = EnumUtils.enumWrites

  val tcp = Value
  val serial = Value
  def map = Map(tcp->"TCP", serial->"RS232")
}