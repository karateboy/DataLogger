package models
import play.api.libs.json._
import models.ModelHelper._


object Protocol extends Enumeration{
  case class ProtocolParam(protocol:Protocol.Value, host:Option[String], comPort:Option[Int])  
  implicit val pReads: Reads[Protocol.Value] = EnumUtils.enumReads(Protocol)
  implicit val pWrites: Writes[Protocol.Value] = EnumUtils.enumWrites
  implicit val ppReader = Json.reads[ProtocolParam]
  implicit val ppWrite = Json.writes[ProtocolParam]

  val tcp = Value
  val serial = Value
  def map = Map(tcp->"TCP", serial->"RS232")
}