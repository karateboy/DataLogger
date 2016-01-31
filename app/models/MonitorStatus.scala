package models

import play.api.Logger
import models.ModelHelper._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.ExecutionContext.Implicits.global

object StatusType extends Enumeration{
  val Internal = Value("0")
  val Auto     = Value("A")
  val Manual   = Value("M")
  def map= Map(Internal->"系統", Auto->"自動註記", Manual->"人工註記")
}

case class MonitorStatus(_id:String, desp:String){
  val info = MonitorStatus.getTagInfo(_id)
}
 
case class TagInfo(statusType:StatusType.Value, auditRule:Option[Char], id:String){
  override def toString={    
    if((statusType == StatusType.Auto || statusType == StatusType.Manual)
        && auditRule.isDefined)
      auditRule.get + id
    else
      statusType + id
  }
}

object MonitorStatus {
  implicit val reads = Json.reads[MonitorStatus]
  implicit val writes = Json.writes[MonitorStatus]
  val collectionName = "status"
  val collection = MongoDB.database.getCollection(collectionName)
  
  val defaultStatus = List(
    MonitorStatus("010", "正常量測值"),
    MonitorStatus("011", "超過預設高值測值"),
    MonitorStatus("012", "低於預設低值測值"),
    MonitorStatus("020", "零點偏移測試量測值"),
    MonitorStatus("021", "全幅偏移測試量測值"),
    MonitorStatus("030", "無效數據"),
    MonitorStatus("031", "監測設施維修、保養量測值"),
    MonitorStatus("032", "超過儀器量測範圍")
  )
  import org.mongodb.scala._
  def toDocument(ms:MonitorStatus)={
    Document(Json.toJson(ms).toString())
  }
  def toMonitorStatus(doc:Document)={
    Json.parse(doc.toJson()).validate[MonitorStatus].asOpt.get
  }
  
  def init(colNames: Seq[String]) {
    def insertDefaultStatus{
      val f = collection.insertMany(defaultStatus.map{toDocument}).toFuture()
      f.onFailure(futureErrorHandler)
    }

    if (!colNames.contains(collectionName)) {
      val f = MongoDB.database.createCollection(collectionName).toFuture()
      f.onFailure(futureErrorHandler)
      f.onSuccess({
        case _:Seq[_]=>
          insertDefaultStatus
      })
    }
  }

  def getTagInfo(tag: String) = {
    val id = tag.substring(1)
    val t = tag.charAt(0)
    if (t == '0')
      TagInfo(StatusType.Internal, None, id)
    else if (t == 'm' || t == 'M') {
      TagInfo(StatusType.Manual, Some(t), id)
    } else if (t.isLetter)
      TagInfo(StatusType.Auto, Some(t), id)
    else
      throw new Exception("Unknown type:" + t)
  }  

  def msList = {
    val f = collection.find().toFuture()
    f.onFailure(futureErrorHandler)
    waitReadyResult(f).map { toMonitorStatus }
  }


  def switchTagToInternal(tag:String)={
    val info = getTagInfo(tag)
    '0' + info.id
  }
      
  def getExplainStr(tag:String)={
    val tagInfo = getTagInfo(tag)
    if(tagInfo.statusType == StatusType.Auto){
      val t = tagInfo.auditRule.get
      "自動註記"
    }else {
      val ms = map(tag)
      ms.desp
    }
  }
  
  val NORMAL_STAT = "010"
  val OVER_STAT = "011"
  val BELOW_STAT = "012"

  def isValid(s: String) = {
    val tagInfo = getTagInfo(s)
    val VALID_STATS = List(NORMAL_STAT, OVER_STAT, BELOW_STAT).map(getTagInfo)
    
    tagInfo.statusType match {
      case StatusType.Internal =>
        VALID_STATS.contains(getTagInfo(s))
      case StatusType.Auto=>
        if(tagInfo.auditRule.isDefined && tagInfo.auditRule.get.isLower)
          true
        else
          false
      case _ =>
        false
    }
  }
    
  val CALBRATION_STAT = "020"
  val CALBRATION_SPAN_STAT = "021"
  
  def isCalbration(s: String) = {
    val CALBRATION_STATS = List(CALBRATION_STAT, CALBRATION_SPAN_STAT).map(getTagInfo)
    CALBRATION_STATS.contains(getTagInfo(s))
  }

  def isCalbrating(s: String)={
   val CALBRATION_STATS = List(CALBRATION_STAT, CALBRATION_SPAN_STAT).map(getTagInfo)
    CALBRATION_STATS.contains(getTagInfo(s))
  }
  
  val REPAIR_MAINTANCE = "031"
  val OVER_RANGE_DATA = "032"
  
  def isMaintanceOrRepairing(s: String)={
    getTagInfo(REPAIR_MAINTANCE) == getTagInfo(s)
  }
        
  def isError(s: String)={
    !(isValid(s)||isCalbration(s)||isMaintanceOrRepairing(s))  
  }
  
  def getCssStyleStr(tag:String, overInternal:Boolean=false, overLaw:Boolean=false)={
   val bkColor =  getBkColorStr(tag)
   val fgColor = 
     if(overLaw)
       "Red"
     else if(overInternal)
       "Blue"
     else
       "Black"
    s"Color:${fgColor};background-color:${bkColor}"
  }
  
  val OverInternalColor = "Blue"
  val OverLawColor = "Red"
  val NormalColor = "White"
  val CalibrationColor = "Chartreuse"
  val RepairColor = "DarkOrchid"
  val AbnormalColor = "DarkRed"
  val AutoAuditColor = "Cyan"
  val ManualAuditColor = "Gold"
  
  def getBkColorStr(tag:String)={
    val info=getTagInfo(tag)
    info.statusType match {
      case StatusType.Internal=>
        {
          if(isValid(tag))
            NormalColor
          else if(isCalbration(tag))
            CalibrationColor
          else if(isMaintanceOrRepairing(tag))
            RepairColor
          else 
            AbnormalColor
        }
      case StatusType.Auto=>
        AutoAuditColor
      case StatusType.Manual=>
        ManualAuditColor
    }
  }
  def update(tag:String, desp:String)={
    refreshMap
  }
  
  private def refreshMap() = {
    _map = Map(msList.map{s=>s.info.toString()->s}:_*)
    _map
  }
  private var _map:Map[String, MonitorStatus] = refreshMap
  val msvList = msList.map {r=>r.info.toString}
  val manualMonitorStatusList = {msvList.filter { _map(_).info.statusType == StatusType.Manual }}

  def map(key: String) = {
    _map.getOrElse(key, {
      val tagInfo = getTagInfo(key)
      tagInfo.statusType match {
        case StatusType.Auto =>
          val ruleId = tagInfo.auditRule.get.toLower
          MonitorStatus(key, s"自動註記:")
        case StatusType.Manual =>
          MonitorStatus(key, "人工註記")
        case StatusType.Internal =>
          MonitorStatus(key, "未知:" + key)
      }
    })
  }
}
