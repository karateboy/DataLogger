package models

import java.io.InputStream
import java.io.OutputStream
import jssc.SerialPort
case class SerialComm(port:SerialPort, is:SerialInputStream, os:SerialOutputStream){
  var readBuffer = Array.empty[Byte]
  def getLine = {    
    readBuffer = readBuffer ++ port.readBytes()
    def splitLine(buf: Array[Byte]):List[String]={
      val idx = buf.indexOf('\n'.toByte)
      if(idx == -1){
        readBuffer = buf
        Nil
      }else{
        val (a, rest) = buf.splitAt(idx+1)
        new String(a) :: splitLine(rest) 
      }
    }
    splitLine(readBuffer) 
  }
  
}

object SerialComm{
  def open(n:Int)={
    val port = new SerialPort(s"COM${n}")
    if(!port.openPort())
      throw new Exception(s"Failed to open COM$n")
    
    port.setParams(SerialPort.BAUDRATE_9600, 
                             SerialPort.DATABITS_8,
                             SerialPort.STOPBITS_1,
                             SerialPort.PARITY_NONE);//Set params. Also you can set params by this string: serialPort.setParams(9600, 8, 1, 0);
      
    val is = new SerialInputStream(port)
    val os = new SerialOutputStream(port)
    SerialComm(port, is, os)     
  }
  
  def close(sc:SerialComm){
    sc.is.close
    sc.os.close
    sc.port.closePort()
  }  
}

class SerialOutputStream(port:SerialPort) extends OutputStream{
  override def write(b:Int)={
    port.writeByte(b.toByte)
  }
}

class SerialInputStream(serialPort:jssc.SerialPort) extends InputStream{
  override def read()={
    serialPort.readBytes(1)(0)
  }
}