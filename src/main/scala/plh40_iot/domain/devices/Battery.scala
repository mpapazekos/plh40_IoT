package plh40_iot.domain.devices

import scala.util.Random


import plh40_iot.domain.DeviceTypes._

sealed trait BatteryStatus 

case object Charging extends BatteryStatus 
case object Discharging extends BatteryStatus 
case object Off extends BatteryStatus


final case class BatteryData(percentage: Double, status: BatteryStatus) extends DeviceData {
    require(percentage >= 0 && percentage <= 100)
}

sealed trait BatteryCmd extends DeviceCmd

final case class ChangeStatus(status: BatteryStatus) extends BatteryCmd


object Battery extends SmartDevice[BatteryData, BatteryCmd] {

    import spray.json._
    import plh40_iot.domain.DeviceJsonProtocol._
    import plh40_iot.util.Utils.tryParse

    override val typeStr = "battery"
    
    override def initState = 
        BatteryData(100d, Discharging)

    override def generateData(state: BatteryData) = {
        state.status match {
            case Charging => 
                if (state.percentage >= 80.0) 
                    BatteryData(state.percentage, Discharging)
                else
                    BatteryData(state.percentage + 0.5, Charging)

            case Discharging =>
                if (state.percentage <= 20.0) 
                    BatteryData(state.percentage, Charging)
                else {
                    val randomDrain = Random.between(0.1, 3.0)
                    BatteryData(state.percentage - randomDrain, Discharging)
                }
                    
            case Off => state
        }
    }

    override def execute(cmd: BatteryCmd, data: BatteryData): BatteryData = 
        cmd match {
            case ChangeStatus(status) =>
                BatteryData(data.percentage, status )
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
