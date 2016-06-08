package models
import play.api._

object HessenProtocol {
  val STX: Byte = 0x02
  val ETX: Byte = 0x03
  val CR: Byte = 0x0D
  val SP: Byte = 0x20
  def buildCommand(cmdTxt: Array[Byte]) = {
    val cmd = new Array[Byte](2 + cmdTxt.length)

    cmd(0) = STX
    for (i <- 0 to cmdTxt.length - 1) {
      cmd(1 + i) = cmdTxt(i)
    }
    cmd(1 + cmdTxt.length) = CR

    cmd
  }

  def dataQuery(): Array[Byte] = {
    buildCommand("DA".getBytes)
  }

  def dataQuery(addr: Array[Byte]) = {
    buildCommand(s"DA${addr.toString()}".getBytes)
  }

  case class Measure(channel: Int, value: Double, status: Byte, error: Byte, serial: String, free: String)
  def decode(reply: String) = {
    val params = reply.split(" ")
    val nMeasure = params(0).substring(2).toInt
    for {
      idx <- 0 to nMeasure-1 if (idx*6+1) < params.length
      measureOffset = idx * 6
    } yield {
      def getValue(str: String): Double = {
        val mantissa = Integer.parseInt(str.substring(0, 5)).toDouble / 1000
        val exponent = Integer.parseInt(str.substring(5))
        mantissa * Math.pow(10, exponent)
      }
      val channel = params(1 + measureOffset).toInt

      val valueStr = params(1 + measureOffset + 1)
      val statusStr = params(1 + measureOffset + 2)

      val status = Integer.parseInt(statusStr, 16).toByte

      val errorStr = params(1 + measureOffset + 3)

      val error = Integer.parseInt(errorStr, 16).toByte
      val serialStr = params(1 + measureOffset + 4)
      val freeStr = params(1 + measureOffset + 5)

      Measure(channel, getValue(valueStr), status, error, serialStr, freeStr)
    }
  }
}