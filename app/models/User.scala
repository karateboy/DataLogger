package models
import play.api._
import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import models._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Json
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions

case class User(email: String, password: String, name: String, phone: String, isAdmin: Boolean,
                alarmConfig: Option[AlarmConfig] = Some(AlarmConfig.defaultConfig), widgets: Option[List[MonitorType.Value]] = Some(List.empty[MonitorType.Value]))

object User {
  import org.mongodb.scala._
  import scala.concurrent._
  import scala.concurrent.duration._

  val ColName = "users"
  val collection = MongoDB.database.getCollection(ColName)
  def toDocument(user: User) = Document("_id" -> user.email, "password" -> user.password,
    "name" -> user.name, "phone" -> user.phone, "isAdmin" -> user.isAdmin,
    "alarmConfig" -> user.alarmConfig, "widgets" -> user.widgets)

  def init(colNames:Seq[String]){
    if(!colNames.contains(ColName)){
      val f = MongoDB.database.createCollection(ColName).toFuture()
      f.onFailure(futureErrorHandler)
    }  
    val f = collection.count().toFuture()
    f.onSuccess({
      case count:Seq[Long]=>
        if(count(0) == 0){
          val defaultUser = User("sales@wecc.com.tw", "abc123", "Aragorn", "02-2219-2886", true)
          Logger.info("Create default user:" + defaultUser.toString())
          newUser(defaultUser)
        }
    })
    f.onFailure(futureErrorHandler)
  }  
  
  def toUser(doc: Document) = {
    import scala.collection.JavaConversions._
    import models.AlarmConfig._
    val widgetArray = if (doc("widgets").isArray())
      Some(doc("widgets").asArray().getValues.map { w => MonitorType.withName(w.asString().getValue) }.toList)
    else
      None

    val alarmConfigOpt =
      if(doc("alarmConfig").isDocument())
        AlarmConfig.toAlarmConfig(doc("alarmConfig").asDocument())
      else
        None
    User(
      email = doc("_id").asString().getValue,
      password = doc("password").asString().getValue,
      name = doc("name").asString.getValue,
      phone = doc("phone").asString().getValue,
      isAdmin = doc("isAdmin").asBoolean().getValue,
      alarmConfig = alarmConfigOpt,
      widgets = widgetArray)
  }

  def createDefaultUser = {
    val f = collection.count().toFuture()
    val ret = waitReadyResult(f)
    if (ret(0) == 0) {
      val defaultUser = User("sales@wecc.com.tw", "abc123", "Aragorn", "02-2219-2886", true)
      Logger.info("Create default user:" + defaultUser.toString())
      newUser(defaultUser)
    }
  }
  def newUser(user: User) = {
    val f = collection.insertOne(toDocument(user)).toFuture()
    waitReadyResult(f)
  }

  import org.mongodb.scala.model.Filters._
  def deleteUser(email: String) = {
    val f = collection.deleteOne(equal("_id", email)).toFuture()
    waitReadyResult(f)
  }

  def updateUser(user: User) = {
    val f = collection.replaceOne(equal("_id", user.email), toDocument(user)).toFuture()
    waitReadyResult(f)
  }

  def getUserByEmail(email: String) = {
    val f = collection.find(equal("_id", email)).first().toFuture()
    f.onFailure { futureErrorHandler }
    val ret = waitReadyResult(f)
    if (ret.length == 0)
      None
    else
      Some(toUser(ret(0)))
  }

  def getAllUsers() = {
    val f = collection.find().toFuture()
    f.onFailure { futureErrorHandler }
    val ret = waitReadyResult(f)
    ret.map { toUser }
  }

  def getAdminUsers() = {
    val f = collection.find(equal("isAdmin", true)).toFuture()
    f.onFailure { futureErrorHandler }
    val ret = waitReadyResult(f)
    ret.map { toUser }
  }

}
