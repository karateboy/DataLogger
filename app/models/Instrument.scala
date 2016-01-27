package models

case class Instrument(name:String)
object Instrument extends Enumeration {
  val simulator = Value("simulator")
  val baseline = Value("baseline")
  val hartdev = Value("hart")
}