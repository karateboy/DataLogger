package models

import play.api._
import akka.actor._

object SimulatorCollector{
}

class SimulatorCollector extends Actor{
    case class Connect(ip:String, port:Int)
    
    def receive = {
      case Connect(ip, port)=>
        
    }
}