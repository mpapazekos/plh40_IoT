
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Producer
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Timeout
import plh40_iot.util.KafkaConnector
import spray.json._

import scala.concurrent.duration.DurationInt
/**
  * Test command 
  * {"buildings":[{"building":"building1","cmdList":{"commands":[{"groupId":"error_group","devices":[]},{"groupId":"module1","devices":[{"deviceId":"4bb28d24","command":{"name":"set","value":38.4}},{"deviceId":"516b0a34","command":{"name":"change-status","value":"discharging"}},{"deviceId":"error_id","command":{}}]},{"groupId":"module2","devices":[{"deviceId":"6a3009c0","command":{"name":"set","value":25.4}},{"deviceId":"7cab08ac","command":{"name":"change-status","value":"discharging"}},{"deviceId":"error_id","command":{}}]}]}},{"building":"building2","cmdList":{"commands":[{"groupId":"error_group","devices":[]},{"groupId":"module1","devices":[{"deviceId":"4bb28d24","command":{"name":"set","value":38.4}},{"deviceId":"516b0a34","command":{"name":"change-status","value":"discharging"}},{"deviceId":"error_id","command":{}}]},{"groupId":"module2","devices":[{"deviceId":"6a3009c0","command":{"name":"set","value":25.4}},{"deviceId":"7cab08ac","command":{"name":"change-status","value":"discharging"}},{"deviceId":"error_id","command":{}}]}]}}]}
  */
object CmdConsumer {
  
    import RegionManager.{SendToBuilding, KafkaRecords}
    /** 
      * 1. H ροή λαμβάνει ένα αντικείμενο json με ερωτήματα για το κάθε κτήριο 
      * 2. αποκτάται η τιμή του πεδίου "buildings" -> (μήνυμα αποτυχημένης προσπάθειας)
      * 3. στέλνει τα αποτελέσματα στον region manager
      * 
      */
    def apply(regionId: String, consumerGroup: String, regionManager: ActorRef[RegionManager.Msg]): Behavior[Nothing] = 
        Behaviors.setup[Nothing] { context => 

            implicit val system = context.system
            implicit val ec = system.classicSystem.dispatcher 
            implicit val timeout: Timeout = 10.seconds

            val committerSettings = CommitterSettings(system)

            val consumerSettings = 
                KafkaConnector.consumerSettings(s"$regionId-cmd-consumer", consumerGroup)
                    
            val producerSettings = 
                KafkaConnector.producerSettings(s"$regionId-cmd-producer")
                    
            val actorFlow =
                ActorFlow
                    .askWithStatusAndContext[Map[String, String], SendToBuilding, KafkaRecords, ConsumerMessage.CommittableOffset](regionManager)(
                        (msg, ackReceiver) => SendToBuilding(msg, "Command", ackReceiver)
                    )
            
            KafkaConnector
                .committableSourceWithOffsetContext(consumerSettings, Subscriptions.topics(s"Command-$regionId"), parseBuildingsJson)
                .via(actorFlow)
                .toMat(Producer.committableSinkWithOffsetContext(producerSettings, committerSettings))(Consumer.DrainingControl.apply)
                .run()

            Behaviors.empty
        }


    /**
     * JSON Format:
      {
       "buildings": [
            {
            "building": "building1", 
            "cmdList": {
                    "commands": [ 
                        { "group": "group1", "devices": [{"deviceId": "deviceId1", "command": { "name": "cmd1", "info": {...} }}, ...] },
                        { "group": "group2", "devices": [{"deviceId": "deviceId1", "command": { "name": "cmd1", "info": {...} }}, ...] },
                        ...
                    ] 
                }     
            },
            ...
        ]
      }
    */
    private def parseBuildingsJson(msg: String): Map[String, String] = {
        
        val fields = 
            msg.parseJson.asJsObject.fields

        val parsed = 
            fields("buildings") match {
                case JsArray(elements) => 
                    elements
                        .map { elem =>  
                            val fields = elem.asJsObject.fields
                            (fields("building").asInstanceOf[JsString].value , fields("cmdList").toString())
                        }  
            }

        parsed.toMap
    }   
}
