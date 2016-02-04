package models
import play.api._
import ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._

object Adam4017 {
  case class ChannelCfg(enable:Boolean, mt:Option[MonitorType.Value], max:Option[Double], mtMax:Option[Double], 
      min:Option[Double], mtMin:Option[Double])
  case class Adam4017Param(addr:String, ch:Seq[ChannelCfg])
  
  implicit val cfgReads = Json.reads[ChannelCfg]
  implicit val reads = Json.reads[Adam4017Param] 
  
  def validateParam(json:String)={
    val ret = Json.parse(json).validate[Adam4017Param]
    ret.fold(
        error=>{
          Logger.error(JsError.toJson(error).toString())
          throw new Exception(JsError.toJson(error).toString())
        }, 
        param=>{
          if(param.ch.length != 8){
            throw new Exception("ch # shall be 8")
          }
          for(cfg<-param.ch){
            if(cfg.enable){
              assert(cfg.mt.isDefined)
              assert(cfg.max.get > cfg.min.get)
              assert(cfg.mtMax.get > cfg.mtMin.get)
            }
          }
          param})
  }
  
}