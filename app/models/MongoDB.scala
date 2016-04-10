package models
import play.api._
import scala.concurrent.ExecutionContext.Implicits.global
 
object MongoDB {
  import org.mongodb.scala._

  val url = Play.current.configuration.getString("my.mongodb.url")
  val dbName = Play.current.configuration.getString("my.mongodb.db")
  
  val mongoClient: MongoClient = MongoClient(url.get)
  val database: MongoDatabase = mongoClient.getDatabase(dbName.get);
  def init(){
    val f = database.listCollectionNames().toFuture()
    val colFuture = f.map { colNames =>
      //MonitorType => 
      val mtFutureOpt = MonitorType.init(colNames)
      Instrument.init(colNames)
      Record.init(colNames)
      User.init(colNames)
      Calibration.init(colNames)
      MonitorStatus.init(colNames)
      Alarm.init(colNames)
      InstrumentStatus.init(colNames)
      ManualAuditLog.init(colNames)
    }
    //Program need to wait before init complete
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import scala.language.postfixOps
    
    Await.result(colFuture, 30 seconds)
  }
  
  def cleanup={
    mongoClient.close()
  }
}