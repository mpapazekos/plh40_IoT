package edge_device

import spray.json.DefaultJsonProtocol._ 

/**
  * Used to parse a json format input
  * @param buildingId Building id 
  * @param devices List of device info to build
  */
final case class InputInfo(buildingId: String, devices: Array[NewDeviceInfo])

/**
  * Used to parse a json format input for a specific device
  * @param deviceType device type string
  * @param deviceId   device Id
  * @param module     module group the device belongs to
  * @param publishingTopic topic path in which device publishes data 
  * @param dataSendingPeriod how often (in seconds) the device will send new data 
  */
final case class NewDeviceInfo(
        deviceType: String, 
        deviceId: String,  
        module: String, 
        publishingTopic: String,
        dataSendingPeriod: Double
    )

object InputJsonProtocol {

    implicit val newDeviceFormat = jsonFormat5(NewDeviceInfo)
    implicit val inputFormat     = jsonFormat2(InputInfo) 
}