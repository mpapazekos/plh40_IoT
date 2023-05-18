
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors

import akka.kafka.Subscriptions

import scala.concurrent.duration._

object BuildingRep {
    
   
    // κανει subscribe σε kafka topic για τα δεδομένα των συσκευών ενός συγκεκριμένου κτηρίου
    // με το που τα λαμβάνει τα προωθεί στο παραπάνω επίπεδο μέσω kafka 
     
    sealed trait Msg 

    def apply(buildingId: String, regionId: String): Behavior[Msg] = 
        Behaviors
            .setup{ context => 

                context.spawnAnonymous[Nothing](
                    Behaviors
                        .supervise[Nothing](
                            BuildingStreamHandler(
                                clientId      = s"$regionId-$buildingId-data-consumer",
                                consumerGroup = s"$regionId-data-consumer",
                                subscription  = Subscriptions.topics(s"Data-$buildingId")
                            )
                        )
                        .onFailure[Exception](SupervisorStrategy.restartWithBackoff(0.5.seconds, 10.seconds, 0.2))
                    )

                context.spawnAnonymous[Nothing](
                    Behaviors
                        .supervise[Nothing](
                            BuildingStreamHandler(
                                clientId      = s"$regionId-$buildingId-query-consumer",
                                consumerGroup = s"$regionId-query-consumer",
                                subscription  = Subscriptions.topics(s"Query-results-$buildingId")
                            )
                        )
                        .onFailure[Exception](SupervisorStrategy.restartWithBackoff(0.5.seconds, 10.seconds, 0.2))
                    )

                Behaviors.empty
            } 
}