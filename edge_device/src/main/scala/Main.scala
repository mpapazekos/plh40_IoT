
import akka.actor.typed.ActorSystem
import plh40_iot.domain.DeviceTypes
import com.typesafe.config.ConfigFactory
import java.util.UUID
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

object Main {

    def main(args: Array[String]): Unit = {
        
        //singleDevice(args)
        buildingDevices(args)
    
    }

    private def buildingDevices(args: Array[String]): Unit = {

        import DeviceTypes.getDevice

        val buildingId = 
            if (args.isEmpty) ConfigFactory.load().getString("edge_device.building.id")
            else args(0)

        val rootBehavior: Behavior[Nothing] = 
            Behaviors.setup[Nothing]{ context => 
                
                // module1 -----------------------------------------------------------
                val device1_id = UUID.randomUUID().toString()
                val device1 = 
                    DeviceActor(
                        getDevice("thermostat", device1_id).getOrElse(???), 
                        buildingId, "module1", s"/$buildingId/module1/temperature/$device1_id"
                    )

                val device2_id = UUID.randomUUID().toString()
                val device2 = 
                    DeviceActor(
                        getDevice("battery", device2_id).getOrElse(???), 
                        buildingId, "module1", s"/$buildingId/module1/status/$device2_id"
                    )

                // module2 ---------------------------------------------------
                val device3_id = UUID.randomUUID().toString()
                val device3 = 
                    DeviceActor(
                        getDevice("thermostat", device3_id).getOrElse(???), 
                        buildingId, "module2", s"/$buildingId/module2/temperature/$device3_id"
                    )

                val device4_id = UUID.randomUUID().toString()
                val device4 = 
                    DeviceActor(
                        getDevice("battery", device4_id).getOrElse(???), 
                        buildingId, "module2", s"/$buildingId/module2/status/$device4_id"
                    )

                context.spawnAnonymous(device1)
                context.spawnAnonymous(device2)
                context.spawnAnonymous(device3)
                context.spawnAnonymous(device4)
    
                Behaviors.empty
            }

        ActorSystem[Nothing](rootBehavior,s"$buildingId-actor-system")
    }


    private def singleDevice(args: Array[String]): Unit = {
        if (args.isEmpty) {
            val config = ConfigFactory.load()

            val deviceType = config.getString("edge_device.device.type")
            val deviceId   = config.getString("edge_device.device.id")

            DeviceTypes
                .getDevice(deviceType, deviceId) match {
                    case Right(device) =>

                        val buildingId = config.getString("edge_device.building.id")
                        val module =     config.getString("edge_device.building.module")
                        val pubTopic =   config.getString("edge_device.mqtt.pubTopic")
                        
                        ActorSystem(
                            DeviceActor(device, buildingId, module, s"/$buildingId/$module/$pubTopic/$deviceId"), 
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
            
            DeviceTypes.getDevice(args(0), args(1)) match {
                case Right(device) =>
                    val (deviceId, buildingId, module, pubTopic) = (args(1), args(2), args(3), args(4))
                    
                    ActorSystem(
                        DeviceActor(device, buildingId, module, s"/$buildingId/$module/$pubTopic/$deviceId"), 
                        s"${device.typeStr}_edge_device_system"
                    )

                case Left(errorMsg) => 
                    println(errorMsg)
                    sys.exit(1)
            }       
        } 
    }
}