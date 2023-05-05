
import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import plh40_iot.domain.DeviceTypes._

import scala.concurrent.duration._

object DeviceActor {
    
    sealed trait Msg
    case object Tick extends Msg
    case object Emitted extends Msg 

    final case class RegisterMsg(info: String, replyTo: ActorRef[StatusReply[Done]]) extends Msg
    final case class ExecuteCommand(cmd: DeviceCmd, replyTo: ActorRef[StatusReply[Done]]) extends Msg
    
    def apply[A <: DeviceData](device: GenDevice[A], deviceId: String, buildingId: String, module: String, modulePath: String): Behavior[Msg] =        
    Behaviors
        .setup { context => 
            Behaviors
                .withTimers { timers => 
                    timers.startTimerAtFixedRate(Tick, 3.seconds)
                    new GenDeviceActor[A](deviceId, context, device, modulePath).registering(module, s"/$buildingId/register")
                }
        }

    def apply[A <: DeviceData, B <: DeviceCmd](device: SmartDevice[A, B], deviceId: String, buildingId: String, module: String, modulePath: String): Behavior[Msg] =        
        Behaviors
            .setup { context => 
                Behaviors
                    .withTimers { timers => 
                        context.spawnAnonymous[Nothing](CmdSubscriber(deviceId, modulePath, context.self, device.cmdFromJsonString))
                        timers.startTimerAtFixedRate(Tick, 3.seconds)
                        new SmartDeviceActor[A, B](deviceId, context, device, modulePath).registering(module, s"/$buildingId/register")
                    }
            }
}




