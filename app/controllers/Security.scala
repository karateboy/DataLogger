package controllers
import play.api._
import play.api.mvc.Security._
import play.api.mvc._
import scala.concurrent._

class AuthenticatedRequest[A](val userinfo:String, request: Request[A]) extends WrappedRequest[A](request)

object Security {
  val idKey = "ID"
  val nameKey = "Name"
  val adminKey = "Admin"
  case class UserInfo(id:String, name:String, isAdmin:Boolean)
  

  def getUserinfo(request: RequestHeader):Option[UserInfo] = {
    val optId = request.session.get(idKey)
    if(optId.isEmpty)
      return None
      
    val optAdmin = request.session.get(adminKey)
    if(optAdmin.isEmpty)
      return None
      
    val optName = request.session.get(nameKey)
    if(optName.isEmpty)
      return None
          
    Some(UserInfo(optId.get, optName.get, optAdmin.get.toBoolean))
  }
  
  def onUnauthorized(request: RequestHeader) = {
    Results.Redirect(routes.Login.prompt()) 
  }
  
  //def invokeBlock[A](request: Request[A], block: (AuthenticatedRequest[A]) => Future[Result]) = {
  //  AuthenticatedBuilder(getUserinfo _, onUnauthorized)
  //})
  
  //def isAuthenticated(f: => String => Request[AnyContent] => Result) = {
  //  Authenticated(getUserinfo, onUnauthorized) { user =>
  //    Action(request => f(user)(request))
  //  }
  // }
  
  def setUserinfo[A](request: Request[A], userInfo:UserInfo)={
    request.session + 
      (idKey->userInfo.id.toString()) + (adminKey->userInfo.isAdmin.toString()) + 
      (nameKey->userInfo.name) 
  }
  
  def getUserInfo[A]()(implicit request:Request[A]):Option[UserInfo]={
    getUserinfo(request)
  }
  
  def Authenticated = new AuthenticatedBuilder(getUserinfo, onUnauthorized)
}