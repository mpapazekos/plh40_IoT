package region_manager

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage.CommittableOffset
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
      * Creates and handles a stream from a kafka broker 
      * to another kafka broker lower in the hierarchy.
      * Once the data arrives in json format, 
      * it is parsed with a given function, 
      * then sent to the region manager actor to create records, 
      * and finally forwarded to a producer sink.
      * @param regionId Id of system region
      * @param topicPrefix Prefix needed to specify subscription topic
      * @param consumerGroup Kafka consumer group 
      * @param regionManager Region manager actor ref
      * @param parseBuildingsJson Function to parse kafka records for this building
      * @param askTimeout timeout for response when asking region manager 
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
                    .committableSourceWithOffsetContext(
                        consumerSettings, 
                        Subscriptions.topics(s"$topicPrefix-$regionId"), 
                        parseBuildingsJson
                    )
            
            val flowThroughRegionManager =
                ActorFlow
                    .askWithStatusAndContext[BuildingToJsonMap, SendToBuilding, KafkaRecords, CommittableOffset](regionManager)(
                        (msg, ackReceiver) => SendToBuilding(msg, topicPrefix, ackReceiver)
                    )

            val producerSettings = 
                KafkaConnector.producerSettings(s"$regionId-$topicPrefix-Producer")

            val producerSink = 
                Producer.committableSinkWithOffsetContext(producerSettings, committerSettings)
            
            val drainingControl =
                consumerSource
                    .via(flowThroughRegionManager)
                    .toMat(producerSink)(Consumer.DrainingControl.apply)
                    .run()

            Behaviors.receiveSignal {
                    case (_, signal) if signal == PreRestart || signal == PostStop =>
                        drainingControl.drainAndShutdown()
                        Behaviors.same
                }
        }
 
}
