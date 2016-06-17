package models

/**
 * @author user
 */
object MonitorStatusFilter extends Enumeration {

  val ValidData = Value("valid")
  val Normal = Value("normal")
  val Calbration = Value("calbration")
  val Maintance = Value("maintance")
  val InvalidData = Value("invalid")
  val All = Value("all")  

  val map = Map(
    All -> "全部",
    Normal -> "正常量測值",
    Calbration -> "校正",
    Maintance -> "維修",
    InvalidData -> "無效數據",
    ValidData -> "有效數據")

  def isMatched(msf: MonitorStatusFilter.Value, stat: String) = {
    msf match {
      case MonitorStatusFilter.All =>
        true

      case MonitorStatusFilter.Normal =>
        stat == MonitorStatus.NormalStat

      case MonitorStatusFilter.Calbration=>
        MonitorStatus.isCalbration(stat)
        
      case MonitorStatusFilter.Maintance =>
        MonitorStatus.isMaintenance(stat)

      case MonitorStatusFilter.InvalidData =>
        MonitorStatus.isError(stat)
        
      case MonitorStatusFilter.ValidData =>
        MonitorStatus.isValid(stat)
    }
  }
}