package plh40_iot.domain

import spray.json._
import DefaultJsonProtocol._

import plh40_iot.domain.devices._

object DeviceJsonProtocol {

  implicit object BatteryStatusFormat extends JsonFormat[BatteryStatus] {

      override def read(json: JsValue): BatteryStatus = 
        json match {
            case JsString(value) => 
                value match {
                    case "discharging" => Discharging
                    case "charging" => Charging
                    case "off" => Off   
            } 
        }
      
      override def write(obj: BatteryStatus): JsValue = {
        val value = 
            obj match {
                case Charging => "charging"
                case Discharging => "discharging"
                case Off => "off"
            }

         JsString(value)   
      }  
  }

  implicit val batteryFormat = jsonFormat2(BatteryData)

  //=============================================================================

  implicit object ThermostatCmdFormat extends JsonFormat[ThermostatCmd] {

    override def read(json: JsValue): ThermostatCmd = 
      json.asJsObject.getFields("command", "value") match {
          case Seq(JsString("set"), JsNumber(value)) => SetVal(value.toDouble)
      }
      
    override def write(cmd: ThermostatCmd): JsValue = 
      cmd match {
          case SetVal(value) => 
              JsObject(Map("command" -> JsString("set"), "value" -> JsNumber(value)))
      }
  }


  implicit val thermostatFormat = jsonFormat2(ThermostatData)

  //=============================================================================

 
  

}
