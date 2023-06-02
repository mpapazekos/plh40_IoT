
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.ActorAttributes
import akka.stream.Supervision
import akka.stream.alpakka.mqtt.MqttQoS
import akka.stream.alpakka.mqtt.MqttSubscriptions
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Timeout
import org.apache.kafka.clients.producer.ProducerRecord
import plh40_iot.domain.DeviceTypes._
import plh40_iot.util.KafkaConnector
import plh40_iot.util.MqttConnector
import plh40_iot.util.Utils

import scala.concurrent.duration._

object DeviceDataStreamer {

    import DeviceRep.{NewData, DataReceived}

    /**
      * Creates a stream responsible for getting device data 
      * through an mqtt broker and publishing them to a kafka broker.
      * @param device current device instance
      * @param mqttSubTopic mqtt topic to subscribe to 
      * @param kafkaPubTopic kafka topic to publish data
      * @param deviceRep  actor keeping the latest data to inform when getting new data
      * @param askTimeout timeout period for asking deviceRep 
      */
    def apply[A <: DeviceData](
        device: GenDevice[A], 
        mqttSubTopic: String, 
        kafkaPubTopic: String, 
        deviceRep: ActorRef[NewData]
    )(implicit askTimeout: Timeout = 5.seconds): Behavior[Nothing] = 
        Behaviors
            .setup[Nothing] { context => 

                implicit val system = context.system
                implicit val ec = system.classicSystem.dispatcher

                val subscriptions = 
                    MqttSubscriptions(mqttSubTopic -> MqttQoS.AtLeastOnce)

                val subscriberSource = 
                    MqttConnector.subscriberSource(s"IoT_SUB_${device.id}", subscriptions)

           
                val flowThroughDeviceRep = 
                    ActorFlow
                        .ask[DeviceData, NewData, DataReceived](deviceRep)(NewData.apply)
                        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))

                val producerSettings = 
                    KafkaConnector.producerSettings(s"Data-Producer-${device.id}")

                val kafkaRestartSink = 
                    KafkaConnector.plainRestartProducer(producerSettings)

                subscriberSource
                    .map(device.fromJsonString)
                    .via(Utils.errorHandleFlow())
                    .via(flowThroughDeviceRep)
                    .map(received => device.toJsonString(received.data.asInstanceOf[A]))
                    .collect{ case Right(json) => new ProducerRecord[String, String](kafkaPubTopic, json) }
                    .to(kafkaRestartSink)
                    .run()
                
                Behaviors.empty
            }

}
