
import spray.json.DefaultJsonProtocol._ 

final case class InputInfo(buildingId: String, devices: Array[NewDeviceInfo])

final case class NewDeviceInfo(
        deviceType: String, 
        deviceId: String,  
        module: String, 
        publishingTopic: String,
        dataSendingPeriod: Double
    )

object InputJsonProtocol {

    implicit val newDeviceFormat = jsonFormat5(NewDeviceInfo)

    implicit val inputFormat = jsonFormat2(InputInfo) 
}