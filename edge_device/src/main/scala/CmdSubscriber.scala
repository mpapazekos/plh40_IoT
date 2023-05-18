
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
import akka.stream.scaladsl.Flow
import akka.NotUsed
import akka.stream.scaladsl.Source
import scala.concurrent.Future

object CmdSubscriber {
  
    import DeviceTypes.DeviceCmd
    import DeviceActor.ExecuteCommand

    def apply(
        deviceId: String, 
        modulePath: String,
        deviceActor: ActorRef[DeviceActor.Msg], 
        jsonToCmd: String => Either[String, DeviceCmd]
    )(implicit askTimeout: Timeout = 5.seconds): Behavior[Nothing] = 
        Behaviors
            .setup[Nothing]{ context => 

                implicit val system = context.system
                implicit val ec = system.classicSystem.dispatcher
                
                val subSource: Source[String, Future[Done]] = 
                    MqttConnector
                        .subscriberSource(s"SUB_$deviceId", MqttSubscriptions(s"$modulePath/cmd" -> MqttQoS.AtLeastOnce))

                val actorFlow: Flow[DeviceCmd, Done, NotUsed] =
                    ActorFlow
                        .askWithStatus[DeviceCmd, ExecuteCommand, Done](deviceActor)(ExecuteCommand.apply)

                /*
                                +---------------------------------------------------+
                                |   (deviceActor)                                   |
                                |    ^        |                                     |
                                |    |        |                                     |
                                |   Cmd      Ack                                    |
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
