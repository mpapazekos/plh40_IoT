
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Committer
import akka.kafka.scaladsl.Consumer
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Timeout
import plh40_iot.domain.ParsedCommands

import plh40_iot.util.KafkaConnector
import spray.json._

import scala.concurrent.duration.DurationInt

//Test command - topic: Command-{buildingId} 
//{"commands":[{"groupId":"test_group1","devices":[]},{"groupId":"factory","devices":[{"deviceId":"tb1","command":{"name":"set","value":38.4}}]},{"groupId":"room","devices":[{"deviceId":"bb1","command":{"name":"change-status","value":"charging"}}]}]}
object CommandConsumer {
    
    import DeviceManager.SendCommands
 
    /**
      * Connects with a Kafka broker to consume commands for the devices in this building. 
      * When a new query arrives it is assumed to be in json format:
        {
            "commands": [ 
                { "group": "group1", "devices": [{"deviceId": "deviceId1", "command": { "name": "cmd1", "value": {...} }}, ...] },
                { "group": "group2", "devices": [{"deviceId": "deviceId1", "command": { "name": "cmd1", "value": {...} }}, ...] },
                ...
            ] 
        } .
      * Each element is mapped to an object for the DeviceManager.
      * The list of objects is sent in a message and the result is awaited.
      * @param buildingId: Used for connecting to a specific kafka topic
      */
    def apply(buildingId: String, deviceManager: ActorRef[DeviceManager.Msg]): Behavior[Nothing] =
        Behaviors
            .setup[Nothing]{ context =>

                implicit val system = context.system
                implicit val ec = system.classicSystem.dispatcher
                implicit val timeout: Timeout = 20.seconds
                
                val consumerSettings = 
                    KafkaConnector.consumerSettings(s"Command-Consumer-$buildingId",s"$buildingId-consumer")

                val committerSettings = CommitterSettings(context.system)

                val deviceManagerFlow = 
                    ActorFlow
                        .askWithStatusAndContext[ParsedCommands, SendCommands, String, CommittableOffset](deviceManager)(SendCommands.apply)
        
                KafkaConnector
                    .committableSourceWithOffsetContext(consumerSettings, Subscriptions.topics(s"Command-$buildingId"), parseCommands)
                    .via(deviceManagerFlow)
                    .map(result => print(result))
                    .toMat(Committer.sinkWithOffsetContext(committerSettings))(Consumer.DrainingControl.apply)
                    .run()

                Behaviors.empty
            }

    private def parseCommands(json: String): ParsedCommands = {
        
        import plh40_iot.domain.ProtocolJsonFormats.parsedCommandsFormat

        json.parseJson.convertTo[ParsedCommands]
    }          
}
