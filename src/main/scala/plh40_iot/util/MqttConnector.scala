package plh40_iot.util

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.stream.RestartSettings
import akka.stream.alpakka.mqtt.MqttConnectionSettings
import akka.stream.alpakka.mqtt.MqttMessage
import akka.stream.alpakka.mqtt.MqttQoS
import akka.stream.alpakka.mqtt.MqttSubscriptions
import akka.stream.alpakka.mqtt.scaladsl.MqttSink
import akka.stream.alpakka.mqtt.scaladsl.MqttSource
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import plh40_iot.util.Utils

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory

object MqttConnector {
 
    sealed trait Event

    // To be sent to actor when the last element to be streamed is communicated.
    case object ReachedEnd extends Event

    // Used to push mqtt message to the stream
    case class Publish(message: MqttMessage) extends Event

    // To be sent to actor when a specific failure has occurred.
    case class FailureOccured(ex: Exception) extends Event

    /**
      * Connects to configured mqtt broker using specific clientId with AtLeastOnce QoS.
      * Creates a MqttSink with restart settings in order to publish messages to broker 
      * using an actor reference created by the provided actorSource. 
      * @param clientId Id used to connect with mqtt broker
      * @param actorSource Definition of an actorSource for obtaining an actor reference after running the stream 
      * @param restartSettings Settings to restart mqtt sink in case of connection failure
      * @param system Actorsystem in which the stream will be materialized
      * @return ActorRef of an actor who publishes mqtt messages to a stream
      */
    def runClientStream(
        clientId: String, 
        actorSource: Source[Event, ActorRef[Event]], 
        restartSettings: RestartSettings = defaultRestartSettings
    )(implicit system: ActorSystem[_]): ActorRef[Event] = {

        val sinkToBroker = 
            publisherSink(clientId, restartSettings)

        val streamActor = 
            actorSource
                .collect { case Publish(msg) => msg }
                .toMat(sinkToBroker)(Keep.left)
                .run()
                
        streamActor
    }

    /**
      * Connects to configured mqtt broker using specific clientId with AtLeastOnce QoS.  
      * Creates a MqttSink with restart settings in order to publish messages to broker.
      * @param clientId Id used to connect with mqtt broker 
      * @param restartSettings Settings to restart mqtt sink in case of connection failure
      * @return Sink which accepts mqtt messages to publish
      */
    def publisherSink(clientId: String, restartSettings: RestartSettings = defaultRestartSettings): Sink[MqttMessage, Future[Done]] = { 
        
        val sinkToBroker: Sink[MqttMessage, Future[Done]] = 
            MqttSink(connectClient(clientId), MqttQoS.atLeastOnce)

        Utils.wrapWithAsRestartSink(restartSettings, sinkToBroker)
    }

    /**
      * Connects to configured mqtt broker using specific clientId with AtLeastOnce QoS.  
      * Subscribes to given topics by creating an MqttSource with restart settings 
      * in order to consume messages from broker. Has a predefined buffersize of 10 messages.
      * Uses an execution context implicitly to send acknowledgements for AtLeastOnce messages 
      * back to the broker with a predefined level of paralellism. 
      * @param clientId Id used to connect with mqtt broker 
      * @param subscriptions MqttSubscriptions for source 
      * @param bufferSize Max number of messages read from MQTT before backpressure applies
      * @param ackParalellism Number of acknowledgements to run in parallel 
      * @param restartSettings Settings to restart mqtt sink in case of connection failure
      * @param ec Implicit execution context used to run acknowledgements in parallel
      * @return Source which produces the utf8 string payload value of incoming mqtt messages
      */
    def subscriberSource(
        clientId: String, 
        subscriptions: MqttSubscriptions, 
        bufferSize: Int = 20, 
        ackParalellism: Int = 4,
        restartSettings: RestartSettings = defaultRestartSettings
    )(implicit ec: ExecutionContext): Source[String, Future[Done]] = {

        val sourceFromBroker = 
            MqttSource
                .atLeastOnce(connectClient(clientId), subscriptions, bufferSize)
                .mapAsync(ackParalellism)(messageWithAck => messageWithAck.ack().map(_ => messageWithAck.message.payload.utf8String))
                
        Utils.wrapWithAsRestartSource(restartSettings, sourceFromBroker)
    }

    /** Default restart settings to be used by an mqtt source or sink */
    private val defaultRestartSettings =  
        RestartSettings(
            minBackoff = 1.second, 
            maxBackoff = 30.seconds, 
            randomFactor = 0.2d
        )
        .withMaxRestarts(count = 10, within = 5.minutes)


    /**
      * Function used to connect to specific mqtt broker defined in configuration.
      * Given a clientId string returns connection settings for that client. 
      */
    private val connectClient: String => MqttConnectionSettings = {
        (clientId: String) => 
            val config = ConfigFactory.load("mqtt").getConfig("mqtt-connection-settings")
            val (host, port) = (config.getString("hostname"), config.getString("port"))
      
            MqttConnectionSettings(s"tcp://$host:$port", "IoTManager", new MemoryPersistence)
                .withCleanSession(false)
                .withAutomaticReconnect(false)
                .withClientId(clientId) 
    }   
}

