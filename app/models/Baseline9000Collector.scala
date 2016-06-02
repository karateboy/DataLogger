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

object Baseline9000Collector {
  case object OpenComPort
  case object ReadData

  var count = 0
  def start(id: String, protocolParam: ProtocolParam, config: Baseline9000Config)(implicit context: ActorContext) = {
    import Protocol.ProtocolParam
    val actorName = s"Baseline_${count}"
    count += 1
    val collector = context.actorOf(Props(classOf[Baseline9000Collector], id, protocolParam, config), name = actorName)
    Logger.info(s"$actorName is created.")

    collector
  }

}

class Baseline9000Collector(id: String, protocolParam: ProtocolParam, config: Baseline9000Config) extends Actor {
  import Baseline9000Collector._
  import scala.concurrent.duration._
  import scala.concurrent.Future
  import scala.concurrent.blocking
  import ModelHelper._
  import DataCollectManager._

  var collectorState = {
    val instrument = Instrument.getInstrument(id)
    instrument(0).state
  }

  var serialCommOpt: Option[SerialComm] = None
  var timerOpt: Option[Cancellable] = None

  override def preStart() = {
    timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, OpenComPort))
  }

  // override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable) = {}

  def receive = openComPort

  val StartShippingDataByte: Byte = 0x11
  val StopShippingDataByte: Byte = 0x13
  val AutoCalibrationByte: Byte = 0x12
  val BackToNormalByte: Byte = 0x10
  val ActivateMethaneZeroByte: Byte = 0x0B
  val ActivateMethaneSpanByte: Byte = 0x0C
  val ActivateNonMethaneZeroByte: Byte = 0xE
  val ActivateNonMethaneSpanByte: Byte = 0xF

  def openComPort: Receive = {
    case OpenComPort =>
      Future {
        blocking {
          serialCommOpt = Some(SerialComm.open(protocolParam.comPort.get))
          context become comPortOpened
          Logger.info(s"${self.path.name}: Open com port.")
          timerOpt = if (collectorState == MonitorStatus.NormalStat) {
            for (serial <- serialCommOpt) {
              serial.port.writeByte(StartShippingDataByte)
            }
            Some(Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, ReadData))
          } else {
            Some(Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, SetState(id, MonitorStatus.NormalStat)))
          }
        }
      } onFailure serialErrorHandler
  }

  def serialErrorHandler: PartialFunction[Throwable, Unit] = {
    case ex: Exception =>
      logInstrumentError(id, s"${self.path.name}: ${ex.getMessage}. Close com port.", ex)
      for (serial <- serialCommOpt) {
        SerialComm.close(serial)
        serialCommOpt = None
      }
      context become openComPort
      timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenComPort))
  }

  val mtCH4 = MonitorType.withName("CH4")
  val mtNMHC = MonitorType.withName("NMHC")
  val mtTHC = MonitorType.withName("THC")

  var calibrateRecordStart = false

  def readData = {
    Future {
      blocking {
        for (serial <- serialCommOpt) {
          val lines = serial.getLine
          for (line <- lines) {
            val parts = line.split('\t')
            val ch4Value = parts(2).toDouble
            val nmhcValue = parts(4).toDouble
            val ch4 = MonitorTypeData(mtCH4, ch4Value, collectorState)
            val nmhc = MonitorTypeData(mtNMHC, nmhcValue, collectorState)
            val thc = MonitorTypeData(mtTHC, (ch4Value + nmhcValue), collectorState)

            if (calibrateRecordStart)
              self ! ReportData(List(ch4, nmhc))

            context.parent ! ReportData(List(ch4, nmhc, thc))
          }
        }
        timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData))
      }
    } onFailure serialErrorHandler
  }

  case object RaiseStart
  case object HoldStart
  case object DownStart
  case object CalibrateEnd

  def comPortOpened: Receive = {
    case ReadData =>
      readData

    case SetState(id, state) =>
      Future {
        blocking {
          collectorState = state
          Instrument.setState(id, state)
          state match {
            case MonitorStatus.NormalStat =>
              for (serial <- serialCommOpt) {
                serial.port.writeByte(BackToNormalByte)
                serial.port.writeByte(StartShippingDataByte)
              }
              timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData))
          }
        }
      } onFailure serialErrorHandler

    case AutoCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      Instrument.setState(id, collectorState)
      context become calibrationHandler(AutoZero, mtCH4, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
      self ! RaiseStart

    case ManualZeroCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      Instrument.setState(id, collectorState)
      context become calibrationHandler(ManualZero, mtCH4, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
      self ! RaiseStart

    case ManualSpanCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.SpanCalibrationStat
      Instrument.setState(id, collectorState)
      context become calibrationHandler(ManualSpan, mtCH4, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
      self ! RaiseStart
  }

  var calibrateTimerOpt: Option[Cancellable] = None

  def calibrationHandler(calibrationType: CalibrationType, mt: MonitorType.Value, startTime: com.github.nscala_time.time.Imports.DateTime,
                         calibrationDataList: List[MonitorTypeData], zeroValue: Option[Double]): Receive = {
    case ReadData =>
      readData

    case RaiseStart =>
      Future {
        blocking {
          Logger.info(s"${calibrationType} RasieStart: $mt")
          val cmd =
            if (calibrationType.zero) {
              if (mt == mtCH4)
                ActivateMethaneZeroByte
              else
                ActivateNonMethaneZeroByte
            } else {
              if (mt == mtCH4)
                ActivateMethaneSpanByte
              else
                ActivateNonMethaneSpanByte
            }

          for (serial <- serialCommOpt)
            serial.port.writeByte(cmd)
          calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.raiseTime, SECONDS), self, HoldStart))
        }
      } onFailure serialErrorHandler

    case ReportData(mtDataList) =>
      val data = mtDataList.filter { data => data.mt == mt }
      context become calibrationHandler(calibrationType, mt, startTime, data ::: calibrationDataList, zeroValue)

    case HoldStart =>
      Logger.debug(s"${calibrationType} HoldStart: $mt")
      calibrateRecordStart = true
      calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.holdTime, SECONDS), self, DownStart))

    case DownStart =>
      Logger.debug(s"${calibrationType} DownStart: $mt")
      calibrateRecordStart = false

      Future {
        blocking {
          for (serial <- serialCommOpt)
            serial.port.writeByte(BackToNormalByte)

          calibrateTimerOpt = if (calibrationType.auto && calibrationType.zero)
            Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, CalibrateEnd))
          else
            Some(Akka.system.scheduler.scheduleOnce(Duration(config.downTime, SECONDS), self, CalibrateEnd))
        }
      } onFailure serialErrorHandler

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
        context become calibrationHandler(AutoSpan, mt, startTime, List.empty[MonitorTypeData], avg)
        self ! RaiseStart
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
            context become calibrationHandler(AutoZero,
              mtNMHC, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
          } else {
            if (calibrationType.zero) {
              collectorState = MonitorStatus.ZeroCalibrationStat
              Instrument.setState(id, collectorState)
              context become calibrationHandler(ManualZero,
                mtNMHC, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
            } else {
              collectorState = MonitorStatus.SpanCalibrationStat
              Instrument.setState(id, collectorState)
              context become calibrationHandler(ManualSpan,
                mtNMHC, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
            }
          }
          self ! RaiseStart
        } else {
          collectorState = MonitorStatus.NormalStat
          Instrument.setState(id, collectorState)
          context become comPortOpened
        }
      }

    case SetState(id, state) =>
      Future {
        blocking {
          collectorState = state
          Instrument.setState(id, state)
          state match {
            case MonitorStatus.NormalStat =>
              for (serial <- serialCommOpt) {
                serial.port.writeByte(BackToNormalByte)
                serial.port.writeByte(StartShippingDataByte)
              }
              calibrateTimerOpt map {
                timer => timer.cancel()
              }
              context become comPortOpened

            case MonitorStatus.ZeroCalibrationStat =>
              Logger.info("Ignore calibration SetState")
          }
        }
      } onFailure serialErrorHandler
  }

  override def postStop() = {
    for (timer <- timerOpt) {
      timer.cancel()
    }
    for (serial <- serialCommOpt) {
      SerialComm.close(serial)
      serialCommOpt = None
    }
  }
}