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
import scala.concurrent.ExecutionContext.Implicits.global

object DataCollectManager {
  val effectivRatio = 0.75
  val storeSecondData = Play.current.configuration.getBoolean("storeSecondData").getOrElse(false)
  Logger.info(s"store second data = $storeSecondData")
  case class AddInstrument(inst: Instrument)
  case class RemoveInstrument(id: String)
  case class SetState(instId: String, state: String)
  case class MonitorTypeData(mt: MonitorType.Value, value: Double, status: String)
  case class ReportData(dataList: List[MonitorTypeData])
  case class ExecuteSeq(seq: Int, on: Boolean)
  case object CalculateData
  case class AutoCalibration(instId: String)
  case class ManualZeroCalibration(instId: String)
  case class ManualSpanCalibration(instId: String)

  case class CalibrationType(auto: Boolean, zero: Boolean)
  object AutoZero extends CalibrationType(true, true)
  object AutoSpan extends CalibrationType(true, false)
  object ManualZero extends CalibrationType(false, true)
  object ManualSpan extends CalibrationType(false, false)

  case class WriteDO(bit: Int, on: Boolean)
  case object ResetCounter

  case object EvtOperationOverThreshold

  var manager: ActorRef = _
  def startup() = {
    manager = Akka.system.actorOf(Props[DataCollectManager], name = "dataCollectManager")
    val instrumentList = Instrument.getInstrumentList()
    instrumentList.foreach {
      inst =>
        if (inst.active)
          manager ! AddInstrument(inst)
    }
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

  def setInstrumentState(id: String, state: String) {
    manager ! SetState(id, state)
  }

  def autoCalibration(id: String) {
    manager ! AutoCalibration(id)
  }

  def zeroCalibration(id: String) {
    manager ! ManualZeroCalibration(id)
  }

  def spanCalibration(id: String) {
    manager ! ManualSpanCalibration(id)
  }

  def executeSeq(seq: Int) {
    manager ! ExecuteSeq(seq, true)
  }

  def evtOperationHighThreshold {
    Alarm.log(Alarm.Src(), Level.INFO, "進行高值觸發事件行動..")
    manager ! EvtOperationOverThreshold
  }
  case object GetLatestData
  def getLatestData() = {
    import akka.pattern.ask
    import akka.util.Timeout
    import scala.concurrent.duration._
    implicit val timeout = Timeout(Duration(3, SECONDS))

    val f = manager ? GetLatestData
    f.mapTo[Map[MonitorType.Value, Record]]
  }

  import scala.collection.mutable.ListBuffer
  def calculateAvgMap(mtMap: Map[MonitorType.Value, Map[String, ListBuffer[(DateTime, Double)]]]) = {
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
          val values = statusKV._2.map(_._2)
          val avg = if (mt == MonitorType.WIN_DIRECTION) {
            val windDir = values
            val windSpeedStatusMap = mtMap.get(MonitorType.WIN_SPEED)
            import controllers.Query._
            if (windSpeedStatusMap.isDefined) {
              val windSpeedMostStatus = windSpeedStatusMap.get.maxBy(kv => kv._2.length)
              val windSpeed = windSpeedMostStatus._2.map(_._2)
              windAvg(windSpeed.toList, windDir.toList)
            } else { //assume wind speed is all equal
              val windSpeed =
                for (r <- 1 to windDir.length)
                  yield 1.0
              windAvg(windSpeed.toList, windDir.toList)
            }
          } else if (mt == MonitorType.RAIN || mt == MonitorType.NH3) {
            values.max
          } else if (mt == MonitorType.PM10 || mt == MonitorType.PM25) {
            values.last
          } else {
            values.sum / values.length
          }
          (avg, statusKV._1)
        }
      mt -> minuteAvg
    }
  }

  def calculateHourAvgMap(mtMap: Map[MonitorType.Value, Map[String, ListBuffer[(DateTime, Double)]]]) = {
    for {
      mt <- mtMap.keys
      statusMap = mtMap(mt)
      normalValueOpt = statusMap.get(MonitorStatus.NormalStat) if normalValueOpt.isDefined
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
          val values = normalValueOpt.get.map { _._2 }
          val avg = if (mt == MonitorType.WIN_DIRECTION) {
            val windDir = values
            val windSpeedStatusMap = mtMap.get(MonitorType.WIN_SPEED)
            import controllers.Query._
            if (windSpeedStatusMap.isDefined) {
              val windSpeedMostStatus = windSpeedStatusMap.get.maxBy(kv => kv._2.length)
              val windSpeed = windSpeedMostStatus._2.map(_._2)
              windAvg(windSpeed.toList, windDir.toList)
            } else { //assume wind speed is all equal
              val windSpeed =
                for (r <- 1 to windDir.length)
                  yield 1.0
              windAvg(windSpeed.toList, windDir.toList)
            }
          } else if (mt == MonitorType.RAIN) {
            values.max
          } else if (mt == MonitorType.PM10 || mt == MonitorType.PM25) {
            values.last
          } else {
            values.sum / values.length
          }
          (avg, statusKV._1)
        }
      mt -> minuteAvg
    }
  }

  def checkMinDataAlarm(minMtAvgList: Iterable[(MonitorType.Value, (Double, String))])={
    var overThreshold = false
    for{hourMtData <- minMtAvgList
      mt = hourMtData._1
      data = hourMtData._2
      }{
      val mtCase = MonitorType.map(mt)
      if(mtCase.std_internal.isDefined && MonitorStatus.isValid(data._2)){
        if(data._1 > mtCase.std_internal.get){
          val msg = s"${mtCase.desp}: ${MonitorType.format(mt, Some(data._1))}超過分鐘高值 ${MonitorType.format(mt, mtCase.std_law)}"
          Alarm.log(Alarm.Src(mt), Level.INFO, msg)
          overThreshold = true
        }
      }
    }
    if(overThreshold){
      DataCollectManager.evtOperationHighThreshold
    }
  }
  def recalculateHourData(current: DateTime, forward: Boolean = true)(mtList: List[MonitorType.Value]) = {
    Logger.debug("calculate hour data " + (current - 1.hour))
    val recordMap = Record.getRecordMap(Record.MinCollection)(mtList, current - 1.hour, current)

    import scala.collection.mutable.ListBuffer
    var mtMap = Map.empty[MonitorType.Value, Map[String, ListBuffer[(DateTime, Double)]]]

    for {
      mtRecords <- recordMap
      mt = mtRecords._1
      r <- mtRecords._2
    } {
      var statusMap = mtMap.getOrElse(mt, {
        val map = Map.empty[String, ListBuffer[(DateTime, Double)]]
        mtMap = mtMap ++ Map(mt -> map)
        map
      })

      val lb = statusMap.getOrElse(r.status, {
        val l = ListBuffer.empty[(DateTime, Double)]
        statusMap = statusMap ++ Map(r.status -> l)
        mtMap = mtMap ++ Map(mt -> statusMap)
        l
      })

      lb.append((r.time, r.value))
    }

    val hourMtAvgList = calculateHourAvgMap(mtMap)
    val f = Record.upsertRecord(Record.toDocument(current.minusHours(1), hourMtAvgList.toList))(Record.HourCollection)
    if (forward)
      f map { _ => ForwardManager.forwardHourData }

    f
  }
}

class DataCollectManager extends Actor {
  import DataCollectManager._

  val timer = {
    import scala.concurrent.duration._
    //Try to trigger at 30 sec
    val next30 = DateTime.now().withSecondOfMinute(30).plusMinutes(1)
    val postSeconds = new org.joda.time.Duration(DateTime.now, next30).getStandardSeconds
    Akka.system.scheduler.schedule(Duration(postSeconds, SECONDS), Duration(1, MINUTES), self, CalculateData)
  }

  var calibratorOpt: Option[ActorRef] = None
  var digitalOutputOpt: Option[ActorRef] = None

  case class InstrumentParam(actor: ActorRef, mtList: List[MonitorType.Value], calibrationTimerOpt: Option[Cancellable])

  def receive = handler(Map.empty[String, InstrumentParam], Map.empty[ActorRef, String],
    Map.empty[MonitorType.Value, Map[String, Record]], List.empty[(DateTime, String, List[MonitorTypeData])])

  def handler(instrumentMap: Map[String, InstrumentParam],
              collectorInstrumentMap: Map[ActorRef, String],
              latestDataMap: Map[MonitorType.Value, Map[String, Record]],
              mtDataList: List[(DateTime, String, List[MonitorTypeData])]): Receive = {
    case AddInstrument(inst) =>
      val instType = InstrumentType.map(inst.instType)
      val collector = instType.driver.start(inst._id, inst.protocol, inst.param)
      val monitorTypes = instType.driver.getMonitorTypes(inst.param)
      val calibrateTimeOpt = instType.driver.getCalibrationTime(inst.param)
      val timerOpt = calibrateTimeOpt.map { localtime =>
        val calibrationTime = DateTime.now().toLocalDate().toDateTime(localtime)
        val duration = if (DateTime.now() < calibrationTime)
          new Duration(DateTime.now(), calibrationTime)
        else
          new Duration(DateTime.now(), calibrationTime + 1.day)

        import scala.concurrent.duration._
        Akka.system.scheduler.schedule(Duration(duration.getStandardSeconds + 1, SECONDS),
          Duration(1, DAYS), self, AutoCalibration(inst._id))
      }

      val instrumentParam = InstrumentParam(collector, monitorTypes, timerOpt)
      if (inst.instType == InstrumentType.t700) {
        calibratorOpt = Some(collector)
      } else if (inst.instType == InstrumentType.moxaE1212 || inst.instType == InstrumentType.adam4068) {
        digitalOutputOpt = Some(collector)
      }

      context become handler(instrumentMap + (inst._id -> instrumentParam),
        collectorInstrumentMap + (collector -> inst._id),
        latestDataMap, mtDataList)

    case RemoveInstrument(id: String) =>
      val paramOpt = instrumentMap.get(id)
      if (paramOpt.isDefined) {
        val param = paramOpt.get
        Logger.info(s"Stop collecting instrument $id ")
        Logger.info(s"remove ${param.mtList.toString()}")
        param.calibrationTimerOpt.map { timer => timer.cancel() }
        param.actor ! PoisonPill

        context become handler(instrumentMap - (id), collectorInstrumentMap - param.actor,
          latestDataMap -- param.mtList, mtDataList)

        if (calibratorOpt == Some(param.actor)) {
          calibratorOpt = None
        } else if (digitalOutputOpt == Some(param.actor)) {
          digitalOutputOpt = None
        }
      }

    case ReportData(dataList) =>
      val now = DateTime.now

      val instIdOpt = collectorInstrumentMap.get(sender)
      instIdOpt map {
        instId =>
          val pairs =
            for (data <- dataList) yield {
              val currentMap = latestDataMap.getOrElse(data.mt, Map.empty[String, Record])
              val filteredMap = currentMap.filter { kv =>
                val r = kv._2
                r.time >= DateTime.now() - 6.second
              }

              (data.mt -> (filteredMap ++ Map(instId -> Record(now, data.value, data.status))))
            }

          context become handler(instrumentMap, collectorInstrumentMap,
            latestDataMap ++ pairs, (DateTime.now, instId, dataList) :: mtDataList)
      }

    case CalculateData => {
      import scala.collection.mutable.ListBuffer

      def flushSecData(recordMap: Map[MonitorType.Value, Map[String, ListBuffer[(DateTime, Double)]]]) {
        import scala.collection.mutable.Map

        if (!recordMap.isEmpty) {
          val secRecordMap = Map.empty[DateTime, ListBuffer[(MonitorType.Value, (Double, String))]]
          for {
            mt_pair <- recordMap
            mt = mt_pair._1
            statusMap = mt_pair._2
          } {
            def fillList(head: (DateTime, Double, String), tail: List[(DateTime, Double, String)]): List[(DateTime, Double, String)] = {
              val secondEnd = if (tail.isEmpty)
                60
              else
                tail.head._1.getSecondOfMinute

              val sameDataList =
                for (s <- head._1.getSecondOfMinute to secondEnd - 1) yield {
                  val minPart = head._1.withSecond(0)
                  (minPart + s.second, head._2, head._3)
                }

              if (!tail.isEmpty)
                sameDataList.toList ++ fillList(tail.head, tail.tail)
              else
                sameDataList.toList
            }

            val mtList = statusMap.flatMap { status_pair =>
              val status = status_pair._1
              val recordList = status_pair._2
              val adjustedRecList = recordList map { rec => (rec._1.withMillisOfSecond(0), rec._2) }

              adjustedRecList map { record => (record._1, record._2, status) }
            }

            val mtSortedList = mtList.toList.sortBy(_._1)
            val completeList = if (!mtSortedList.isEmpty) {
              val head = mtSortedList.head
              if (head._1.getSecondOfMinute == 0)
                fillList(head, mtSortedList.tail.toList)
              else
                fillList((head._1.withSecondOfMinute(0), head._2, head._3), mtSortedList)
            } else
              List.empty[(DateTime, Double, String)]

            for (record <- completeList) {
              val mtSecListbuffer = secRecordMap.getOrElseUpdate(record._1, ListBuffer.empty[(MonitorType.Value, (Double, String))])
              mtSecListbuffer.append((mt, (record._2, record._3)))
            }
          }

          val docs = secRecordMap map { r => r._1 -> Record.toDocument(r._1, r._2.toList) }

          val sortedDocs = docs.toSeq.sortBy { x => x._1 } map (_._2)
          if (!sortedDocs.isEmpty)
            Record.insertManyRecord(sortedDocs)(Record.SecCollection)
        }
      }

      def calculateMinData(currentMintues: DateTime) = {
        import scala.collection.mutable.Map
        val mtMap = Map.empty[MonitorType.Value, Map[String, ListBuffer[(String, DateTime, Double)]]]

        val currentData = mtDataList.takeWhile(d => d._1 >= currentMintues)
        val minDataList = mtDataList.drop(currentData.length)

        for {
          dl <- minDataList
          instrumentId = dl._2
          data <- dl._3
        } {
          val statusMap = mtMap.getOrElse(data.mt, {
            val map = Map.empty[String, ListBuffer[(String, DateTime, Double)]]
            mtMap.put(data.mt, map)
            map
          })

          val lb = statusMap.getOrElse(data.status, {
            val l = ListBuffer.empty[(String, DateTime, Double)]
            statusMap.put(data.status, l)
            l
          })

          lb.append((instrumentId, dl._1, data.value))
        }

        val priorityMtPair =
          for {
            mt_statusMap <- mtMap
            mt = mt_statusMap._1
            statusMap = mt_statusMap._2
          } yield {
            val winOutStatusPair =
              for {
                status_lb <- statusMap
                status = status_lb._1
                lb = status_lb._2
                measuringInstrumentList = MonitorType.map(mt).measuringBy.get
              } yield {
                val winOutInstrumentOpt = measuringInstrumentList.find { instrumentId =>
                  lb.exists { id_value =>
                    val id = id_value._1
                    instrumentId == id
                  }
                }
                val winOutLbOpt = winOutInstrumentOpt.map {
                  winOutInstrument =>
                    lb.filter(_._1 == winOutInstrument).map(r => (r._2, r._3))
                }

                status -> winOutLbOpt.getOrElse(ListBuffer.empty[(DateTime, Double)])
              }
            val winOutStatusMap = winOutStatusPair.toMap
            mt -> winOutStatusMap
          }
        val priorityMtMap = priorityMtPair.toMap

        if (storeSecondData)
          flushSecData(priorityMtMap)

        val minuteMtAvgList = calculateAvgMap(priorityMtMap)

        checkMinDataAlarm(minuteMtAvgList)
        
        context become handler(instrumentMap, collectorInstrumentMap, latestDataMap, currentData)
        val f = Record.insertRecord(Record.toDocument(currentMintues.minusMinutes(1), minuteMtAvgList.toList))(Record.MinCollection)
        f map { _ => ForwardManager.forwardMinData }
        f
      }

      val current = DateTime.now().withSecondOfMinute(0).withMillisOfSecond(0)
      val f = calculateMinData(current)
      f onFailure (errorHandler)

      if (current.getMinuteOfHour == 0) {
        import scala.concurrent.ExecutionContext.Implicits.global
        f.map { _ => recalculateHourData(current)(latestDataMap.keys.toList) }
      }
    }

    case SetState(instId, state) =>
      instrumentMap.get(instId).map { param =>
        param.actor ! SetState(instId, state)
      }

    case AutoCalibration(instId) =>
      instrumentMap.get(instId).map { param =>
        param.actor ! AutoCalibration(instId)
      }

    case ManualZeroCalibration(instId) =>
      instrumentMap.get(instId).map { param =>
        param.actor ! ManualZeroCalibration(instId)
      }

    case ManualSpanCalibration(instId) =>
      instrumentMap.get(instId).map { param =>
        param.actor ! ManualSpanCalibration(instId)
      }

    case msg: ExecuteSeq =>
      if (calibratorOpt.isDefined)
        calibratorOpt.get ! msg
      else {
        Logger.warn(s"Calibrator is not online! Ignore execute (${msg.seq} - ${msg.on}).")
      }

    case msg: WriteDO =>
      if (digitalOutputOpt.isDefined)
        digitalOutputOpt.get ! msg
      else {
        Logger.warn(s"DO is not online! Ignore output (${msg.bit} - ${msg.on}).")
      }

    case EvtOperationOverThreshold =>
      if (digitalOutputOpt.isDefined)
        digitalOutputOpt.get ! EvtOperationOverThreshold
      else {
        Logger.warn(s"DO is not online! Ignore EvtOperationOverThreshold.")
      }

    case GetLatestData =>
      //Filter out older than 6 second
      val latestMap = latestDataMap.flatMap { kv =>
        val mt = kv._1
        val instRecordMap = kv._2
        val timeout = if(mt == MonitorType.LAT || mt == MonitorType.LNG)
          1.minute
        else
          6.second
          
        val filteredRecordMap = instRecordMap.filter {
          kv =>
            val r = kv._2
            r.time >= DateTime.now() - timeout
        }

        val measuringList = MonitorType.map(mt).measuringBy.get
        val instrumentIdOpt = measuringList.find { instrumentId => filteredRecordMap.contains(instrumentId) }
        instrumentIdOpt map {
          mt -> filteredRecordMap(_)
        }
      }

      context become handler(instrumentMap, collectorInstrumentMap, latestDataMap, mtDataList)

      sender ! latestMap
  }

  override def postStop(): Unit = {
    timer.cancel()
  }

}