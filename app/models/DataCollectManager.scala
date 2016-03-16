package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import com.github.nscala_time.time.Imports._
import play.api.Play.current
import Alarm._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._

object DataCollectManager {
  val effectivRatio = 0.75
  case class AddInstrument(inst: Instrument)
  case class RemoveInstrument(id: String)
  case class SetState(instId: String, state: String)
  case class MonitorTypeData(mt: MonitorType.Value, value: Double, status: String)
  case class ReportData(dataList: List[MonitorTypeData])
  case object CalculateData

  var manager: ActorRef = _
  def startup() = {
    manager = Akka.system.actorOf(Props[DataCollectManager], name = "dataCollectManager")
    val instrumentList = Instrument.getInstrumentList()
    instrumentList.foreach {
      inst =>
        if (inst.active)
          manager ! AddInstrument(inst)
    }

    import scala.concurrent.duration._
    import scala.concurrent.ExecutionContext.Implicits.global

    Akka.system.scheduler.schedule(Duration(1, MINUTES), Duration(1, MINUTES), manager, CalculateData)
  }

  def startCollect(inst: Instrument) {
    manager ! AddInstrument(inst)
  }

  def startCollect(id: String) {
    val instList = Instrument.getInstrument(id)
    instList.map { inst => manager ! AddInstrument(inst) }
  }

  def stopCollect(id: String) {
    manager ! RemoveInstrument(id)
  }
  
  def setInstrumentState(id: String, state: String){
    manager ! SetState(id, state)
  }
}

class DataCollectManager extends Actor {
  import DataCollectManager._
  var collectorMap = Map.empty[String, ActorRef]
  var latestDataMap = Map.empty[MonitorType.Value, Record]
  var mtDataList = List.empty[(DateTime, List[MonitorTypeData])]

  def receive = {
    case AddInstrument(inst) =>
      val instType = InstrumentType.map(inst.instType)
      val collector = instType.driver.start(inst._id, inst.protocol, inst.param)
      collectorMap += (inst._id -> collector)

    case RemoveInstrument(id: String) =>
      val actorOpt = collectorMap.get(id)
      if (actorOpt.isEmpty) {
        Logger.error(s"unknown instrument ID $id")
      } else {
        val actor = actorOpt.get
        Logger.info(s"Stop collecting instrument $id ")
        actor ! PoisonPill
        collectorMap = collectorMap - (id)
      }
    case ReportData(dataList) =>
      val now = DateTime.now
      mtDataList = (now, dataList) :: mtDataList

      for (data <- dataList) {
        latestDataMap += data.mt -> Record(now, data.value, data.status)
        //Logger.info(s"${MonitorType.map(data.mt).desp} (${data.value},${data.status})")  
      }

    case CalculateData =>
      val current = DateTime.now().withSecondOfMinute(0).withMillisOfSecond(0)
      val f = calculateMinData(current)

      if (current.getMinuteOfHour == 0) {
        import scala.concurrent.ExecutionContext.Implicits.global
        f.map { _ => calculateHourData(current) }
      }
    case SetState(instId, state) =>
      collectorMap.get(instId).map { collector =>
        collector ! SetState(instId, state)
      }
  }

  import scala.collection.mutable.ListBuffer
  def calculateAvgMap(mtMap: scala.collection.mutable.Map[MonitorType.Value, scala.collection.mutable.Map[String, ListBuffer[Double]]]) = {
    for {
      mt <- mtMap.keys
      statusMap = mtMap(mt)
      total = statusMap.map { _._2.size }.sum if total != 0
    } yield {
      val minuteAvg =
        {
          val statusKV = {
            val kv = statusMap.maxBy(kv => kv._2.length)
            if (kv._1 == MonitorStatus.NormalStat &&
              statusMap(kv._1).size < statusMap.size * effectivRatio) {
              //return most status except normal
              val noNormalStatusMap = statusMap - kv._1
              noNormalStatusMap.maxBy(kv => kv._2.length)
            } else
              kv
          }
          val values = statusKV._2
          val avg = if (mt == MonitorType.WIN_DIRECTION) {
            val windDir = values
            val windSpeedStatusMap = mtMap.get(MonitorType.WIN_SPEED)
            import controllers.Query._
            if (windSpeedStatusMap.isDefined) {
              val windSpeedMostStatus = windSpeedStatusMap.get.maxBy(kv => kv._2.length)
              val windSpeed = windSpeedMostStatus._2
              windAvg(windSpeed.toList, windDir.toList)
            } else { //assume wind speed is all equal
              val windSpeed =
                for (r <- 1 to windDir.length)
                  yield 1.0
              windAvg(windSpeed.toList, windDir.toList)
            }
          } else {
            values.sum / values.length
          }
          (avg, statusKV._1)
        }
      mt -> minuteAvg
    }

  }

  def calculateMinData(currentMintues: DateTime) = {
    import scala.collection.mutable.ListBuffer
    import scala.collection.mutable.Map
    val mtMap = Map.empty[MonitorType.Value, Map[String, ListBuffer[Double]]]

    val currentData = mtDataList.takeWhile(d => d._1 >= currentMintues)
    val minDataList = mtDataList.drop(currentData.length)

    for {
      dl <- minDataList
      data <- dl._2
    } {
      val statusMap = mtMap.getOrElse(data.mt, {
        val map = Map.empty[String, ListBuffer[Double]]
        mtMap.put(data.mt, map)
        map
      })

      val lb = statusMap.getOrElse(data.status, {
        val l = ListBuffer.empty[Double]
        statusMap.put(data.status, l)
        l
      })

      lb.append(data.value)
    }

    val minuteMtAvgList = calculateAvgMap(mtMap)

    Logger.debug(s"update ${currentMintues.minusMinutes(1)} min data")
    mtDataList = currentData
    Record.insertRecord(Record.toDocument(currentMintues.minusMinutes(1), minuteMtAvgList.toList))(Record.MinCollection)
  }

  def calculateHourData(current: DateTime) = {
    Logger.debug("calculate hour data " + (current - 1.hour))
    val recordMap = Record.getRecordMap(Record.MinCollection)(latestDataMap.keys.toList, current - 1.hour, current)

    import scala.collection.mutable.ListBuffer
    import scala.collection.mutable.Map
    val mtMap = Map.empty[MonitorType.Value, Map[String, ListBuffer[Double]]]

    for {
      mtRecords <- recordMap
      mt = mtRecords._1
      r <- mtRecords._2
    } {
      val statusMap = mtMap.getOrElse(mt, {
        val map = Map.empty[String, ListBuffer[Double]]
        mtMap.put(mt, map)
        map
      })

      val lb = statusMap.getOrElse(r.status, {
        val l = ListBuffer.empty[Double]
        statusMap.put(r.status, l)
        l
      })

      lb.append(r.value)
    }

    val hourMtAvgList = calculateAvgMap(mtMap)
    Record.insertRecord(Record.toDocument(current.minusHours(1), hourMtAvgList.toList))(Record.HourCollection)
  }
}