import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import plh40_iot.domain.DeviceTypes._

import scala.concurrent.duration._
import akka.actor.typed.SupervisorStrategy

// Actor representing a device in a building 
object DeviceRep {

    sealed trait Msg

    /** Sent when new data is received from topic */
    final case class NewData(value: DeviceData, replyTo: ActorRef[DataReceived]) extends Msg

    /** Response to NewData message successfull execution */   
    final case class DataReceived(id: String, data: DeviceData) extends Msg


    /** Sent when the current device data is requested in a query */
    final case class RequestData(replyTo: ActorRef[DataResponse]) extends Msg

    /** Response to RequestData message successfull execution */  
    final case class DataResponse(id: String, dataJson: String) extends Msg


    /** Sents when a command in json format is to be published to the device */
    final case class PublishCommand(cmdJson: String, replyTo: ActorRef[StatusReply[String]]) extends Msg

    /**
      * Create a general type device
      * @param device GenDevice instance
      * @param modulePath topic to subscribe in order to receive data
      * @param buildingId building this devices belongs to
      */
    def apply[A <: DeviceData](
        device: GenDevice[A], 
        modulePath: String,
        buildingId: String
    ): Behavior[Msg] = 
        Behaviors
            .setup { context => 
                context.spawn[Nothing](
                    Behaviors
                        .supervise[Nothing](DeviceDataStreamer(device, modulePath, s"Data-$buildingId", context.self))
                        .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2d)), 
                    name = s"SUB_${device.id}"
                )
                new GenDeviceRep(context, device, modulePath).running(None)
            }

    /**
      * Create a smart type device
      * @param device SmartDevice instance
      * @param modulePath topic to subscribe in order to receive data
      * @param buildingId building this devices belongs to
      */
    def apply[A <: DeviceData, B <: DeviceCmd](
        device: SmartDevice[A, B], 
        modulePath: String,
        buildingId: String
    ): Behavior[Msg] = 
        Behaviors
            .setup { context =>       
                context.spawn[Nothing](
                    Behaviors
                        .supervise[Nothing](DeviceDataStreamer(device, modulePath, s"Data-$buildingId", context.self))
                        .onFailure(SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2d)),
                    name = s"SUB_${device.id}"
                )
                new SmartDeviceRep(context, device, modulePath).running(None)
            }
}


