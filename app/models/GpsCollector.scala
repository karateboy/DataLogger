package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.ExecutionContext.Implicits.global
import GPS._
import Protocol.ProtocolParam

object GpsCollector {
  var count = 0
  def start(id: String, protocolParam: ProtocolParam)(implicit context: ActorContext) = {
    val prop = Props(classOf[GpsCollector], id, protocolParam)
    val collector = context.actorOf(prop, name = "Gps_" + count)
    count += 1
    collector
  }
}

import net.sf.marineapi.nmea.io.ExceptionListener;
import net.sf.marineapi.nmea.io.SentenceReader;
import net.sf.marineapi.provider.PositionProvider;
import net.sf.marineapi.provider.event.PositionEvent;
import net.sf.marineapi.provider.event.PositionListener;
import net.sf.marineapi.nmea.event.SentenceEvent;
import net.sf.marineapi.nmea.event.SentenceListener;
import net.sf.marineapi.nmea.io.SentenceReader;
import net.sf.marineapi.nmea.sentence.SentenceValidator;

class GpsCollector(id: String, protocolParam: ProtocolParam) extends Actor
    with ActorLogging with SentenceListener with ExceptionListener with PositionListener {
  val comm: SerialComm = SerialComm.open(protocolParam.comPort.get)
  var reader: SentenceReader = _

  import DataCollectManager._
  import scala.concurrent.Future
  import scala.concurrent.blocking

  def receive = handler(MonitorStatus.NormalStat)

  def handler(collectorState: String): Receive = {
    case SetState(id, state) =>
      Logger.warn(s"Ignore $self => $state")
  }

  def init() {
    Logger.info("Init GPS reader...")
    val stream = comm.is
    reader = new SentenceReader(stream)
    reader.setExceptionListener(this)
    val provider = new PositionProvider(reader)
    provider.addListener(this)
    reader.start()
  }

  init

  override def postStop(): Unit = {
    if (reader != null) {
      reader.stop()
    }

    if (comm != null)
      SerialComm.close(comm)
  }

  import com.github.nscala_time.time.Imports._
  var reportTime = DateTime.now
  def providerUpdate(evt: PositionEvent) {
    if (reportTime < DateTime.now - 3.second) {
      val lat = MonitorTypeData(MonitorType.LAT, evt.getPosition.getLatitude, MonitorStatus.NormalStat)
      val lng = MonitorTypeData(MonitorType.LNG, evt.getPosition.getLongitude, MonitorStatus.NormalStat)
      context.parent ! ReportData(List(lat, lng))
      reportTime = DateTime.now
    }
  }

  def onException(ex: Exception) {
    Logger.warn(ex.getMessage)
  }

  /*
	 * (non-Javadoc)
	 * @see net.sf.marineapi.nmea.event.SentenceListener#readingPaused()
	 */
  def readingPaused() {
    Logger.debug("-- Paused --");
  }

  /*
	 * (non-Javadoc)
	 * @see net.sf.marineapi.nmea.event.SentenceListener#readingStarted()
	 */
  def readingStarted() {
    Logger.debug("-- Started --");
  }

  /*
	 * (non-Javadoc)
	 * @see net.sf.marineapi.nmea.event.SentenceListener#readingStopped()
	 */
  def readingStopped() {
    Logger.debug("-- Stopped --");
  }

  /*
	 * (non-Javadoc)
	 * @see
	 * net.sf.marineapi.nmea.event.SentenceListener#sentenceRead(net.sf.marineapi
	 * .nmea.event.SentenceEvent)
	 */
  def sentenceRead(event: SentenceEvent) {
    // here we receive each sentence read from the port
    Logger.debug(event.getSentence().toString());
  }

}
