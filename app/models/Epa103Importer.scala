package models
import play.api._
import akka.actor._
import com.github.nscala_time.time.Imports._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import scala.concurrent.ExecutionContext.Implicits.global
import org.apache.poi.openxml4j.opc._
import org.apache.poi.xssf.usermodel._
import org.apache.poi.ss.usermodel._
import org.mongodb.scala._

object Epa103Importer {
  def importData(path: String) = {
    val worker = Akka.system.actorOf(Props[Epa103Importer], name = "epaImporter" + (Math.random() * 1000).toInt)
    worker ! ImportEpa(path)
  }

  import java.io.File
  import java.io.FileFilter
  def listAllFiles(dir: String) = {
    new java.io.File(dir).listFiles.filter(_.getName.endsWith(".xls"))
  }
}

case class ImportEpa(path: String)

class Epa103Importer extends Actor {

  def receive = {
    case ImportEpa(path) =>
      val files = Epa103Importer.listAllFiles(path)
      for (f <- files) {
        importEpaData(f)
        f.delete()
      }

      Logger.info("Finish import 103")
      self ! PoisonPill
  }

  import java.io.File
  import java.io.FileInputStream
  def importEpaData(f: File) {
    Logger.debug(s"Import ${f.getAbsolutePath}")
    import scala.collection.mutable.Map
    val wb = WorkbookFactory.create(new FileInputStream(f));
    val sheet = wb.getSheetAt(0)

    var rowN = 1
    var finish = false
    val docMap = Map.empty[DateTime, Document]
    do {
      var row = sheet.getRow(rowN)
      if (row == null)
        finish = true
      else {
        try{
        val dateStr = row.getCell(0).getStringCellValue
        val site = row.getCell(1).getStringCellValue
                      
        val mt = row.getCell(2).getStringCellValue
        
        val mtId = {
          try{
            Some(MonitorType.withName(mt))
          }catch{
            case _:Throwable=>
              None
          }
        }
        val values =
          for (col <- 3 to 3 + 23) yield {
            try {
              val v = row.getCell(col).getNumericCellValue
              Some(v.toDouble)
            } catch {
              case _: Throwable =>
                {
                  try {
                    val valStr = row.getCell(col).getStringCellValue
                    if (valStr.isEmpty())
                      None
                    else if (valStr.equalsIgnoreCase("NR"))
                      Some(0d)
                    else {
                      try {
                        Some(valStr.toDouble)
                      } catch {
                        case _: Throwable =>
                          None
                      }
                    }
                  }catch{
                      case _: Throwable =>
                          None
                  }
                }
            }
          }

        def appendHr(value: Option[Double], offset: Int) = {
          import org.mongodb.scala.bson._
          val dt = DateTime.parse(dateStr, DateTimeFormat.forPattern("YYYY/MM/dd")) + offset.hour
          val doc = docMap.getOrElseUpdate(dt, {
            val bdt:BsonDateTime = dt
            Document("_id"->bdt)})
            if(mtId.isDefined){
              val newDoc = doc ++ Document(MonitorType.BFName(mtId.get)->Document("v"->value, "s"->"010")) 
              docMap.put(dt, newDoc)              
            }
        }

        for (v <- values.zipWithIndex)
          appendHr(v._1, v._2)
          
        }catch{
          case ex: Throwable=>
            Logger.error(ex.getMessage, ex)
            throw ex
        }
          
        rowN+=1
      }
    } while (!finish)
    //Flush docs
    
    val col = MongoDB.database.getCollection(Record.HourCollection)
    col.insertMany(docMap.values.toSeq).subscribe((doOnNext:Completed)=>{Logger.info("db write complete.")}, 
        (ex:Throwable)=>{Logger.error(ex.getMessage)})
      
    wb.close()
  }
}