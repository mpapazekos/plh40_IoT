package plh40_iot.domain
import plh40_iot.domain.devices.Battery
import plh40_iot.domain.devices.Thermostat

object DeviceTypes {

    trait DeviceData
    trait DeviceCmd
    trait GenDevice[A <: DeviceData] {
        
        val typeStr : String
        def initState: A
        def generateData(curr: A): A
        

        def fromJsonString(json: String): Either[String, A] 
        
        def toJsonString(data: A): Either[String, String]
    }

    trait SmartDevice[A <: DeviceData, B <: DeviceCmd] extends GenDevice[A] {
        
        def execute(cmd: B, data: A): A

        def cmdFromJsonString(json: String): Either[String, B]
    }

    def getDevice(name: String) =
        name match {
                case "thermostat" => Right(Thermostat)
                case "battery" => Right(Battery)
                case _ => Left("INVALID DEVICE NAME")
            } 
}
