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
  import TapiTxx._

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
  val calibrationMtList = List(mtCH4, mtNMHC)

  var calibrateRecordStart = false

  def readData = {
    Future {
      blocking {
        for (serial <- serialCommOpt) {
          val lines = serial.getLine2(timeout = 3)
          for (line <- lines) {
            val parts = line.split('\t')
            if (parts.length >= 4) {
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
          if (state == MonitorStatus.NormalStat) {
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
      context become calibrationHandler(AutoZero, com.github.nscala_time.time.Imports.DateTime.now,
        List.empty[MonitorTypeData], Map.empty[MonitorType.Value, Double])
      self ! RaiseStart

    case ManualZeroCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.ZeroCalibrationStat
      Instrument.setState(id, collectorState)
      context become calibrationHandler(ManualZero, com.github.nscala_time.time.Imports.DateTime.now,
        List.empty[MonitorTypeData], Map.empty[MonitorType.Value, Double])
      self ! RaiseStart

    case ManualSpanCalibration(instId) =>
      assert(instId == id)
      collectorState = MonitorStatus.SpanCalibrationStat
      Instrument.setState(id, collectorState)
      context become calibrationHandler(ManualSpan, com.github.nscala_time.time.Imports.DateTime.now,
        List.empty[MonitorTypeData], Map.empty[MonitorType.Value, Double])
      self ! RaiseStart
  }

  var calibrateTimerOpt: Option[Cancellable] = None

  def calibrationHandler(calibrationType: CalibrationType, startTime: com.github.nscala_time.time.Imports.DateTime,
                         calibrationDataList: List[MonitorTypeData], zeroMap: Map[MonitorType.Value, Double]): Receive = {
    case ReadData =>
      readData

    case RaiseStart =>
      Future {
        blocking {
          Logger.info(s"${calibrationType} RasieStart")
          val cmd =
            if (calibrationType.zero) {
              config.calibrateZeoSeq map {
                seqNo =>
                  context.parent ! ExecuteSeq(seqNo, true)
              }
            } else {
              config.calibrateSpanSeq map {
                seqNo =>
                  context.parent ! ExecuteSeq(seqNo, true)
              }
            }

          calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.raiseTime, SECONDS), self, HoldStart))
        }
      } onFailure serialErrorHandler

    case ReportData(mtDataList) =>
      context become calibrationHandler(calibrationType, startTime, mtDataList ::: calibrationDataList, zeroMap)

    case HoldStart =>
      Logger.debug(s"${calibrationType} HoldStart")
      calibrateRecordStart = true
      calibrateTimerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(config.holdTime, SECONDS), self, DownStart))

    case DownStart =>
      Logger.debug(s"${calibrationType} DownStart")
      calibrateRecordStart = false

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
      } onFailure serialErrorHandler

    case CalibrateEnd =>

      val mtPair =
        for {
          mt <- calibrationMtList
          values = calibrationDataList.filter { mtData => mtData.mt == mt }.map { _.value } if (values.length > 0)
          avg = values.sum / values.length  
        } yield {
          mt -> avg
        }

      if (calibrationType.auto && calibrationType.zero) {
        Logger.info(s"Zero calibration end.")
        collectorState = MonitorStatus.SpanCalibrationStat
        Instrument.setState(id, collectorState)
        context become calibrationHandler(AutoSpan, startTime, List.empty[MonitorTypeData], mtPair.toMap)
        self ! RaiseStart
      } else {
        Logger.info(s"Calibration end.")
        if (calibrationType.auto) {
          val spanMap = mtPair.toMap
          for (mt <- calibrationMtList) {
            val cal = Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, zeroMap.get(mt), MonitorType.map(mt).span, spanMap.get(mt))
            Calibration.insert(cal)
          }
        } else {
          val calibrationMap = mtPair.toMap
          for (mt <- calibrationMtList) {
            val cal =
              if (calibrationType.zero) {
                Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, calibrationMap.get(mt), None, None)
              } else {
                Calibration(mt, startTime, com.github.nscala_time.time.Imports.DateTime.now, None, MonitorType.map(mt).span, calibrationMap.get(mt))
              }
            Calibration.insert(cal)
          }
        }

        collectorState = MonitorStatus.NormalStat
        Instrument.setState(id, collectorState)
        context become comPortOpened
      }

    case SetState(id, state) =>
      Future {
        blocking {
          if (state == MonitorStatus.NormalStat) {
            collectorState = state
            Instrument.setState(id, state)

            for (serial <- serialCommOpt) {
              serial.port.writeByte(BackToNormalByte)
              serial.port.writeByte(StartShippingDataByte)
            }
            calibrateTimerOpt map {
              timer => timer.cancel()
            }
            context.parent ! ExecuteSeq(T700_STANDBY_SEQ, true)
            context become comPortOpened
          } else {
            Logger.info(s"Ignore setState $state during calibration")
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