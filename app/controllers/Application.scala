package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import scala.concurrent.Future
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
import Highchart._
import models._

object Application extends Controller {

  val title = "資料擷取器"

  def index = Security.Authenticated {
    implicit request =>
      val user = request.user
      Ok(views.html.outline(title, user, views.html.dashboard("test")))
  }
  
  def dashboard = Security.Authenticated {
    Ok(views.html.dashboard(""))
  }

  val path = current.path.getAbsolutePath + "/importEPA/"

  def importEpa103 = Action {
    Epa103Importer.importData(path)
    Ok(s"匯入 $path")
  }

  def userManagement() = Security.Authenticated {
    implicit request =>
      val userInfoOpt = Security.getUserinfo(request)
      if (userInfoOpt.isEmpty)
        Forbidden("No such user!")
      else {
        val userInfo = userInfoOpt.get
        val user = User.getUserByEmail(userInfo.id).get
        val userList =
          if (!user.isAdmin)
            List.empty[User]
          else
            User.getAllUsers.toList

        Ok(views.html.userManagement(userInfo, user, userList))
      }
  }

  import models.User._
  implicit val userParamRead = Json.reads[User]

  def newUser = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val newUserParam = request.body.validate[User]

      newUserParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          User.newUser(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def deleteUser(email: String) = Security.Authenticated {
    implicit request =>
      val userInfoOpt = Security.getUserinfo(request)
      val userInfo = userInfoOpt.get

      User.deleteUser(email)
      Ok(Json.obj("ok" -> true))
  }

  def updateUser(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val userParam = request.body.validate[User]

      userParam.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        param => {
          User.updateUser(param)
          Ok(Json.obj("ok" -> true))
        })
  }

  def getAllUsers = Security.Authenticated {
    val users = User.getAllUsers()
    implicit val userWrites = Json.writes[User]

    Ok(Json.toJson(users))
  }

  def monitorTypeConfig = Security.Authenticated {
    implicit request =>
      Ok(views.html.monitorTypeConfig())
  }

  case class EditData(id: String, data: String)
  def saveMonitorTypeConfig() = Security.Authenticated {
    implicit request =>
      try {
        val mtForm = Form(
          mapping(
            "id" -> text,
            "data" -> text)(EditData.apply)(EditData.unapply))

        val mtData = mtForm.bindFromRequest.get
        val mtInfo = mtData.id.split(":")
        val mt = MonitorType.withName(mtInfo(0))

        MonitorType.updateMonitorType(mt, mtInfo(1), mtData.data)

        Ok(mtData.data)
      } catch {
        case e: Exception =>
          Logger.error(e.toString)
          BadRequest(e.toString)
        case e: Throwable =>
          Logger.error(e.toString)
          BadRequest(e.toString)
      }
  }

  def getInstrumentTypes = Security.Authenticated {
    implicit val write = Json.writes[InstrumentTypeInfo]
    val iTypes = InstrumentType.map.values.toSeq.map { t => InstrumentTypeInfo(t.id, t.desp, t.protocol) }
    Ok(Json.toJson(iTypes))
  }

  def newInstrument = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val instrumentResult = request.body.validate[Instrument]

      instrumentResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        rawInstrument => {
          try {
            val instType = InstrumentType.map(rawInstrument.instType)
            val instParam = instType.driver.verifyParam(rawInstrument.param)
            val newInstrument = rawInstrument.replaceParam(instParam)
            Instrument.newInstrument(newInstrument)
            val mtList = instType.driver.getMonitorTypes(instParam)
            for (mt <- mtList) {
              MonitorType.updateMonitorTypeInstrument(mt, newInstrument._id)
            }
            DataCollectManager.startCollect(newInstrument)
            Ok(Json.obj("ok" -> true))
          } catch {
            case ex: Throwable =>
              ModelHelper.logException(ex)
              Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
          }
        })
  }

  def manageInstrument = Security.Authenticated {
    Ok(views.html.manageInstrument(""))
  }

  def getInstrumentList = Security.Authenticated {
    import Instrument._
    val ret = Instrument.getInstrumentList()
    val ret2 = ret.map { _.getInfoClass }
    Ok(Json.toJson(ret2))
  }

  def removeInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach { DataCollectManager.stopCollect(_) }
      ids.map { Instrument.delete }
    } catch {
      case ex: Exception =>
        Logger.error(ex.toString)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def deactivateInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.foreach { DataCollectManager.stopCollect(_) }
      ids.map { Instrument.deactivate }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.toString)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def activateInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      val f = ids.map { Instrument.activate }
      ids.foreach { DataCollectManager.startCollect(_) }      
    } catch {
      case ex: Throwable =>
        Logger.error(ex.toString)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def toggleMaintainInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>
        Instrument.getInstrument(id).map { inst =>
          val newState =
            if (inst.state == MonitorStatus.MaintainStat)
              MonitorStatus.NormalStat
            else
              MonitorStatus.MaintainStat

          DataCollectManager.setInstrumentState(id, newState)
        }
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.toString)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def calibrateInstrument(instruments: String) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>        
          DataCollectManager.setInstrumentState(id, MonitorStatus.ZeroCalibrationStat)        
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.toString)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def getExecuteSeq(instruments: String, seq:Int) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>        
          DataCollectManager.executeSeq(id, seq)        
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.toString)
        Ok(ex.getMessage)
    }

    Ok(s"Execute $instruments $seq")
  }

  def executeSeq(instruments: String, seq:Int) = Security.Authenticated {
    val ids = instruments.split(",")
    try {
      ids.map { id =>        
          DataCollectManager.executeSeq(id, seq)        
      }
    } catch {
      case ex: Throwable =>
        Logger.error(ex.toString)
        Ok(Json.obj("ok" -> false, "msg" -> ex.getMessage))
    }

    Ok(Json.obj("ok" -> true))
  }

  def monitorTypeList = Security.Authenticated {
    val mtList = MonitorType.mtvList.map { mt => MonitorType.map(mt) }
     
    Ok(Json.toJson(mtList))
  }
}
