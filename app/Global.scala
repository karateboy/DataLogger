import play.api._
import models._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import akka.actor._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Global extends GlobalSettings {  
  override def onStart(app: Application) {
    Logger.info("Application has started")
    super.onStart(app)
    MongoDB.init()
    //val importManager = Akka.system.actorOf(Props[ImportManager], name = "ImportManager")
    
    //Akka.system.scheduler.schedule(Duration(1, MINUTES), Duration(6, HOURS), importManager, ImportYesterday)
  }

  override def onStop(app: Application) {
    Logger.info("Application shutdown...")
    MongoDB.cleanup
    super.onStop(app)
  }
}