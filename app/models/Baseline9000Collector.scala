package models

import akka.actor._
import models.Protocol.ProtocolParam
import play.api.Play.current
import play.api._
import play.api.libs.concurrent.Akka

import scala.concurrent.ExecutionContext.Implicits.global


object Baseline9000Collector {
  val ch4Offset = Play.current.configuration.getDouble("baseline.offset.ch4").getOrElse(0d)
  val nmhcOffset = Play.current.configuration.getDouble("baseline.offset.nmhc").getOrElse(0d)
  var count = 0

  def start(id: String, protocolParam: ProtocolParam, config: Baseline9000Config)(implicit context: ActorContext) = {
    val actorName = s"Baseline_${count}"
    count += 1
    val collector = context.actorOf(Props(classOf[Baseline9000Collector], id, protocolParam, config), name = actorName)
    Logger.info(s"$actorName is created.")

    collector
  }
  Logger.info(s"CH4 offset=$ch4Offset")
  Logger.info(s"NMHC offset=$nmhcOffset")

  case object OpenComPort

  case object ReadData

}

class Baseline9000Collector(id: String, protocolParam: ProtocolParam, config: Baseline9000Config) extends Actor {

  import Baseline9000Collector._
  import DataCollectManager._
  import ModelHelper._
  import TapiTxx._

  import scala.concurrent.duration._
  import scala.concurrent.{Future, blocking}

  val StartShippingDataByte: Byte = 0x11
  val StopShippingDataByte: Byte = 0x13
  val AutoCalibrationByte: Byte = 0x12
  val BackToNormalByte: Byte = 0x10
  val ActivateMethaneZeroByte: Byte = 0x0B
  val ActivateMethaneSpanByte: Byte = 0x0C
  val ActivateNonMethaneZeroByte: Byte = 0xE
  val ActivateNonMethaneSpanByte: Byte = 0xF
  val mtCH4 = MonitorType.withName("CH4")
  val mtNMHC = MonitorType.withName("NMHC")
  val mtTHC = MonitorType.withName("THC")
  var collectorState = {
    val instrument = Instrument.getInstrument(id)
    instrument(0).state
  }
  var serialCommOpt: Option[SerialComm] = None
  var timerOpt: Option[Cancellable] = None
  var calibrateRecordStart = false
  var calibrateTimerOpt: Option[Cancellable] = None

  override def preStart() = {
    timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, OpenComPort))
  }

  // override postRestart so we don't call preStart and schedule a new message
  override def postRestart(reason: Throwable) = {}

  def receive = openComPort

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

  def readData(calibratingMt: Option[MonitorType.Value]) = {
    Future {
      blocking {
        for (serial <- serialCommOpt) {
          val lines = serial.getLine2(timeout = 3)
          for (line <- lines) {
            val parts = line.split('\t')

            def calibrate(v: Double, m: Option[Double], b: Option[Double]) =
              v * m.getOrElse(1d) + b.getOrElse(0d)

            if (parts.length >= 4) {
              val ch4Value = calibrate(parts(2).toDouble, MonitorType.map(mtCH4).m, MonitorType.map(mtCH4).b)
              val nmhcValue = calibrate(parts(4).toDouble, MonitorType.map(mtNMHC).m, MonitorType.map(mtNMHC).b)
              val ch4 = MonitorTypeData(mtCH4, ch4Value, collectorState)
              val nmhc = MonitorTypeData(mtNMHC, nmhcValue, collectorState)
              val thc =
                MonitorTypeData(mtTHC, (ch4Value + nmhcValue), collectorState)

              if (calibrateRecordStart)
                self ! ReportData(List(ch4, nmhc, thc))

              context.parent ! ReportData(List(ch4, nmhc, thc))
            }
          }
        }
        timerOpt = Some(Akka.system.scheduler.scheduleOnce(Duration(1, SECONDS), self, ReadData))
      }
    } onFailure serialErrorHandler
  }

  def comPortOpened: Receive = {
    case ReadData =>
      readData(None)

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

  def calibrationHandler(calibrationType: CalibrationType, mt: MonitorType.Value, startTime: com.github.nscala_time.time.Imports.DateTime,
                         calibrationDataList: List[MonitorTypeData], zeroValue: Option[Double]): Receive = {
    case ReadData =>
      readData(Some(mt))

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

              if (mt == mtCH4)
                ActivateMethaneZeroByte
              else
                ActivateNonMethaneZeroByte
            } else {
              config.calibrateSpanSeq map {
                seqNo =>
                  context.parent ! ExecuteSeq(seqNo, true)
              }

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
          for (serial <- serialCommOpt)
            serial.port.writeByte(BackToNormalByte)

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
      val values = calibrationDataList.map {
        _.value
      }
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
              mtTHC, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
          } else {
            if (calibrationType.zero) {
              collectorState = MonitorStatus.ZeroCalibrationStat
              Instrument.setState(id, collectorState)
              context become calibrationHandler(ManualZero,
                mtTHC, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
            } else {
              collectorState = MonitorStatus.SpanCalibrationStat
              Instrument.setState(id, collectorState)
              context become calibrationHandler(ManualSpan,
                mtTHC, com.github.nscala_time.time.Imports.DateTime.now, List.empty[MonitorTypeData], None)
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

  case object RaiseStart

  case object HoldStart

  case object DownStart

  case object CalibrateEnd
}