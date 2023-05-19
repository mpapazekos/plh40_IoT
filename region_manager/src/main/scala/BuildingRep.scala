
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.CommitterSettings
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Committer
import akka.kafka.scaladsl.Consumer
import plh40_iot.util.KafkaConnector

object BuildingRep {
    
   
    // κανει subscribe σε kafka topic για τα δεδομένα των συσκευών ενός συγκεκριμένου κτηρίου
    // με το που τα λαμβάνει τα προωθεί στο παραπάνω επίπεδο μέσω kafka 
     
    sealed trait Msg 

    def apply(buildingId: String, regionId: String): Behavior[Msg] = 
        Behaviors
            .setup{ context => 

                implicit val system = context.system
               
                val consumerSettings = 
                    KafkaConnector.consumerSettings(
                        clientId = s"$regionId-$buildingId-consumer",
                        groupId  = s"$regionId-consumer"
                    )
                
                val committerSettings = CommitterSettings(system)
  
                Consumer
                    .committableSource(consumerSettings, Subscriptions.topics(s"Data-$buildingId", s"Query-results-$buildingId"))
                    .map { msg => 
                        println("KAFKA CONSUMER RECEIVED VALUE: " + msg.record.value())
                        msg.committableOffset
                    }
                    .toMat(Committer.sink(committerSettings))(Consumer.DrainingControl.apply)
                    .run()

                Behaviors.empty
            } 
}