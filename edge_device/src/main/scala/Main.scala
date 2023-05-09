
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import plh40_iot.domain.DeviceTypes
import plh40_iot.util.Utils
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.duration._
import akka.actor.typed.Behavior

object Main {

    final case class NewDeviceInfo(
        deviceType: String, 
        deviceId: String,  
        module: String, 
        publishingTopic: String,
        dataSendingPeriod: Double
    )

    final case class InputInfo(buildingId: String, devices: Array[NewDeviceInfo])

    implicit val newDeviceFormat = jsonFormat5(NewDeviceInfo)
    implicit val inputFormat = jsonFormat2(InputInfo) 

    // ======================================================================================

    def main(args: Array[String]): Unit = {
        
        val deviceListJson = 
            if (args.isEmpty) ConfigFactory.load().getString("edge_device.devices.list.json")
            else args(0)

        Utils
            .tryParse(deviceListJson.parseJson.convertTo[InputInfo]) match {
                case Left(parseError) => 
                    println(parseError)
                    sys.exit(1) 
                case Right(input) =>
                    ActorSystem[Nothing](rootBehavior(input),s"${input.buildingId}-actor-system")
            }
    }

    private def rootBehavior(input: InputInfo): Behavior[Nothing] = 
        Behaviors
            .setup[Nothing]{ context => 
                input.devices.foreach { d =>
                    DeviceTypes.getDevice(d.deviceType, d.deviceId) match {
                        case Left(errorMsg) => 
                            context.log.error(errorMsg) 
                        case Right(device) =>
                            context.log.info(s"Spawning new ${d.deviceType} with id: ${d.deviceId} .") 
                            context.spawnAnonymous(
                                DeviceActor(
                                    device, 
                                    input.buildingId, 
                                    d.module, 
                                    modulePath = s"/${input.buildingId}/${d.module}/${d.publishingTopic}/${d.deviceId}", 
                                    d.dataSendingPeriod.seconds
                                )
                            )               
                    }
                }
                Behaviors.empty
            }
}