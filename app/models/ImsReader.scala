package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import com.github.tototoshi.csv.CSVReader
import controllers.Query
import play.api.Play.current
import play.api._
import play.api.libs.concurrent.Akka

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}
import java.util.Date
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

object ImsReader {
  private case object ReadFile

  case class ImsConfig(enable: Boolean, path: String)

  var managerOpt: Option[ActorRef] = None
  var count = 0

  def startUp(dir: String): Unit = {
    val props = Props(classOf[ImsReader], dir)

    managerOpt = Some(Akka.system.actorOf(props, name = s"ImsReader$count"))
    count += 1
  }

  def getConfig: Option[ImsConfig] = {
    for {config <- current.configuration.getConfig("Ims")
         enable <- config.getBoolean("enable")
         path <- config.getString("path")
         } yield {
      ImsConfig(enable, path)
    }
  }

  private val parsedFileName = "parsedFiles.txt"
  private val parsedInfoMap: mutable.Map[String, Long] = mutable.Map.empty[String, Long]

  try {
    for (parsedInfo <- Files.readAllLines(current.getFile(parsedFileName).toPath, StandardCharsets.UTF_8).asScala) {
      val token = parsedInfo.split(":")
      val filePath = token(0)
      val modifiedTime = token(1).toLong
      parsedInfoMap.update(filePath, modifiedTime)
    }
  } catch {
    case _: Throwable =>
      Logger.info("Init parsed.lst")
      mutable.Set.empty[String]
  }

  private def updateParsedInfoMap(filePath: String, modifiedTime: Long): Unit = {
    parsedInfoMap.update(filePath, modifiedTime)

    try {
      Files.write(current.getFile(parsedFileName).toPath, s"$filePath:$modifiedTime\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch {
      case ex: Throwable =>
        Logger.warn(ex.getMessage)
    }
  }

  import java.io.File

  def parser(file: File): Boolean = {
    val reader = CSVReader.open(file, "UTF-8")
    val recordLists = reader.allWithHeaders()

    def handleDoc(map: Map[String, String]): Boolean = {
      try {
        val date =
          LocalDate.parse(map("Date"), DateTimeFormat.forPattern("YYYY/M/d"))

        val time = LocalTime.parse(map("Time"), DateTimeFormat.forPattern("HH:mm:ss")).withSecondOfMinute(0)
        val dateTime = date.toDateTime(time)
        if (dateTime < DateTime.now().minusMinutes(1).minusSeconds(40)) {
          val mtNames = List("HCL", "HF", "NH3", "HNO3", "AcOH")
          val mtDataList =
            for (mt <- mtNames) yield {
              try {
                if (mt == "HCL")
                  Some((MonitorType.withName(mt), (map("HCl").split("\\s+")(0).toDouble, MonitorStatus.NormalStat)))
                else
                  Some((MonitorType.withName(mt), (map(mt).split("\\s+")(0).toDouble, MonitorStatus.NormalStat)))
              } catch {
                case ex: Throwable =>
                  None
              }
            }
          Record.findAndUpdate(dateTime, mtDataList.flatten)(Record.MinCollection)
          true
        } else
          false
      } catch {
        case ex: Throwable =>
          Logger.error(s"fail to parse ${file.getName}", ex)
          false
      }
    }

    val completeParsed =
      for (map <- recordLists) yield
        handleDoc(map)

    reader.close()

    val completed = completeParsed.foldLeft(true)((a, b) => a && b)

    val start = try {
      LocalDate.parse(file.getName.take(6), DateTimeFormat.forPattern("YYMMDD")).toDateTimeAtStartOfDay
    } catch {
      case ex: Throwable =>
        Logger.error(s"file to handle ${file.getName}", ex)
        throw ex
    }
    val end = if(completed)
      start + 1.day
    else {
      val lastModified = new DateTime(Files.getLastModifiedTime(file.toPath).toMillis)
      lastModified.withMinute(0).withSecondOfMinute(0).withMillisOfSecond(0)
    }

    for (hour <- Query.getPeriods(start, end, 1.hour))
      DataCollectManager.recalculateHourData(hour)(MonitorType.mtvList)

    completed
  }

  private def listFiles(srcPath: String): List[File] = {
    Logger.info(s"listDirs $srcPath")
    val allFileAndDirs = Option(new java.io.File(srcPath).listFiles()).getOrElse(Array.empty[File]).toList
    val files = allFileAndDirs.filter(p => p != null &&
      p.isFile &&
      p.getAbsolutePath.endsWith("csv") &&
      (!parsedInfoMap.contains(p.getAbsolutePath) ||
        Files.getLastModifiedTime(p.toPath).toMillis != parsedInfoMap(p.getAbsolutePath)))

    val dirs = allFileAndDirs.filter(p => p != null && p.isDirectory)
    if (dirs.isEmpty) {
      files
    } else {
      val deepDir = dirs flatMap (dir => listFiles(dir.getAbsolutePath))
      files ++ deepDir
    }

  }

  private def parseCsv(srcDir: String): Unit = {
    for (file <- listFiles(srcDir)) {
      try {
        Logger.info(s"parse ${file.getAbsolutePath}")
        if(parser(file))
          updateParsedInfoMap(file.getAbsolutePath, Files.getLastModifiedTime(file.toPath).toMillis)
      } catch {
        case ex: Throwable =>
          Logger.error("skip buggy file", ex)
      }
    }
  }
}

class ImsReader(dir: String) extends Actor {

  import ImsReader._

  Logger.info(s"ImsReader start $dir")

  def resetTimer(t: Int): Cancellable = {
    import scala.concurrent.duration._
    Akka.system.scheduler.scheduleOnce(FiniteDuration(t, SECONDS), self, ReadFile)
  }

  @volatile var timer: Cancellable = resetTimer(5)

  def receive: Receive = handler

  def handler: Receive = {
    case ReadFile =>
      Logger.info("Start read files")
      parseCsv(dir)
      timer = resetTimer(120)
  }

  override def postStop(): Unit = {
    timer.cancel()
  }
}