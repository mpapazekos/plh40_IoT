package plh40_iot.domain.devices

import scala.util.Random

import plh40_iot.domain.DeviceTypes._

sealed trait ThermostatCmd extends DeviceCmd

final case class SetVal(value: Double) extends ThermostatCmd
final case class ThermostatData(value: Double, unit: String) extends DeviceData 


object Thermostat extends SmartDevice[ThermostatData, ThermostatCmd] {

    import plh40_iot.util.Utils.tryParse
    
    import spray.json._
    import plh40_iot.domain.DeviceJsonProtocol._


    override val typeStr = "thermostat"
    
    override def initState = 
        ThermostatData(25, "Celsius")

    override def generateData(data: ThermostatData): ThermostatData = {
        val randomTemperature = Random.between(-10d, 40)
        ThermostatData(randomTemperature, "Celsius")
    }

    override def execute(cmd: ThermostatCmd, data: ThermostatData): ThermostatData = 
        cmd match {
            case SetVal(value) => 
                ThermostatData(value, "Celsius")
        }

    override def toJsonString(data: ThermostatData): Either[String, String] =
        tryParse(data.toJson.compactPrint)
       
    override def fromJsonString(json: String): Either[String, ThermostatData] = 
        tryParse(json.parseJson.convertTo[ThermostatData])

    
    override def cmdFromJsonString(json: String): Either[String, ThermostatCmd] = 
        tryParse(
            json.parseJson.asJsObject.getFields("name", "value") match {
                case Seq(JsString("set"), JsNumber(value)) => SetVal(value.toDouble)
            }
        )
        
}
