package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import Protocol.ProtocolParam
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString

object Horiba370Collector {
  case object ReadData
  case object CheckStatus

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, config: Horiba370Config)(implicit context: ActorContext) = {
    import Protocol.ProtocolParam
    val actorName = s"Horiba_${count}"
    count += 1
    val collector = context.actorOf(Props(classOf[Horiba370Collector], id, protocolParam.host.get, config), name = actorName)
    Logger.info(s"$actorName is created.")

    collector
  }

  def getNextLoggingStatusTime = {
    import com.github.nscala_time.time.Imports._
    def getNextTime(period: Int) = {
      val now = DateTime.now()
      val nextMin = (now.getMinuteOfHour / period + 1) * period
      val hour = (now.getHourOfDay + (nextMin / 60)) % 24
      val nextDay = (now.getHourOfDay + (nextMin / 60)) / 24

      now.withHourOfDay(hour).withMinuteOfHour(nextMin % 60).withSecondOfMinute(0).withMillisOfSecond(0) + nextDay.day
    }
    // suppose every 10 min
    val period = 30
    val nextTime = getNextTime(period)
    //Logger.debug(s"$instId next logging time= $nextTime")
    nextTime
  }
}

class Horiba370Collector(id: String, targetAddr: String, config: Horiba370Config) extends Actor {
  import Horiba370Collector._
  import scala.concurrent.duration._
  import scala.concurrent.Future
  import scala.concurrent.blocking
  import ModelHelper._
  import DataCollectManager._
  import TapiTxx._

  var (collectorState, instrumentStatusTypesOpt) = {
    val instrument = Instrument.getInstrument(id)
    val inst = instrument(0)
    (inst.state, inst.statusType)
  }

  var nextLoggingStatusTime = getNextLoggingStatusTime
  var statusMap = Map.empty[String, Double]
  def logStatus() = {
    import com.github.nscala_time.time.Imports._
    //Log Instrument state
    if (DateTime.now() > nextLoggingStatusTime) {
      try {
        val statusList = statusMap map { kv => InstrumentStatus.Status(kv._1, kv._2) }
        val is = InstrumentStatus.InstrumentStatus(DateTime.now(), id, statusList.toList)
        InstrumentStatus.log(is)
      } catch {
        case _: Throwable =>
          Logger.error("Log instrument status failed")
      }
      nextLoggingStatusTime = getNextLoggingStatusTime
    }
  }

  if (instrumentStatusTypesOpt.isEmpty) {
    instrumentStatusTypesOpt = Some(Horiba370.InstrumentStatusTypeList)
    Instrument.updateStatusType(id, Horiba370.InstrumentStatusTypeList)
  } else {
    val instrumentStatusTypes = instrumentStatusTypesOpt.get
    if (instrumentStatusTypes.length != Horiba370.InstrumentStatusTypeList.length) {
      Instrument.updateStatusType(id, Horiba370.InstrumentStatusTypeList)
    }
  }

  val timerOpt: Option[Cancellable] = Some(Akka.system.scheduler.schedule(Duration(1, SECONDS), Duration(2, SECONDS),
    self, ReadData))

  val statusTimerOpt: Option[Cancellable] = Some(Akka.system.scheduler.schedule(Duration(30, SECONDS), Duration(1, MINUTES),
    self, CheckStatus))

  // override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable) = {}

  val mtCH4 = MonitorType.withName("CH4")
  val mtNMHC = MonitorType.withName("NMHC")
  val mtTHC = MonitorType.withName("THC")

  def processResponse(data: ByteString)(implicit calibrateRecordStart: Boolean) = {
    def getResponse = {
      assert(data(0) == 0x1)
      assert(data(data.length - 1) == 0x3)

      val cmd = data.slice(7, 11).decodeString("US-ASCII")
      val prmStr = data.slice(12, data.length - 3).decodeString("US-ASCII")
      (cmd, prmStr)
    }

    val (cmd, prmStr) = getResponse
    cmd match {
      case "R001" =>
        val result = prmStr.split(",")
        assert(result.length == 8)

        val ch4Value = result(2).substring(5).toDouble
        val nmhcValue = result(3).substring(5).toDouble
        val thcValue = result(4).substring(5).toDouble
        val ch4 = MonitorTypeData(mtCH4, ch4Value, collectorState)
        val nmhc = MonitorTypeData(mtNMHC, nmhcValue, collectorState)
        val thc = MonitorTypeData(mtTHC, thcValue, collectorState)

        if (calibrateRecordStart)
          self ! ReportData(List(ch4, nmhc, thc))

        context.parent ! ReportData(List(ch4, nmhc, thc))

      case "A024" =>
        Logger.info("Response from line change (A024)")
        Logger.info(prmStr)

      case "A029" =>
        Logger.info("Response from user zero (A029)")
        Logger.info(prmStr)

      case "A030" =>
        Logger.info("Response from user span (A030)")
        Logger.info(prmStr)

      case "R010" =>
        val result = prmStr.split(",")
        val value = result(1).toDouble
        statusMap += (Horiba370.FlameStatus -> value)

      case "R037" =>
        val ret = prmStr.split(",")
        if (ret.length == 31 && ret(0) == "00") {
          for (idx <- 0 to 2) {
            val cc = ret(1 + idx * 3)
            val dp = ret(1 + idx * 3 + 1)
            val value = ret(1 + idx * 3 + 2).toDouble
            statusMap += (Horiba370.Press + idx -> value)
          }
        }
        
      case "R038" =>
        Logger.info("R038")
        Logger.info(prmStr)
        val ret = prmStr.split(",")
        Logger.info("#=" + ret.length)
    }
  }

  case object RaiseStart
  case object HoldStart
  case object DownStart
  case object CalibrateEnd

  import context.system

  import akka.io._
  import akka.io.Udp._
  import java.net._

  IO(UdpConnected) ! UdpConnected.Connect(self, new InetSocketAddress(targetAddr, 53700))

  def receive = {
    case UdpConnected.Connected =>
      Logger.info("UDP connected...")
      context become connectionReady(sender())(false)
  }

  def reqData(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'R', '0', '0', '1', 0x2)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqFlameStatus(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'R', '0', '1', '0', 0x2)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqPressure(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'R', '0', '3', '7', 0x2)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqFlow(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'R', '0', '3', '8', 0x2)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqZeroCalibration(connection: ActorRef) = {
    reqZero(connection)

    //    val componentNo = if (mt == mtCH4)
    //      '0'.toByte
    //    else if (mt == mtTHC)
    //      '2'.toByte
    //    else {
    //      throw new Exception(s"Invalid monitorType ${mt.toString}")
    //    }
    //
    //    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
    //      'A', '0', '2', '9', 0x2, componentNo)
    //    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    //    val fcsStr = "%x".format(FCS.toByte)
    //    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    //    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqSpanCalibration(connection: ActorRef) = {
    reqSpan(connection)

    //    val componentNo = if (mt == mtCH4)
    //      '0'.toByte
    //    else if (mt == mtTHC)
    //      '2'.toByte
    //    else {
    //      throw new Exception(s"Invalid monitorType ${mt.toString}")
    //    }
    //
    //    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
    //      'A', '0', '3', '0', 0x2, componentNo)
    //    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    //    val fcsStr = "%x".format(FCS.toByte)
    //    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    //    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqNormal(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'A', '0', '2', '4', 0x2, '0')
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqZero(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'A', '0', '2', '4', 0x2, '1')
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqSpan(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'A', '0', '2', '4', 0x2, '2')
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  object CommCmd extends Enumeration {
    val ValueAcquisition = Value
  }

  var raiseStartTimerOpt: Option[Cancellable] = None

  def setupSpanRaiseStartTimer(connection: ActorRef) {
    raiseStartTimerOpt =
      if (config.calibratorPurgeTime.isDefined && config.calibratorPurgeTime.get != 0) {
        collectorState = MonitorStatus.NormalStat
        reqNormal(connection)
        Instrument.setState(id, collectorState)

        Some(purgeCalibrator)
      } else
        Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, RaiseStart))
  }

  def purgeCalibrator() = {
    import scala.concurrent.duration._
    def triggerCalibratorPurge(v: Boolean) {
      try {
        if (v && config.calibratorPurgeSeq.isDefined)
          context.parent ! ExecuteSeq(config.calibratorPurgeSeq.get, v)
        else
          context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
      } catch {
        case ex: Exception =>
          ModelHelper.logException(ex)
      }
    }

    val purgeTime = config.calibratorPurgeTime.get
    Logger.info(s"Purge calibrator. Delay start of calibration $purgeTime seconds")
    triggerCalibratorPurge(true)
    Akka.system.scheduler.scheduleOnce(Duration(purgeTime + 1, SECONDS), self, RaiseStart)
  }

  def connectionReady(connection: ActorRef)(implicit calibrateRecordStart: Boolean): Receive = {

    case UdpConnected.Received(data) =>
      processResponse(data)

    case UdpConnected.Disconnect =>
      connection ! UdpConnected.Disconnect

    case UdpConnected.Disconnected => context.stop(self)

    case ReadData =>
      reqData(connection)
      logStatus()

    case CheckStatus =>
      reqFlameStatus(connection)
      reqPressure(connection)
      reqFlow(connection)

    case SetState(id, state) =>
      Future {
        blocking {
          collectorState = state
          Instrument.setState(id, state)
          if (state == MonitorStatus.NormalStat) {
            reqNormal(connection)
            raiseStartTimerOpt map {
              timer => timer.cancel()
            }
          }
        }
      }

    case AutoCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      Instrument.setState(id, collectorState)
      context become calibrationHandler(connection, AutoZero,
        com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData],
        Map.empty[MonitorType.Value, Option[Double]])
      self ! RaiseStart

    case ManualZeroCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      Instrument.setState(id, collectorState)
      context become calibrationHandler(connection, ManualZero,
        com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData],
        Map.empty[MonitorType.Value, Option[Double]])
      self ! RaiseStart

    case ManualSpanCalibration(instId) =>
      assert(instId == id)
      context become calibrationHandler(connection, ManualSpan,
        com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData],
        Map.empty[MonitorType.Value, Option[Double]])

      setupSpanRaiseStartTimer(connection)
  }

  var calibrateTimerOpt: Option[Cancellable] = None

  def calibrationHandler(connection: ActorRef, calibrationType: CalibrationType,
                         startTime: com.github.nscala_time.time.Imports.DateTime,
                         recording: Boolean,
                         calibrationDataList: List[MonitorTypeData],
                         zeroMap: Map[MonitorType.Value, Option[Double]]): Receive = {

    case UdpConnected.Received(data) =>
      processResponse(data)(recording)

    case UdpConnected.Disconnect =>
      connection ! UdpConnected.Disconnect

    case UdpConnected.Disconnected => context.stop(self)

    case ReadData =>
      reqData(connection)
      logStatus()

    case RaiseStart =>
      Future {
        blocking {
          if (calibrationType.zero)
            collectorState = MonitorStatus.ZeroCalibrationStat
          else
            collectorState = MonitorStatus.SpanCalibrationStat

          Instrument.setState(id, collectorState)

          Logger.info(s"${calibrationType} RasieStart")
          val cmd =
            if (calibrationType.zero) {
              config.calibrateZeoSeq map {
                seqNo =>
                  context.parent ! ExecuteSeq(seqNo, true)
              }

              reqZeroCalibration(connection)
            } else {
              config.calibrateSpanSeq map {
                seqNo =>
                  context.parent ! ExecuteSeq(seqNo, true)
              }

              reqSpanCalibration(connection)
            }

          calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.raiseTime, SECONDS), self, HoldStart))
        }
      }

    case ReportData(mtDataList) =>
      val data = mtDataList
      context become calibrationHandler(connection, calibrationType, startTime, recording,
        data ::: calibrationDataList, zeroMap)

    case HoldStart =>
      Logger.debug(s"${calibrationType} HoldStart")
      context become calibrationHandler(connection, calibrationType, startTime, true,
        calibrationDataList, zeroMap)
      calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.holdTime, SECONDS), self, DownStart))

    case DownStart =>
      Logger.debug(s"${calibrationType} DownStart")
      context become calibrationHandler(connection, calibrationType, startTime, false,
        calibrationDataList, zeroMap)

      if (calibrationType.zero) {
        config.calibrateZeoSeq map {
          seqNo =>
            context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
        }
      } else {
        config.calibrateSpanSeq map {
          seqNo =>
            context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
        }
      }

      Future {
        blocking {

          calibrateTimerOpt = if (calibrationType.auto && calibrationType.zero)
            Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, CalibrateEnd))
          else {
            collectorState = MonitorStatus.CalibrationResume
            Instrument.setState(id, collectorState)

            Some(Akka.system.scheduler.scheduleOnce(Duration(config.downTime, SECONDS), self, CalibrateEnd))
          }
        }
      }

    case CalibrateEnd =>
      import scala.collection.mutable.Map
      val mtValueMap = Map.empty[MonitorType.Value, List[Double]]
      for {
        record <- calibrationDataList
      } {
        val dataList = mtValueMap.getOrElseUpdate(record.mt, List.empty[Double])
        mtValueMap.put(record.mt, record.value :: dataList)
      }

      val mtAvgMap = mtValueMap map {
        mt_values =>
          val mt = mt_values._1
          val values = mt_values._2
          val avg = if (values.length == 0)
            None
          else
            Some(values.sum / values.length)
          mt -> avg
      }

      if (calibrationType.auto && calibrationType.zero) {
        Logger.info(s"zero calibration end.")
        context become calibrationHandler(connection, AutoSpan, startTime, false, List.empty[MonitorTypeData], mtAvgMap.toMap)

        setupSpanRaiseStartTimer(connection)
      } else {
        Logger.info(s"calibration end.")
        val monitorTypes = mtAvgMap.keySet.toList
        val calibrationList =
          if (calibrationType.auto) {
            for {
              mt <- monitorTypes
              zeroValue = zeroMap(mt)
              avg = mtAvgMap(mt)
            } yield Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, zeroValue, MonitorType.map(mt).span, avg)
          } else {
            for {
              mt <- monitorTypes
              avg = mtAvgMap(mt)
            } yield {
              if (calibrationType.zero) {
                Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, avg, None, None)
              } else {
                Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, None, MonitorType.map(mt).span, avg)
              }
            }
          }
        for (cal <- calibrationList)
          Calibration.insert(cal)

        self ! SetState(id, MonitorStatus.NormalStat)
      }

    case CheckStatus =>
      reqFlameStatus(connection)
      reqPressure(connection)
      reqFlow(connection)
      
    case SetState(id, state) =>
      Future {
        blocking {
          if (state == MonitorStatus.NormalStat) {
            collectorState = state
            Instrument.setState(id, state)

            reqNormal(connection)
            calibrateTimerOpt map {
              timer => timer.cancel()
            }
            context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
            context become connectionReady(connection)(false)
          } else {
            Logger.info(s"Ignore setState $state during calibration")
          }
        }
      }
  }

  override def postStop() = {
    for (timer <- timerOpt) {
      timer.cancel()
    }
    for (timer <- statusTimerOpt) {
      timer.cancel()
    }
  }
}