
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.alpakka.mqtt.MqttQoS
import akka.stream.alpakka.mqtt.MqttSubscriptions
import akka.stream.scaladsl.Keep
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Timeout
import org.apache.kafka.clients.producer.ProducerRecord
import plh40_iot.domain.DeviceTypes._
import plh40_iot.util.KafkaConnector
import plh40_iot.util.MqttConnector
import plh40_iot.util.Utils

import scala.concurrent.duration._
import akka.stream.ActorAttributes
import akka.stream.Supervision

object DeviceDataStreamer {

    import DeviceRep.{NewData, DataReceived}

    // είναι υπεύθυνος για την απόκτηση δεδομένων απο έναν mqtt broker 
    // με το που τα λάβει επιτυχώς αναβαθμίζει την κατάσταση του κυρίως actor
    // τα προωθεί στο kafka topic για το συγκεκριμένο κτήριο

    def apply[A <: DeviceData](device: GenDevice[A], mqttSubTopic: String, kafkaPubTopic: String, askRef: ActorRef[NewData]): Behavior[Nothing] = 
        Behaviors
            .setup[Nothing] { context => 

                implicit val system = context.system
                implicit val ec = system.classicSystem.dispatcher
                implicit val timeout: Timeout = 5.seconds

                // mqtt subscriber 
                val subscriptions = 
                    MqttSubscriptions(mqttSubTopic -> MqttQoS.AtLeastOnce)

                val subscriberSource = 
                    MqttConnector.subscriberSource(s"IoT_SUB_${device.id}", subscriptions)


                // Οταν λαμβάνεται μια καινούργια μέτρηση απο εναν mqtt broker 
                // ενημερώνεται αρχικά ο actor υπέθυνος για την συσκευή 
                // και ύστερα προωθείται σε έναν kafka broker
                val flowThroughActor = 
                    ActorFlow
                        .ask[DeviceData, NewData, DataReceived](askRef)(NewData.apply)
                        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))

                val producerSettings = 
                    KafkaConnector.producerSettings(s"Data-Producer-${device.id}")

                val kafkaRestartSink = 
                    KafkaConnector.plainRestartProducer(producerSettings)

                subscriberSource
                    .map(device.fromJsonString)
                    .via(Utils.errorHandleFlow())
                    .viaMat(flowThroughActor)(Keep.left)
                    .map(received => device.toJsonString(received.data.asInstanceOf[A]))
                    .collect{ case Right(json) => new ProducerRecord[String, String](kafkaPubTopic, json) }
                    .runWith(kafkaRestartSink)
                
                Behaviors.empty
            }

}
