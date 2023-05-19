
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

import scala.concurrent.duration.DurationInt
import akka.actor.typed.PreRestart
import akka.actor.typed.PostStop


object BuildingInfoConsumer {
  
    sealed trait Msg

    import RegionManager.{SendToBuilding, KafkaRecords, BuildingToJsonMap}
    /** 
      * 1. H ροή λαμβάνει ένα αντικείμενο json με ερωτήματα για το κάθε κτήριο 
      * 2. αποκτάται η τιμή του πεδίου "buildings" -> (μήνυμα αποτυχημένης προσπάθειας)
      * 3. στέλνει τα αποτελέσματα στον region manager
      * 
      */
    def apply(
        regionId: String,
        topicPrefix: String, 
        consumerGroup: String, 
        regionManager: ActorRef[RegionManager.Msg],
        parseBuildingsJson: String => BuildingToJsonMap
    )(implicit askTimeout: Timeout = 10.seconds): Behavior[Msg] = 
        Behaviors.setup { context => 

            implicit val system = context.system
            implicit val ec = system.classicSystem.dispatcher 

            val committerSettings = CommitterSettings(system)

            val consumerSettings = 
                KafkaConnector.consumerSettings(s"$regionId-$topicPrefix-Consumer", consumerGroup)
            
            val consumerSource = 
                KafkaConnector
                    .committableSourceWithOffsetContext(consumerSettings, Subscriptions.topics(s"$topicPrefix-$regionId"), parseBuildingsJson)
            
            val flowThroughActor =
                ActorFlow
                    .askWithStatusAndContext[BuildingToJsonMap, SendToBuilding, KafkaRecords, ConsumerMessage.CommittableOffset](regionManager)(
                        (msg, ackReceiver) => SendToBuilding(msg, topicPrefix, ackReceiver)
                    )

            val producerSettings = 
                KafkaConnector.producerSettings(s"$regionId-$topicPrefix-Producer")

            val producerSink = 
                Producer.committableSinkWithOffsetContext(producerSettings, committerSettings)
            
            val drainingControl =
                consumerSource
                    .via(flowThroughActor)
                    .toMat(producerSink)(Consumer.DrainingControl.apply)
                    .run()

            Behaviors.receiveSignal {
                    case (_, signal) if signal == PreRestart || signal == PostStop =>
                        drainingControl.drainAndShutdown()
                        Behaviors.same
                }
        }
 
}
