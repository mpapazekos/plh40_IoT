
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.CommitterSettings
import akka.kafka.scaladsl.Committer
import akka.kafka.scaladsl.Consumer
import plh40_iot.util.KafkaConnector

import akka.kafka.Subscription

object BuildingStreamHandler  {
  

    def apply(clientId: String, consumerGroup: String, subscription: Subscription): Behavior[Nothing] = 
        Behaviors
            .setup[Nothing]{ context =>

                implicit val system = context.system
               
                val consumerSettings = 
                    KafkaConnector.consumerSettings(clientId, consumerGroup)
                
                val committerSettings = CommitterSettings(system)
  
                Consumer
                    .committableSource(consumerSettings, subscription)
                    .map { msg => 
                        println("KAFKA CONSUMER RECEIVED VALUE: " + msg.record.value())
                        msg.committableOffset
                    }
                    .toMat(Committer.sink(committerSettings))(Consumer.DrainingControl.apply)
                    .run()

                Behaviors.empty
            }
}