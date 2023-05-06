package plh40_iot.domain
import plh40_iot.domain.devices.Battery
import plh40_iot.domain.devices.Thermostat

object DeviceTypes {

    trait DeviceData
    trait DeviceCmd

    abstract class GenDevice[A <: DeviceData](val id: String) {
        
        val typeStr : String
        def initState: A
        def generateData(curr: A): A
        

        def fromJsonString(json: String): Either[String, A] 
        
        def toJsonString(data: A): Either[String, String]
    }

    abstract class SmartDevice[A <: DeviceData, B <: DeviceCmd](override val id: String) extends GenDevice[A](id) {
        
        def execute(cmd: B, data: A): A

        def cmdFromJsonString(json: String): Either[String, B]
    }

    def getDevice(name: String, deviceId: String) =
        name match {
                case "thermostat" => Right(new Thermostat(deviceId))
                case "battery" => Right(new Battery(deviceId))
                case _ => Left("INVALID DEVICE NAME")
            } 
}
