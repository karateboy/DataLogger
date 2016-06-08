package models

import java.io.InputStream
import java.io.OutputStream
import jssc.SerialPort
case class SerialComm(port: SerialPort, is: SerialInputStream, os: SerialOutputStream) {
  var readBuffer = Array.empty[Byte]
  def getLine = {
    def splitLine(buf: Array[Byte]): List[String] = {
      val idx = buf.indexOf('\n'.toByte)
      if (idx == -1) {
        val cr_idx = buf.indexOf('\r'.toByte)
        if (cr_idx == -1) {
          readBuffer = buf
          Nil
        } else {
          val (a, rest) = buf.splitAt(cr_idx + 1)
          new String(a).trim() :: splitLine(rest)
        }
      } else {
        val (a, rest) = buf.splitAt(idx + 1)
        new String(a).trim() :: splitLine(rest)
      }
    }

    val ret = port.readBytes()
    if (ret != null)
      readBuffer = readBuffer ++ ret

    splitLine(readBuffer)
  }

  def getLine2 = {
    def splitLine(buf: Array[Byte]): List[String] = {
      val idx = buf.indexOf('\n'.toByte)
      if (idx == -1) {
        val cr_idx = buf.indexOf('\r'.toByte)
        if (cr_idx == -1) {
          readBuffer = buf
          Nil
        } else {
          val (a, rest) = buf.splitAt(cr_idx + 1)
          new String(a).trim() :: splitLine(rest)
        }
      } else {
        val (a, rest) = buf.splitAt(idx + 1)
        new String(a) :: splitLine(rest)
      }
    }

    val ret = port.readBytes()
    if (ret != null)
      readBuffer = readBuffer ++ ret

    splitLine(readBuffer)
  }
  def getLine2(timeout: Int):List[String] = {
    import com.github.nscala_time.time.Imports._
    var strList = getLine2
    val startTime = DateTime.now
    while (strList.length == 0) {
      val elapsedTime = new Duration(startTime, DateTime.now)
      if (elapsedTime.getStandardSeconds > timeout) {
        throw new Exception("Read timeout!")
      }
      strList = getLine2
    }
    strList
  }
  def close = {
    is.close
    os.close
    port.closePort()
    readBuffer = Array.empty[Byte]
  }
}

object SerialComm {
  def open(n: Int) = {
    val port = new SerialPort(s"COM${n}")
    if (!port.openPort())
      throw new Exception(s"Failed to open COM$n")

    port.setParams(SerialPort.BAUDRATE_9600,
      SerialPort.DATABITS_8,
      SerialPort.STOPBITS_1,
      SerialPort.PARITY_NONE); //Set params. Also you can set params by this string: serialPort.setParams(9600, 8, 1, 0);

    val is = new SerialInputStream(port)
    val os = new SerialOutputStream(port)
    SerialComm(port, is, os)
  }

  def close(sc: SerialComm) {
    sc.close
  }
}

class SerialOutputStream(port: SerialPort) extends OutputStream {
  override def write(b: Int) = {
    port.writeByte(b.toByte)
  }
}

class SerialInputStream(serialPort: jssc.SerialPort) extends InputStream {
  override def read() = {
    serialPort.readBytes(1)(0)
  }
}