
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.CommitterSettings
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Committer
import akka.kafka.scaladsl.Consumer
import plh40_iot.util.KafkaConnector

import scala.concurrent.Future

object BuildingDataHandler {
  

    def apply(regionId: String, buildingId: String, consumerGroup: String): Behavior[Nothing] = 
        Behaviors
            .setup[Nothing]{ context =>

                implicit val system = context.system
               
                val consumerSettings = 
                    KafkaConnector.consumerSettings(s"$regionId-$buildingId-DATA-CONSUMER", consumerGroup)
                
                val committerSettings = CommitterSettings(system)
  
                Consumer
                    .committableSource(consumerSettings, Subscriptions.topics(s"Data-$buildingId"))
                    .mapAsync(4) { msg =>
                        println("KAFKA CONSUMER RECEIVED VALUE: " + msg.record.value())
                        Future.successful(msg.committableOffset)
                    }
                    .toMat(Committer.sink(committerSettings))(Consumer.DrainingControl.apply)
                    .run()

                Behaviors.empty
            }
}