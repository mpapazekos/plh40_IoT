package edge_device

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import plh40_iot.domain.DeviceTypes._

import scala.concurrent.duration._

object DeviceActor {
    
    sealed trait Msg

    /** Each time a Tick message is received new data will be generated and sent to mqtt broker*/
    case object Tick extends Msg

    /** Signals that the latest element is emitted into the stream */
    case object Emitted extends Msg 

    /** Received when device asks for registration to the building manager system. 
     *  A reply is sent to acknowlegde the arrival of the message.
     */
    final case class RegisterMsg(info: String, replyTo: ActorRef[StatusReply[Done]]) extends Msg

    /** Received when a new command arrives from the broker.
     *  A reply is sent to acknowlegde the execution of the command.
     */
    final case class ExecuteCommand(cmd: DeviceCmd, replyTo: ActorRef[StatusReply[Done]]) extends Msg
    
    /**
      * Creates a general device actor.
      * @param device The implementation of a general device
      * @param buildingId Id of the building which contains the device
      * @param module Building module to group the device 
      * @param pubTopic Publishing topic path to construct final topic
      * @param tickPeriod Duration period for receiving Tick messages.
      * @return General device actor behavior
      */
    def apply[A <: DeviceData](device: GenDevice[A], buildingId: String, module: String, pubTopic: String, tickPeriod: FiniteDuration): Behavior[Msg] =        
        Behaviors
            .setup { context => 
                Behaviors
                    .withTimers { timers => 
                        timers.startTimerAtFixedRate(Tick, tickPeriod)
                        new GenDeviceActor[A](context, device, pubTopic, buildingId).registering(module, s"/$buildingId/register")
                    }
            }

    /**
      * Creates a smart device actor.
      * @param device The implementation of a smart device
      * @param buildingId Id of the building which contains the device
      * @param module Building module to group the device 
      * @param pubTopic Publishing topic path to construct final topic
      * @param tickPeriod Duration period for receiving Tick messages.
      * @return Smart device actor behavior
      */
    def apply[A <: DeviceData, B <: DeviceCmd](device: SmartDevice[A, B], buildingId: String, module: String, pubTopic: String, tickPeriod: FiniteDuration): Behavior[Msg] =        
        Behaviors
            .setup { context => 
                Behaviors
                    .withTimers { timers => 
                        context.spawnAnonymous[Nothing](CmdSubscriber(device.id, pubTopic, context.self, device.cmdFromJsonString))
                        timers.startTimerAtFixedRate(Tick, tickPeriod)
                        new SmartDeviceActor[A, B](context, device, pubTopic, buildingId).registering(module, s"/$buildingId/register")
                    }
            }
}




