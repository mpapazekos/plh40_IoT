package plh40_iot.edge_device

import akka.actor.typed.ActorSystem
import plh40_iot.domain.DeviceTypes

// "fc5d8e11-f44e-400f-ab65-d85c2fd958c1", "6874cd0f-a7e4-4d2f-85e6-dc1ddd37a75b"
object Main {

    def main(args: Array[String]): Unit = {
        require(
            args.length == 5,
            "Invalid number of arguements. Correct use: [device_type] [device_id] [buildingId] [module] [publishing_topic]"
        )
        
        DeviceTypes.getDevice(args(0)) match {
            case Right(device) =>
                val (deviceId, buildingId, module, pubTopic) = (args(1), args(2), args(3), args(4))
                
                ActorSystem(
                    DeviceActor(device, deviceId, buildingId, module, s"/$buildingId/$module/$pubTopic/$deviceId"), 
                    s"${device.typeStr}_edge_device_system"
                )

            case Left(errorMsg) => 
                println(errorMsg)
                sys.exit(1)
        }       
    } 
}