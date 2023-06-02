
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.PreRestart
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.KillSwitches
import akka.stream.alpakka.mqtt.MqttMessage
import akka.stream.alpakka.mqtt.MqttQoS
import akka.stream.alpakka.mqtt.MqttSubscriptions
import akka.stream.scaladsl.Keep
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.ByteString
import akka.util.Timeout
import plh40_iot.domain.RegisterInfo
import plh40_iot.util.MqttConnector
import plh40_iot.util.Utils
import spray.json._

import scala.concurrent.duration._

object RegisterListener {
  
    sealed trait Msg

    import BuildingManager.RegisterDevice
    import DeviceGroup.{DeviceCreated, AlreadyRunning, CouldNotCreateDevice, Response}

    /**
      * Creates and handles as stream responsible for registering devices to the application
      * through an mqtt broker. Subscribew to specific register topic, informs the building manager
      * to register a new device and publishes the result to a another topic for device to see.
      */
    def apply(buildingId: String,  buildingManager: ActorRef[RegisterDevice])(implicit askTimeout: Timeout = 5.seconds): Behavior[Msg] = 
        Behaviors
            .setup{ context => 

                implicit val system = context.system
                implicit val ec = system.classicSystem.dispatcher

                val subscriptions = 
                    MqttSubscriptions(s"/$buildingId/register", MqttQoS.atLeastOnce)
                
                val subscriberSource =  
                    MqttConnector.subscriberSource(s"DEVICE_LISTENER_$buildingId", subscriptions)

                            
                val flowThroughActor = 
                    ActorFlow
                        .ask[RegisterInfo, RegisterDevice, Response](buildingManager)(RegisterDevice.apply)
                        .map { response =>
                            val (devId, payload) = 
                                response match {
                                    case DeviceCreated(devId) => (devId,"REGISTERED")
                                    case AlreadyRunning(devId) => (devId,"DEVICE ALREADY RUNNING")
                                    case CouldNotCreateDevice(devId, error) => (devId,s"COULD NOT CREATE DEVICE: $error")         
                                }

                            MqttMessage(s"/$buildingId/register/$devId", ByteString(payload))
                        }
                        
                val sinkToBroker = 
                    MqttConnector.publisherSink(s"DEVICE_LISTENER_ACK_$buildingId")

                // Start stream an obtain killSwitch
                val killSwitch =
                    subscriberSource 
                        .mapAsync(4)(json => Utils.parseMessage(json, parseRegisterInfo))
                        .via(Utils.errorHandleFlow())
                        .via(flowThroughActor)
                        .viaMat(KillSwitches.single)(Keep.right)
                        .toMat(sinkToBroker)(Keep.left)
                        .run()
                
                Behaviors.receiveSignal {
                    case (_, signal) if signal == PreRestart || signal == PostStop =>
                        killSwitch.shutdown()
                        Behaviors.same
                }
            }

    private def parseRegisterInfo(json: String): RegisterInfo = {
     
        import plh40_iot.domain.ProtocolJsonFormats.registerInfoFormat

        json.parseJson.convertTo[RegisterInfo]
    }

}
