
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
import akka.actor.typed.PreRestart
import akka.actor.typed.PostStop

//Test command - topic: Command-{buildingId} 
//{"commands":[{"groupId":"test_group1","devices":[]},{"groupId":"factory","devices":[{"deviceId":"tb1","command":{"name":"set","value":38.4}}]},{"groupId":"room","devices":[{"deviceId":"bb1","command":{"name":"change-status","value":"charging"}}]}]}
object CmdConsumer {
    
    sealed trait Msg
    
    import BuildingManager.SendCommands
 
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
      * Each element is mapped to an object for the BuildingManager.
      * The list of objects is sent in a message and the result is awaited.
      * @param buildingId: Used for connecting to a specific kafka topic
      */
    def apply(buildingId: String, buildingManager: ActorRef[BuildingManager.Msg]): Behavior[Msg] =
        Behaviors
            .setup{ context =>

                implicit val system = context.system
                implicit val ec = system.classicSystem.dispatcher
                implicit val timeout: Timeout = 20.seconds
                
                val consumerSettings = 
                    KafkaConnector.consumerSettings(s"Command-Consumer-$buildingId",s"$buildingId-consumer")

                val committerSettings = CommitterSettings(context.system)

                val buildingManagerFlow = 
                    ActorFlow
                        .askWithStatusAndContext[ParsedCommands, SendCommands, String, CommittableOffset](buildingManager)(SendCommands.apply)
        
                val drainingControl =
                    KafkaConnector
                        .committableSourceWithOffsetContext(consumerSettings, Subscriptions.topics(s"Command-$buildingId"), parseCommands)
                        .via(buildingManagerFlow)
                        .map(result => print(result))
                        .toMat(Committer.sinkWithOffsetContext(committerSettings))(Consumer.DrainingControl.apply)
                        .run()

                Behaviors.receiveSignal {
                    case (_, signal) if signal == PreRestart || signal == PostStop =>
                        drainingControl.drainAndShutdown()
                        Behaviors.same
                }
            }

    private def parseCommands(json: String): ParsedCommands = {
        
        import plh40_iot.domain.ProtocolJsonFormats.parsedCommandsFormat

        json.parseJson.convertTo[ParsedCommands]
    }          
}
