package plh40_iot.domain.devices

import plh40_iot.domain.DeviceTypes._
import plh40_iot.util.Utils.currentTimestamp
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.util.Random

//===========================================================================

sealed trait BatteryStatus 

case object Charging    extends BatteryStatus 
case object Discharging extends BatteryStatus 
case object Off         extends BatteryStatus

sealed trait BatteryCmd extends DeviceCmd
final case class ChangeStatus(status: BatteryStatus) extends BatteryCmd

final case class BatteryData(deviceId: String, percentage: Double, status: BatteryStatus, timestamp: String) extends DeviceData {
    require(percentage >= 0 && percentage <= 100)
}

//===========================================================================

object BatteryJsonProtocol {

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

    implicit val batteryFormat = jsonFormat4(BatteryData)
}

//===========================================================================

final class Battery(deviceId: String) extends SmartDevice[BatteryData, BatteryCmd](deviceId) {

    import plh40_iot.util.Utils.tryParse
    import BatteryJsonProtocol._

    override val typeStr = "battery"
    
    override def initState = 
        BatteryData(deviceId, 100d, Discharging, currentTimestamp())

    override def generateData(state: BatteryData) = {

        val (newPercentage, newStatus) = 
            state.status match {
                case Charging => 
                    if (state.percentage >= 80.0) 
                        (state.percentage, Discharging)
                    else
                        (state.percentage + 0.5, Charging)
                case Discharging =>
                    if (state.percentage <= 20.0) 
                        (state.percentage, Charging)
                    else {
                        val randomDrain = Random.between(0.5, 3.0)
                        (state.percentage - randomDrain, Discharging)
                    }
                        
                case Off => (state.percentage, state.status)
            }

        BatteryData(deviceId, newPercentage, newStatus, currentTimestamp())
    }

    override def execute(cmd: BatteryCmd, data: BatteryData): BatteryData = 
        cmd match {
            case ChangeStatus(status) =>
                BatteryData(deviceId, data.percentage, status, currentTimestamp())
        }

    override def toJsonString(data: BatteryData): Either[String, String] =
        tryParse(data.toJson.compactPrint)

    override def fromJsonString(json: String): Either[String, BatteryData] =
        tryParse(json.parseJson.convertTo[BatteryData])

    override def cmdFromJsonString(json: String): Either[String, BatteryCmd] = 
        tryParse{
            val fields = json.parseJson.asJsObject.fields

            fields("name").asInstanceOf[JsString].value match {
                case "change-status" => 
                    val newStatus = fields("value").convertTo[BatteryStatus]
                    ChangeStatus(newStatus)
            }
        }
}
