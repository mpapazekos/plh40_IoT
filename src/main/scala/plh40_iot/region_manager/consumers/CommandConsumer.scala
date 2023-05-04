package plh40_iot.region_manager.consumers

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
import plh40_iot.region_manager.RegionManager
import plh40_iot.util.KafkaConnector
import spray.json._

import scala.concurrent.duration.DurationInt
/**
  * Test command 
  * {"buildings":[{"building":"building1","cmdList":{"commands":[{"group":"test_group","devices":[{"deviceId":"fc5d8e11-f44e-400f-ab65-d85c2fd958c1","command":{"name":"set","value":38.4}},{"deviceId":"6874cd0f-a7e4-4d2f-85e6-dc1ddd37a75b","command":{"name":"set","value":34.4}}]}]}}]}
  */
object CommandConsumer {
  
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
        
        val buildings = 
            msg.parseJson.asJsObject.getFields("buildings").head

        val parsed = 
            buildings
                .asInstanceOf[JsArray]
                .elements
                .map { elem =>  
                    val fields = elem.asJsObject.fields
                    (fields("building").asInstanceOf[JsString].value , fields("cmdList").toString())
                }      
                
        parsed.toMap
    }   
}
