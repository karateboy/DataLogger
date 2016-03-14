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
  case class AddInstrument(inst: Instrument)
  case class RemoveInstrument(id: String)
  case class MonitorTypeData(mt: MonitorType.Value, value: Double, status: String)
  case class ReportData(dataList: List[MonitorTypeData])
  object calculateData

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

    Akka.system.scheduler.schedule(Duration(1, MINUTES), Duration(1, MINUTES), manager, calculateData)
  }

  def startCollect(inst: Instrument) {
    manager ! AddInstrument(inst)
  }

  def startCollect(id: String) {
    val inst = Instrument.getInstrument(id)(0)
    manager ! AddInstrument(inst)
  }

  def stopCollect(id: String) {
    manager ! RemoveInstrument(id)
  }
}

class DataCollectManager extends Actor {
  import DataCollectManager._
  var collectorMap = Map.empty[String, ActorRef]
  var latestDataMap = Map.empty[MonitorType.Value, (DateTime, Double, String)]
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
        latestDataMap += data.mt -> (now, data.value, data.status)
        //Logger.info(s"${MonitorType.map(data.mt).desp} (${data.value},${data.status})")  
      }

    case calculateData =>
      val currentMinutes = DateTime.now().withSecondOfMinute(0).withMillisOfSecond(0)
      calculateMinData(currentMinutes)
  }

  def calculateMinData(currentMintues: DateTime) {    
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
    
    val minMtAvgList =
      for {
        mt <- mtMap.keys
        statusMap = mtMap(mt)
        total = statusMap.map { _._2.size }.sum if total != 0
      } yield {
        val normalLb = statusMap(MonitorStatus.normalStatus)
        val minVS =
          if (normalLb.length >= (total * 0.75)) {
            val avg = normalLb.sum / normalLb.length
            (avg, MonitorStatus.normalStatus)
          } else {
            val mostStatus = statusMap.maxBy(kv => kv._2.length)
            val lb = mostStatus._2
            val avg = lb.sum / lb.length
            (avg, mostStatus._1)
          }
        mt -> minVS
      }
      
    Logger.debug(s"update ${currentMintues.minusMinutes(1)} min data")
    Record.insertRecord(Record.toDocument(currentMintues.minusMinutes(1), minMtAvgList.toList))(Record.MinCollection)
    
    mtDataList = currentData
  }

  
  def calculateHourData(currentMintues: LocalTime) {

  }
}