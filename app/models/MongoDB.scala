package models
import play.api._

object MongoDB {
  import org.mongodb.scala._

  val url = Play.current.configuration.getString("my.mongodb.url")
  val dbName = Play.current.configuration.getString("my.mongodb.db")
  
  val mongoClient: MongoClient = MongoClient(url.get)
  val database: MongoDatabase = mongoClient.getDatabase(dbName.get);
  
  def cleanup={
    mongoClient.close()
  }
}