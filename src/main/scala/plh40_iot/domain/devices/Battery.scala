package plh40_iot.domain.devices

import scala.util.Random


import plh40_iot.domain.DeviceTypes._
import plh40_iot.util.Utils.currentTimestamp

sealed trait BatteryStatus 

case object Charging extends BatteryStatus 
case object Discharging extends BatteryStatus 
case object Off extends BatteryStatus


final case class BatteryData(deviceId: String, percentage: Double, status: BatteryStatus, timestamp: String) extends DeviceData {
    require(percentage >= 0 && percentage <= 100)
}

sealed trait BatteryCmd extends DeviceCmd

final case class ChangeStatus(status: BatteryStatus) extends BatteryCmd


final class Battery(deviceId: String) extends SmartDevice[BatteryData, BatteryCmd](deviceId) {

    import spray.json._
    import plh40_iot.domain.DeviceJsonProtocol._
    import plh40_iot.util.Utils.tryParse

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
                        val randomDrain = Random.between(0.1, 3.0)
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
