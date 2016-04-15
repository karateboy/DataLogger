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

  val StartShippingData: Byte = 0x11
  val StopShippingData: Byte = 0x13
  val AutoCalibration: Byte = 0x12
  val BackToNormal: Byte = 0x10
  val ActivateMethaneZero: Byte = 0x0B
  val ActivateMethaneSpan: Byte = 0x0C
  val ActivateNonMethaneZero: Byte = 0xE
  val ActivateNonMethaneSpan: Byte = 0xF

  def openComPort: Receive = {
    case OpenComPort =>
      Future {
        blocking {
          serialCommOpt = Some(SerialComm.open(protocolParam.comPort.get))
          context become comPortOpened
          Logger.info(s"${self.path.name}: Open com port.")
          timerOpt = if (collectorState == MonitorStatus.NormalStat) {
            for (serial <- serialCommOpt) {
              serial.port.writeByte(StartShippingData)
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
      logInstrumentError(id, s"${self.path.name}: ${ex.getMessage}. Close com port.")
      for (serial <- serialCommOpt) {
        SerialComm.close(serial)
        serialCommOpt = None
      }
      context become openComPort
      timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, OpenComPort))
  }

  val mtCH4 = MonitorType.withName("CH4")
  val mtNMHC = MonitorType.withName("NMHC")

  var calibrateRecordStart = false

  def readData = {
    Future {
      blocking {
        for (serial <- serialCommOpt) {
          val line = serial.port.readString()
          val parts = line.split('\t')
          Logger.debug(s"parts=${parts.length}=>$line")
          val ch4 = MonitorTypeData(mtCH4, parts(2).toDouble, collectorState)
          val nmhc = MonitorTypeData(mtNMHC, parts(4).toDouble, collectorState)

          if (calibrateRecordStart)
            self ! ReportData(List(ch4, nmhc))

          context.parent ! ReportData(List(ch4, nmhc))
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
                serial.port.writeByte(BackToNormal)
                serial.port.writeByte(StartShippingData)
              }
              timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData))

            case MonitorStatus.ZeroCalibrationStat =>
              context become calibrationHandler(state, mtCH4, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], 0)              
              self ! RaiseStart
          }
        }
      } onFailure serialErrorHandler
  }

  var calibrateTimerOpt: Option[Cancellable] = None

  def calibrationHandler(state: String, mt: MonitorType.Value, startTime: com.github.nscala_time.time.Imports.DateTime, calibrationDataList: List[MonitorTypeData], zeroValue: Double): Receive = {
    case ReadData =>
      readData

    case RaiseStart =>
      Future {
        blocking {
          Logger.debug(s"$state RasieStart: $mt")
          val cmd =
            state match {
              case MonitorStatus.ZeroCalibrationStat =>
                if (mt == mtCH4)
                  ActivateMethaneZero
                else
                  ActivateNonMethaneZero
              case MonitorStatus.SpanCalibrationStat =>
                if (mt == mtCH4)
                  ActivateMethaneSpan
                else
                  ActivateNonMethaneSpan
            }

          for (serial <- serialCommOpt)
            serial.port.writeByte(cmd)
          calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.raiseTime, SECONDS), self, HoldStart))
        }
      } onFailure serialErrorHandler

    case ReportData(mtDataList) =>
      val data = mtDataList.filter { data => data.mt == mt }
      context become calibrationHandler(state, mt, startTime, data ::: calibrationDataList, zeroValue)

    case HoldStart =>
      calibrateRecordStart = true
      calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.holdTime, SECONDS), self, DownStart))

    case DownStart =>
      calibrateRecordStart = false

      Future {
        blocking {
          for (serial <- serialCommOpt)
            serial.port.writeByte(BackToNormal)
          calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.holdTime, SECONDS), self, CalibrateEnd))
        }
      } onFailure serialErrorHandler

    case CalibrateEnd =>
      val values = calibrationDataList.map { _.value }
      val avg = values.sum / values.length
      if (state == MonitorStatus.ZeroCalibrationStat) {
        collectorState = MonitorStatus.SpanCalibrationStat
        Instrument.setState(id, collectorState)
        context become calibrationHandler(MonitorStatus.SpanCalibrationStat, mt, startTime, List.empty[MonitorTypeData], avg)
        self ! RaiseStart
      } else {
        Logger.info(s"$mt calibration end.")
        val spanStd = MonitorType.map(mt).span.getOrElse(0d)
        val cal = Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, zeroValue, spanStd, avg)
        Calibration.insert(cal)
        if (mt == mtCH4) {
          collectorState = MonitorStatus.ZeroCalibrationStat
          Instrument.setState(id, collectorState)
          context become calibrationHandler(MonitorStatus.ZeroCalibrationStat,
            mtNMHC, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], 0)
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
                serial.port.writeByte(BackToNormal)
                serial.port.writeByte(StartShippingData)
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