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

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, config: Horiba370Config)(implicit context: ActorContext) = {
    import Protocol.ProtocolParam
    val actorName = s"Horiba_${count}"
    count += 1
    val collector = context.actorOf(Props(classOf[Horiba370Collector], id, protocolParam.host.get, config), name = actorName)
    Logger.info(s"$actorName is created.")

    collector
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

  var collectorState = {
    val instrument = Instrument.getInstrument(id)
    instrument(0).state
  }

  var timerOpt: Option[Cancellable] = None

  //  override def preStart() = {
  //    timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData))
  //  }

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

        timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData))

      case "A024" =>
        Logger.info("Response from line change (A024)")
        Logger.info(prmStr)

      case "A029" =>
        Logger.info("Response from user zero (A029)")
        Logger.info(prmStr)

      case "A030" =>
        Logger.info("Response from user span (A030)")
        Logger.info(prmStr)

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
      timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData))
  }

  def reqData(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'R', '0', '0', '1', 0x2)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqZeroCalibration(connection: ActorRef, mt: MonitorType.Value) = {
    val componentNo = if (mt == mtCH4)
      '0'.toByte
    else if (mt == mtTHC)
      '2'.toByte
    else {
      throw new Exception(s"Invalid monitorType ${mt.toString}")
    }

    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'A', '0', '2', '9', 0x2, componentNo)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqSpanCalibration(connection: ActorRef, mt: MonitorType.Value) = {
    val componentNo = if (mt == mtCH4)
      '0'.toByte
    else if (mt == mtTHC)
      '2'.toByte
    else {
      throw new Exception(s"Invalid monitorType ${mt.toString}")
    }

    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'A', '0', '3', '0', 0x2, componentNo)
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  def reqNormal(connection: ActorRef) = {
    val reqCmd = Array[Byte](0x1, '0', '2', '0', '2', '0', '0',
      'A', '0', '2', '4', 0x2, '0')
    val FCS = reqCmd.foldLeft(0x0)((a, b) => a ^ b.toByte)
    val fcsStr = "%x".format(FCS.toByte)
    val reqFrame = reqCmd ++ (fcsStr.getBytes("UTF-8")).:+(0x3.toByte)
    connection ! UdpConnected.Send(ByteString(reqFrame))
  }

  object CommCmd extends Enumeration {
    val ValueAcquisition = Value
  }

  def setupSpanRaiseStartTimer {
    Logger.debug("setupSpanRaiseStartTimer")
    val timer =
      if (config.calibratorPurgeTime.isDefined && config.calibratorPurgeTime.get != 0)
        purgeCalibrator
      else
        Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, RaiseStart)
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
      timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData))

    case UdpConnected.Disconnect =>
      connection ! UdpConnected.Disconnect

    case UdpConnected.Disconnected => context.stop(self)

    case ReadData =>
      reqData(connection)
      context become connectionReady(connection)
    case SetState(id, state) =>
      Future {
        blocking {
          collectorState = state
          Instrument.setState(id, state)
          if (state == MonitorStatus.NormalStat) {
          }
        }
      }

    case AutoCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      Instrument.setState(id, collectorState)
      context become calibrationHandler(connection, AutoZero, mtCH4,
        com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData], None)
      self ! RaiseStart

    case ManualZeroCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      Instrument.setState(id, collectorState)
      context become calibrationHandler(connection, ManualZero, mtCH4,
        com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData], None)
      self ! RaiseStart

    case ManualSpanCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.SpanCalibrationStat
      Instrument.setState(id, collectorState)
      context become calibrationHandler(connection, ManualSpan, mtCH4,
        com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData], None)

      setupSpanRaiseStartTimer
  }

  var calibrateTimerOpt: Option[Cancellable] = None

  def calibrationHandler(connection: ActorRef, calibrationType: CalibrationType, mt: MonitorType.Value,
                         startTime: com.github.nscala_time.time.Imports.DateTime,
                         recording: Boolean,
                         calibrationDataList: List[MonitorTypeData],
                         zeroValue: Option[Double]): Receive = {

    case UdpConnected.Received(data) =>
      processResponse(data)(recording)

    case UdpConnected.Disconnect =>
      connection ! UdpConnected.Disconnect

    case UdpConnected.Disconnected => context.stop(self)

    case ReadData =>
      reqData(connection)

    case RaiseStart =>
      Future {
        blocking {
          Logger.info(s"${calibrationType} RasieStart: $mt")
          val cmd =
            if (calibrationType.zero) {
              config.calibrateZeoSeq map {
                seqNo =>
                  context.parent ! ExecuteSeq(seqNo, true)
              }

              reqZeroCalibration(connection, mt)
            } else {
              config.calibrateSpanSeq map {
                seqNo =>
                  context.parent ! ExecuteSeq(seqNo, true)
              }

              reqSpanCalibration(connection, mt)
            }

          calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.raiseTime, SECONDS), self, HoldStart))
        }
      }

    case ReportData(mtDataList) =>
      val data = mtDataList.filter { data => data.mt == mt }
      context become calibrationHandler(connection, calibrationType, mt, startTime, recording,
        data ::: calibrationDataList, zeroValue)

    case HoldStart =>
      Logger.debug(s"${calibrationType} HoldStart: $mt")
      context become calibrationHandler(connection, calibrationType, mt, startTime, true,
        calibrationDataList, zeroValue)
      calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.holdTime, SECONDS), self, DownStart))

    case DownStart =>
      Logger.debug(s"${calibrationType} DownStart: $mt")
      context become calibrationHandler(connection, calibrationType, mt, startTime, false,
        calibrationDataList, zeroValue)

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
      val values = calibrationDataList.map { _.value }
      val avg = if (values.length == 0)
        None
      else
        Some(values.sum / values.length)

      if (calibrationType.auto && calibrationType.zero) {
        Logger.info(s"$mt zero calibration end. ($avg)")
        collectorState = MonitorStatus.SpanCalibrationStat
        Instrument.setState(id, collectorState)
        context become calibrationHandler(connection, AutoSpan, mt, startTime, false, List.empty[MonitorTypeData], avg)

        setupSpanRaiseStartTimer
      } else {
        Logger.info(s"$mt calibration end.")
        val cal =
          if (calibrationType.auto) {
            Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, zeroValue, MonitorType.map(mt).span, avg)
          } else {
            if (calibrationType.zero) {
              Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, avg, None, None)
            } else {
              Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, None, MonitorType.map(mt).span, avg)
            }
          }
        Calibration.insert(cal)

        if (mt == mtCH4) {
          if (calibrationType.auto) {
            collectorState = MonitorStatus.ZeroCalibrationStat
            Instrument.setState(id, collectorState)
            context become calibrationHandler(connection, AutoZero,
              mtTHC, com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData], None)
            self ! RaiseStart
          } else {
            if (calibrationType.zero) {
              collectorState = MonitorStatus.ZeroCalibrationStat
              Instrument.setState(id, collectorState)
              context become calibrationHandler(connection, ManualZero,
                mtTHC, com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData], None)
              self ! RaiseStart
            } else {
              collectorState = MonitorStatus.SpanCalibrationStat
              Instrument.setState(id, collectorState)
              context become calibrationHandler(connection, ManualSpan,
                mtTHC, com.github.nscala_time.time.Imports.DateTime.now, false, List.empty[MonitorTypeData], None)
              setupSpanRaiseStartTimer
            }
          }
        } else {
          collectorState = MonitorStatus.NormalStat
          Instrument.setState(id, collectorState)
          context become connectionReady(connection)(false)
        }
      }

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
  }
}