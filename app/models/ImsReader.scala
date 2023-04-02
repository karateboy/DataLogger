package models

import akka.actor._
import com.github.nscala_time.time.Imports._
import com.github.tototoshi.csv.CSVReader
import controllers.Query
import models.ModelHelper.waitReadyResult
import play.api.Play.current
import play.api._
import play.api.libs.concurrent.Akka

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, StandardOpenOption}
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}

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

  private case class ParseInfo(modifiedTime: FileTime, skip: Int)

  private val parsedFileName = "parsedFiles.txt"
  private val parsedInfoMap: mutable.Map[String, ParseInfo] = mutable.Map.empty[String, ParseInfo]

  try {
    for (parsedInfo <- Files.readAllLines(current.getFile(parsedFileName).toPath, StandardCharsets.UTF_8).asScala) {
      val token = parsedInfo.split(":")
      val filePath = token(0)
      val modifiedTime = FileTime.fromMillis(token(1).toLong)
      val skip = token(2).toInt
      parsedInfoMap.update(filePath, ParseInfo(modifiedTime, skip))
    }
  } catch {
    case _: Throwable =>
      Logger.info("Init parsed.lst")
      mutable.Set.empty[String]
  }

  private def updateParsedInfoMap(filePath: String, modifiedTime: Long, skip: Int): Unit = {
    parsedInfoMap.update(filePath, ParseInfo(FileTime.fromMillis(modifiedTime), skip))

    try {
      Files.write(current.getFile(parsedFileName).toPath, s"$filePath:$modifiedTime:$skip\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch {
      case ex: Throwable =>
        Logger.warn(ex.getMessage)
    }
  }

  import java.io.File

  def parser(file: File, skip: Int = 0): Future[Int] = {
    val reader = CSVReader.open(file, "UTF-8")
    val recordLists = reader.allWithHeaders()

    def handleDoc(map: Map[String, String]): Future[Option[DateTime]] = {
      try {
        val date =
          LocalDate.parse(map("Date"), DateTimeFormat.forPattern("YYYY/M/d"))

        val time = LocalTime.parse(map("Time"), DateTimeFormat.forPattern("HH:mm:ss")).withSecondOfMinute(0)
        val dateTime = date.toDateTime(time)

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
        for (_ <- Record.findAndUpdate(dateTime, mtDataList.flatten)(Record.MinCollection)) yield
          Some(dateTime)
      } catch {
        case ex: Throwable =>
          Logger.error(s"fail to parse ${file.getName}", ex)
          Future.successful(None)
      }
    }

    val dateTimeListFuture =
      for (map <- recordLists.drop(skip)) yield
        handleDoc(map)

    reader.close()

    for (dateTimeList <- Future.sequence(dateTimeListFuture)) yield {
      val orderedTimeList = dateTimeList.flatten.sorted
      if (orderedTimeList.nonEmpty) {
        val start = orderedTimeList.head
        val end = orderedTimeList.last
        ForwardManager.forwardMinRecord(start, end.plusMinutes(1))
        val startHour = start.withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
        val endHour = end.withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0).plusHours(1)
        for (hour <- Query.getPeriods(startHour, endHour, 1.hour) if hour <= DateTime.now().minusHours(1))
          DataCollectManager.recalculateHourData(hour)(MonitorType.mtvList)

        orderedTimeList.size
      } else
        0
    }
  }

  private def listFiles(srcPath: String): List[(File, Long)] = {
    Logger.info(s"listDirs $srcPath")
    val allFileAndDirs = Option(new java.io.File(srcPath).listFiles()).getOrElse(Array.empty[File])
      .map(f => (f, Files.getLastModifiedTime(f.toPath).toMillis))
    val files = allFileAndDirs.filter(p => {
      val (file, modifiedTime) = p
      file != null &&
        file.isFile &&
        file.getAbsolutePath.endsWith("csv") &&
        (!parsedInfoMap.contains(file.getAbsolutePath) ||
          parsedInfoMap(file.getAbsolutePath).modifiedTime.toMillis != modifiedTime)
    }).toList

    val dirs = allFileAndDirs.filter(pair => pair._1 != null && pair._1.isDirectory)
    if (dirs.isEmpty) {
      files
    } else {
      val deepDir = dirs flatMap (dir => listFiles(dir._1.getAbsolutePath))
      files ++ deepDir
    }
  }

  private def parseCsv(srcDir: String): Unit = {
    for ((file, modifiedTime) <- listFiles(srcDir)) {
      try {
        Logger.info(s"parse ${file.getAbsolutePath}")
        val parsedNum = waitReadyResult(parser(file))
        if (parsedNum != 0) {
            val parseInfo = parsedInfoMap.getOrElseUpdate(file.getAbsolutePath, ParseInfo(FileTime.fromMillis(0), 0))
            updateParsedInfoMap(file.getAbsolutePath, modifiedTime, parseInfo.skip + parsedNum)
        }
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
      Future {
        blocking {
          parseCsv(dir)
          timer = resetTimer(600)
        }
      }
  }

  override def postStop(): Unit = {
    timer.cancel()
  }
}