
import akka.actor.typed.ActorSystem
import plh40_iot.domain.DeviceTypes
import com.typesafe.config.ConfigFactory

// "fc5d8e11-f44e-400f-ab65-d85c2fd958c1", "6874cd0f-a7e4-4d2f-85e6-dc1ddd37a75b"
object Main {

    def main(args: Array[String]): Unit = {

        if (args.isEmpty) {
            val config = ConfigFactory.load("edge_device")

            val deviceType = config.getString("edge_device.device.type")
            
            DeviceTypes
                .getDevice(deviceType) match {
                    case Right(device) =>
                    
                        val deviceId   = config.getString("edge_device.device.id")
                        val buildingId = config.getString("edge_device.building.id")
                        val module =     config.getString("edge_device.building.module")
                        val pubTopic =   config.getString("edge_device.mqtt.pubTopic")
                        
                        ActorSystem(
                            DeviceActor(device, deviceId, buildingId, module, s"/$buildingId/$module/$pubTopic/$deviceId"), 
                            s"${device.typeStr}_edge_device_system",
                            config
                        )

                    case Left(errorMsg) => 
                        println(errorMsg)
                        sys.exit(1)
                }  
        } 
        else {
            
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
}