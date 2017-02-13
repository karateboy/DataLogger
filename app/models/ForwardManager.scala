package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import com.github.nscala_time.time.Imports._
import play.api.Play.current
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.ExecutionContext.Implicits.global

case class LatestRecordTime(time: Long)
object ForwardManager {
  import com.typesafe.config.ConfigFactory
  implicit val latestRecordTimeRead = Json.reads[LatestRecordTime]

  val serverConfig = ConfigFactory.load("server")
  val disable = serverConfig.getBoolean("disable")
  val server = serverConfig.getString("server")
  val monitor = serverConfig.getString("monitor")

  case object ForwardHour
  case class ForwardHourRecord(start: DateTime, end: DateTime)
  case object ForwardMin
  case class ForwardMinRecord(start: DateTime, end: DateTime)
  case object ForwardCalibration
  case object ForwardAlarm
  case object ForwardInstrumentStatus
  case object UpdateInstrumentStatusType
  case object GetInstrumentCmd

  var managerOpt: Option[ActorRef] = None
  var count = 0
  def startup() = {
    val props = Props(classOf[ForwardManager], server, monitor)
    if (disable)
      Logger.info("forwarding is disabled.")

    if (!disable) {
      Logger.info(s"create forwarder $server")
      managerOpt = Some(Akka.system.actorOf(props, name = s"forward_$count"))
      count += 1
    }
  }

  def updateInstrumentStatusType = {
    managerOpt map { _ ! UpdateInstrumentStatusType }
  }

  def forwardHourData = {
    managerOpt map { _ ! ForwardHour }
  }

  def forwardHourRecord(start: DateTime, end: DateTime) = {
    managerOpt map { _ ! ForwardHourRecord(start, end) }
  }

  def forwardMinData = {
    managerOpt map { _ ! ForwardMin }
  }

  def forwardMinRecord(start: DateTime, end: DateTime) = {
    managerOpt map { _ ! ForwardMinRecord(start, end) }
  }

}

class ForwardManager(server: String, monitor: String) extends Actor {
  import ForwardManager._

  val hourRecordForwarder = context.actorOf(Props(classOf[HourRecordForwarder], server, monitor),
    "hourForwarder")

  val minRecordForwarder = context.actorOf(Props(classOf[MinRecordForwarder], server, monitor),
    "minForwarder")

  val calibrationForwarder = context.actorOf(Props(classOf[CalibrationForwarder], server, monitor),
    "calibrationForwarder")

  val alarmForwarder = context.actorOf(Props(classOf[AlarmForwarder], server, monitor),
    "alarmForwarder")

  val instrumentStatusForwarder = context.actorOf(Props(classOf[InstrumentStatusForwarder], server, monitor),
    "instrumentStatusForwarder")

  val statusTypeForwarder = context.actorOf(Props(classOf[InstrumentStatusTypeForwarder], server, monitor),
    "statusTypeForwarder")

  {
    import scala.concurrent.duration._

    Akka.system.scheduler.scheduleOnce(Duration(30, SECONDS), statusTypeForwarder, UpdateInstrumentStatusType)
  }

  val timer = {
    import scala.concurrent.duration._
    Akka.system.scheduler.schedule(Duration(30, SECONDS), Duration(10, MINUTES), instrumentStatusForwarder, ForwardInstrumentStatus)
  }

  val timer2 = {
    import scala.concurrent.duration._
    Akka.system.scheduler.schedule(Duration(30, SECONDS), Duration(5, MINUTES), calibrationForwarder, ForwardCalibration)
  }

  val timer3 = {
    import scala.concurrent.duration._
    Akka.system.scheduler.schedule(Duration(30, SECONDS), Duration(3, MINUTES), alarmForwarder, ForwardAlarm)
  }

  val timer4 = {
    import scala.concurrent.duration._
    Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, GetInstrumentCmd)
  }

  def receive = handler

  import play.api.libs.ws._
  def handler: Receive = {
    case ForwardHour =>
      hourRecordForwarder ! ForwardHour

    case fhr: ForwardHourRecord =>
      hourRecordForwarder ! fhr

    case ForwardMin =>
      minRecordForwarder ! ForwardMin

    case fmr: ForwardMinRecord =>
      minRecordForwarder ! fmr

    case ForwardCalibration =>
      Logger.info("Forward Calibration")
      calibrationForwarder ! ForwardCalibration

    case ForwardAlarm =>
      alarmForwarder ! ForwardAlarm

    case ForwardInstrumentStatus =>
      instrumentStatusForwarder ! ForwardInstrumentStatus

    case UpdateInstrumentStatusType =>
      statusTypeForwarder ! UpdateInstrumentStatusType

    case GetInstrumentCmd =>
                /*
      val url = s"http://$server/InstrumentCmd/$monitor"
      val f = WS.url(url).get().map {
        response =>

          val result = response.json.validate[Seq[InstrumentCommand]]
          result.fold(
            error => {
              Logger.error(JsError.toJson(error).toString())
            },
            cmdSeq => {
              if (!cmdSeq.isEmpty) {
                Logger.info("receive cmd from server=>")
                Logger.info(cmdSeq.toString())
                for (cmd <- cmdSeq) {
                  cmd.cmd match {
                    case InstrumentCommand.AutoCalibration.cmd =>
                      DataCollectManager.autoCalibration(cmd.instId)

                    case InstrumentCommand.ManualZeroCalibration.cmd =>
                      DataCollectManager.zeroCalibration(cmd.instId)

                    case InstrumentCommand.ManualSpanCalibration.cmd =>
                      DataCollectManager.spanCalibration(cmd.instId)

                    case InstrumentCommand.BackToNormal.cmd =>
                      DataCollectManager.setInstrumentState(cmd.instId, MonitorStatus.NormalStat)
                  }
                }
              }
            })
            
      }
      f onFailure {
        case ex: Throwable =>
          ModelHelper.logException(ex)
      }
      f onComplete { x =>
        {
          import scala.concurrent.duration._
          Akka.system.scheduler.scheduleOnce(Duration(10, SECONDS), self, GetInstrumentCmd)
        }
      }
      
      */
  }

  override def postStop(): Unit = {
    timer.cancel
    timer2.cancel
    timer3.cancel
    timer4.cancel
  }
}