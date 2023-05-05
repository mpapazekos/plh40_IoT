
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors

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
                        .supervise[Nothing](BuildingDataHandler(regionId, buildingId, s"$regionId-data-consumer"))
                        .onFailure[Exception](SupervisorStrategy.restartWithBackoff(0.5.seconds, 10.seconds, 0.2))
                    )

                context.spawnAnonymous[Nothing](
                    Behaviors
                        .supervise[Nothing](BuildingQryHandler(regionId, buildingId, s"$regionId-qry-consumer"))
                        .onFailure[Exception](SupervisorStrategy.restartWithBackoff(0.5.seconds, 10.seconds, 0.2))
                    )

                Behaviors.empty
            }

    
}