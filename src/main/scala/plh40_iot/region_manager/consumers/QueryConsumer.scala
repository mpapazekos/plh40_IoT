package plh40_iot.region_manager.consumers

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.CommitterSettings
import plh40_iot.util.KafkaConnector
import akka.stream.typed.scaladsl.ActorFlow
import akka.kafka.ConsumerMessage
import akka.actor.typed.ActorRef
import akka.util.Timeout

import scala.concurrent.duration.DurationInt
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Producer
import akka.kafka.scaladsl.Consumer

import spray.json._

import plh40_iot.region_manager.RegionManager

object RegionQueryActor {
  
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
                KafkaConnector.localConsumerSettings(s"$regionId-query-consumer", consumerGroup)
                    
            val producerSettings = 
                KafkaConnector.localProducerSettings(s"$regionId-query-producer")
                    
            val actorFlow =
                ActorFlow
                    .askWithStatusAndContext[Map[String, String], SendToBuilding, KafkaRecords, ConsumerMessage.CommittableOffset](regionManager)(
                        (msg, ackReceiver) => SendToBuilding(msg, "Query", ackReceiver)
                    )
            
            
            KafkaConnector
                .committableSourceWithOffsetContext(consumerSettings, Subscriptions.topics(s"Query-$regionId"), parseBuildingsJson)
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
            "groupList": {
                    "queryId": "query1",
                    "groups": [ 
                        { "group": "group1", "devices": ["deviceId1", "deviceId2", ...] },
                        { "group": "group2", "devices": ["deviceId1", "deviceId2", ...] },
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
                    (fields("building").asInstanceOf[JsString].value , fields("groupList").toString())
                }      
                
        parsed.toMap
    }   
}
