package models

object TapiT100 extends TapiTxx(ModelConfig("T100", List("SO2"))){
  val modelReg = readModelSetting
}