package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.Logger
import models.User
case class Credential(account: String, password: String)

/**
 * @author user
 */
object Login extends Controller {
  implicit val credentialReads = Json.reads[Credential]    
        
  def authenticate = Action(BodyParsers.parse.json){
    implicit request =>
      val credentail = request.body.validate[Credential]      
      credentail.fold(
          error=>{
            BadRequest(Json.obj("ok"->false, "msg"->JsError.toJson(error)))
          }, 
          crd=>{
            val optUser = User.getUserByEmail(crd.account)
            if(optUser.isEmpty || optUser.get.password != crd.password)
              Ok(Json.obj("ok"->false, "msg"->"密碼或帳戶錯誤"))
            else {
              val user = optUser.get
              import Security._
              val userInfo = UserInfo(user.email, user.name, user.isAdmin)
              Ok(Json.obj("ok"->true)).withSession(Security.setUserinfo(request, userInfo))              
            }              
          })
  }
  
  def prompt = Action{
    Ok(views.html.login())
  }
  
  def logout = Action{
    Redirect(routes.Login.prompt()).withNewSession
  }
}