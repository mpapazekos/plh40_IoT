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

    /** Signals that the latest element is emitted into the stream */
     
    sealed trait Event

    case object ReachedEnd extends Event
    case class Publish(message: MqttMessage) extends Event
    case class FailureOccured(ex: Exception) extends Event

    private val connectClient: String => MqttConnectionSettings = {
        (clientId: String) => 
            val config = ConfigFactory.load("mqtt").getConfig("mqtt-connection-settings")

            val (host, port) = (config.getString("hostname"), config.getString("port"))

            MqttConnectionSettings(s"tcp://$host:$port", "IoTManager", new MemoryPersistence)
                .withCleanSession(false)
                .withAutomaticReconnect(false)
                .withClientId(clientId) 
    } 

    private val restartSettings =  
        RestartSettings(1.second, 20.seconds, randomFactor = 0.2d).withMaxRestarts(10, 5.minutes)


    def subscriberSource(clientId: String, subscriptions: MqttSubscriptions)(implicit ec: ExecutionContext): Source[String, Future[Done]] = {
        val sourceFromBroker = 
            MqttSource.atLeastOnce(connectClient(clientId), subscriptions , bufferSize = 10)
                
        Utils
            .wrapWithAsRestartSource(restartSettings, sourceFromBroker)
            .mapAsync(4)(messageWithAck => messageWithAck.ack().map(_ => messageWithAck.message.payload.utf8String))
    }

    def publisherSink(clientId: String): Sink[MqttMessage, Future[Done]] = { 
        
        val sinkToBroker = MqttSink(connectClient(clientId), MqttQoS.atLeastOnce)

        Utils.wrapWithAsRestartSink(restartSettings, sinkToBroker)
    }
        

    def runClientStream(clientId: String, actorSource: Source[Event, ActorRef[Event]])(implicit system: ActorSystem[_]): ActorRef[Event] = {

        val sinkToBroker = 
            MqttConnector.publisherSink(clientId)

        /*
                        +--------------+               +---------------+  
                        |              |               |               |  
              Event ~~> | actorSource ~~> MqttMessage ~~> sinkToBroker |  
                        |              |               |               |  
                        +--------------+               +---------------+  
                        
        */
        val streamActor = 
            actorSource
                .collect { case Publish(msg) => msg }
                .toMat(sinkToBroker)(Keep.left)
                .run()
                
        // Επιστρέφεται ο actor που τοποθετεί τα δεδομένα τύπου Event στη ροή
        streamActor
    }
}

