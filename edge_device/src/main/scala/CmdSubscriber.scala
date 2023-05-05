
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout

import scala.concurrent.duration.DurationInt
import plh40_iot.util.MqttConnector
import akka.stream.alpakka.mqtt.MqttSubscriptions
import akka.stream.alpakka.mqtt.MqttQoS
import akka.stream.typed.scaladsl.ActorFlow
import plh40_iot.domain.DeviceTypes
import plh40_iot.util.Utils
import akka.Done

object CmdSubscriber {
  
    import DeviceTypes.DeviceCmd
    import DeviceActor.ExecuteCommand

    def apply(
        deviceId: String, 
        modulePath: String,
        deviceActor: ActorRef[DeviceActor.Msg], 
        jsonToCmd: String => Either[String, DeviceCmd]
    ): Behavior[Nothing] = 
        Behaviors
            .setup[Nothing]{ context => 

                implicit val system = context.system
                implicit val ec = system.classicSystem.dispatcher
                implicit val timeout: Timeout = 5.seconds

                //subsciber
                val subSource = 
                    MqttConnector.subscriberSource(s"SUB_$deviceId", MqttSubscriptions(s"$modulePath/cmd" -> MqttQoS.AtLeastOnce))

                val actorFlow =
                    ActorFlow.askWithStatus[DeviceCmd, ExecuteCommand, Done](deviceActor)(ExecuteCommand.apply)


                /*
                                +---------------------------------------------------+
                                |   (deviceActor)                                   |
                                |    ^        |                                     |
                                |    |        |                                     |
                                |   Cmd   CmdReceived                               |
                                |    |        |                                     |
                                |  +-|--------V--+               +---------------+  |
                                |  |             |               |               |  |
                MqttMessage ~~> | actorFlow  ~~> CmdReceived ~~> Sink.ignore  |  |
                                |  |             |               |               |  |
                                |  +-------------+               +---------------+  |
                                +---------------------------------------------------+
                */  
                subSource
                .map(jsonToCmd)
                .via(Utils.errorHandleFlow())
                .via(actorFlow)
                .run()

                Behaviors.empty
            }
}
