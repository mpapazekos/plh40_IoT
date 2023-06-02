package edge_device

import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import plh40_iot.domain.DeviceTypes
import plh40_iot.util.Utils

import scala.concurrent.duration._

object Main {

    import spray.json._
    import InputJsonProtocol._

    def main(args: Array[String]): Unit = {
        
        // tries to get json input 
        val deviceListJson = 
            if (args.isEmpty) ConfigFactory.load().getString("edge_device.devices.list.json")
            else args(0)

        // if successfully parsed input starts actor system with devices
        Utils
            .tryParse(deviceListJson.parseJson.convertTo[InputInfo]) match {
                case Left(parseError) => 
                    println(parseError)
                    sys.exit(1) 
                case Right(input) =>
                    ActorSystem[Nothing](rootBehavior(input), s"${input.buildingId}-device-system")
            }
    }

    // spawns device actors for each device giben in input
    private def rootBehavior(input: InputInfo): Behavior[Nothing] = 
        Behaviors
            .setup[Nothing]{ context => 
                input.devices.foreach { d =>
                    DeviceTypes.create(d.deviceType, d.deviceId) match {
                        case Left(errorMsg) => 
                            context.log.error(errorMsg) 
                        case Right(device) =>
                            context.log.info(s"Spawning new ${d.deviceType} with id: ${d.deviceId} .") 
                            context.spawnAnonymous(
                                DeviceActor(
                                    device, 
                                    input.buildingId, 
                                    d.module, 
                                    pubTopic = s"/${input.buildingId}/${d.module}/${d.publishingTopic}/${d.deviceId}", 
                                    d.dataSendingPeriod.seconds
                                )
                            )               
                    }
                }
                Behaviors.empty
            }
}