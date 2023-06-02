package plh40_iot.domain.devices

import scala.util.Random

import plh40_iot.domain.DeviceTypes._

import plh40_iot.util.Utils.currentTimestamp

//=================================================================

sealed trait ThermostatCmd extends DeviceCmd

/** sets thermostat temperature to given value*/
final case class SetVal(value: Double) extends ThermostatCmd

/**
  * Represents data to be reported by a thermostat
  * @param deviceId Thermostat id
  * @param value Current temperature value
  * @param unit Temperature measurement unit
  * @param timestamp Timestamp of data captured
  */
final case class ThermostatData(deviceId: String, value: Double, unit: String, timestamp: String) extends DeviceData

//=================================================================

/**Creates a thermostat object with given id. */
final class Thermostat(deviceId: String) extends SmartDevice[ThermostatData, ThermostatCmd](deviceId) {

    import plh40_iot.util.Utils.tryParse
    
    import spray.json._
    import spray.json.DefaultJsonProtocol._
    implicit val thermostatJsonFormat = jsonFormat4(ThermostatData)
   
    override val typeStr = "thermostat"
    
    override def initState = 
        ThermostatData(deviceId, 25.0, "Celsius", currentTimestamp())

    override def generateData(data: ThermostatData): ThermostatData = {
        val randomTemperature = Random.between(-10d, 40)
        ThermostatData(deviceId, randomTemperature, "Celsius", currentTimestamp())
    }

    override def execute(cmd: ThermostatCmd, data: ThermostatData): ThermostatData = 
        cmd match {
            case SetVal(value) => 
                ThermostatData(deviceId, value, "Celsius", currentTimestamp())
        }

    override def toJsonString(data: ThermostatData): Either[String, String] =
        tryParse(data.toJson.compactPrint)
       
    override def fromJsonString(json: String): Either[String, ThermostatData] = 
        tryParse(json.parseJson.convertTo[ThermostatData])

    override def cmdFromJsonString(json: String): Either[String, ThermostatCmd] = 
        tryParse(
            json.parseJson.asJsObject.getFields("name", "value") match {
                case Seq(JsString("set"), JsNumber(value)) => 
                    if ( value >= -10d && value <= 40)  SetVal(value.toDouble)
                    else throw new IllegalArgumentException("Cannot set thermostat value out of normal range!")
            }
        )     
}
