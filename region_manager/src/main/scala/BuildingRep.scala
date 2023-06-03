package region_manager

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.CommitterSettings
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Committer
import akka.kafka.scaladsl.Consumer
import plh40_iot.util.KafkaConnector
import akka.actor.typed.PreRestart
import akka.actor.typed.PostStop

object BuildingRep {
    
    sealed trait Msg 

    /**
      * Creates an actor represenative of a particular building.
      * Starts a stream connected to a kafka broker which subscribes 
      * in order to receive device data from that building.
      */
    def apply(buildingId: String, regionId: String): Behavior[Msg] = 
        Behaviors
            .setup{ context => 

                implicit val system = context.system
                implicit val ec = system.classicSystem.dispatcher
               
                val consumerSettings = 
                    KafkaConnector.consumerSettings(
                        clientId = s"$regionId-$buildingId-consumer",
                        groupId  = s"$regionId-consumer"
                    )
                
                val committerSettings = CommitterSettings(system)
  
                val drainingControl =
                    Consumer
                        .committableSource(consumerSettings, Subscriptions.topics(s"Data-$buildingId", s"Query-results-$buildingId"))
                        .map { msg => 
                            println("KAFKA CONSUMER RECEIVED: " + msg.record.value())
                            msg.committableOffset
                        }
                        .toMat(Committer.sink(committerSettings))(Consumer.DrainingControl.apply)
                        .run()

                
                Behaviors.receiveSignal {
                    case (_, signal) if signal == PreRestart || signal == PostStop =>
                        drainingControl.drainAndShutdown()
                        Behaviors.same
                }
            } 
}