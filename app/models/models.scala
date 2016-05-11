package models
import com.github.nscala_time.time.Imports._
import scala.language.implicitConversions
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.util.parsing.json.JSONFormat
import play.api._

/**
 * @author user
 */

object ModelHelper {
  implicit def getSqlTimestamp(t: DateTime) = {
    new java.sql.Timestamp(t.getMillis)
  }

  implicit def getDateTime(st: java.sql.Timestamp) = {
    new DateTime(st)
  }

  import org.mongodb.scala.bson.BsonDateTime
  implicit def toDateTime(time: BsonDateTime) = new DateTime(time.getValue)
  implicit def toBsonDateTime(jdtime: DateTime) = new BsonDateTime(jdtime.getMillis)
  
  def main(args: Array[String]) {
    val timestamp = DateTime.parse("2015-04-01")
    println(timestamp.toString())
  }

  def logException(ex: Throwable) = {
    Logger.error(ex.getMessage, ex)
  }
  
  import Alarm._
  def logInstrumentError(id:String, msg:String, ex:Throwable) ={    
    Logger.error(msg, ex)
    log(instStr(id), Level.ERR, msg)
  }
  
  def logInstrumentInfo(id:String, msg:String) ={    
    Logger.info(msg)
    log(instStr(id), Level.INFO, msg)
  }  
  
  def futureErrorHandler:PartialFunction[Throwable, Any] = {
      case ex:Throwable=>
        Logger.error(ex.getMessage, ex)
        throw ex
    }
  
  import scala.concurrent._
  
  def waitReadyResult[T](f:Future[T])={
    import scala.concurrent.duration._
    import scala.util._
    
    val ret = Await.ready(f, Duration.Inf).value.get

    ret match {
      case Success(t) =>
        t
      case Failure(ex) =>
        Logger.error(ex.getMessage, ex)
        throw ex
    }
  }
}

object EnumUtils {
  def enumReads[E <: Enumeration](enum: E): Reads[E#Value] = new Reads[E#Value] {
    def reads(json: JsValue): JsResult[E#Value] = json match {
      case JsString(s) => {
        try {
          JsSuccess(enum.withName(s))
        } catch {
          case _: NoSuchElementException => JsError(s"Enumeration expected of type: '${enum.getClass}', but it does not appear to contain the value: '$s'")
        }
      }
      case _ => JsError("String value expected")
    }
  }

  implicit def enumWrites[E <: Enumeration]: Writes[E#Value] = new Writes[E#Value] {
    def writes(v: E#Value): JsValue = JsString(v.toString)
  }

  implicit def enumFormat[E <: Enumeration](enum: E): Format[E#Value] = {
    Format(enumReads(enum), enumWrites)
  }
}
