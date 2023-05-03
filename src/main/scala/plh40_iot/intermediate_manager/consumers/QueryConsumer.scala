package plh40_iot.intermediate_manager.consumers

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.ProducerMessage
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Producer
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Timeout
import org.apache.kafka.clients.producer.ProducerRecord
import plh40_iot.util.KafkaConnector
import spray.json._

import scala.concurrent.duration.DurationInt

import plh40_iot.intermediate_manager.DeviceManager
import plh40_iot.domain.ParsedQuery

    /**
     * { "queryId": "query1", "groups": [ { "group": "test_group", "devices": ["error_id","fc5d8e11-f44e-400f-ab65-d85c2fd958c1","6874cd0f-a7e4-4d2f-85e6-dc1ddd37a75b"]}]}
     */


// είναι υπεύθυνος για την απόκτηση queries για απο έναν kafka broker 
// κάθε query αφορά την απόκτηση των τελευταίων δεδομένων μιας ή περισσότερων συσκευών 
// θα πρέπει να περιέχει επαρκείς πληροφορίες ώστε να μπορεί να βρεθεί και να ερωτηθεί ο αντίστοιχος actor με τα δεδομένα
// με το που εκτελεστεί ένα ερώτημα επιτυχως τα αποτελέσματα προωθούνται στον kafka broker πάλι
object QueryConsumer {
    
    import DeviceManager.{QueryDevices, AggregatedResults}
 
    /**
      * Connects with a Kafka broker to consume queries for the devices in this building. 
      * When a new query arrives it is assumed to be in json format:
        {
            "queryId": "query1",
            "groups": [
                { "group": "group1", "devices": ["deviceId1", "deviceId2", ... ] },
                { "group": "group2", "devices": ["deviceId1", "deviceId2", ... ] },
                ...
            ]
        }.
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
                    KafkaConnector.localConsumerSettings(s"Query-Consumer-$buildingId",s"$buildingId-consumer")

                val producerSettings = 
                    KafkaConnector.localProducerSettings(s"Query-Producer-$buildingId")

                val committerSettings = CommitterSettings(context.system)

                val deviceManagerFlow = 
                    ActorFlow
                        .askWithContext[ParsedQuery, QueryDevices, AggregatedResults, CommittableOffset](deviceManager)(QueryDevices.apply)
        
                KafkaConnector
                    .committableSourceWithOffsetContext(consumerSettings, Subscriptions.topics(s"Query-$buildingId"), parseGroups)
                    .via(deviceManagerFlow)
                    .map { aggResults =>

                        println(s"\nAGGREGATED: ${aggResults.resultsJson}")
                        
                        ProducerMessage.single(new ProducerRecord[String, String](s"Query-results-$buildingId", aggResults.resultsJson))
                    }
                    .toMat(Producer.committableSinkWithOffsetContext(producerSettings, committerSettings))(Consumer.DrainingControl.apply)
                    .run()

                Behaviors.empty
            }


    private def parseGroups(json: String): ParsedQuery = {

      import plh40_iot.domain.ProtocolJsonFormats.parsedQueryFormat

      json.parseJson.convertTo[ParsedQuery]  
    }             
}