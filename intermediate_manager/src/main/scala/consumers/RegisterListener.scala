
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.alpakka.mqtt.MqttMessage
import akka.stream.alpakka.mqtt.MqttQoS
import akka.stream.alpakka.mqtt.MqttSubscriptions
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.ByteString
import akka.util.Timeout
import plh40_iot.util.MqttConnector
import plh40_iot.util.Utils
import spray.json._

import scala.concurrent.duration._


import plh40_iot.domain.RegisterInfo
import akka.actor.typed.PreRestart
import akka.actor.typed.PostStop
import akka.stream.KillSwitches
import akka.stream.scaladsl.Keep

object RegisterListener {
  
    sealed trait Msg

    import BuildingManager.RegisterDevice
    import DeviceGroup.{DeviceCreated, AlreadyRunning, CouldNotCreateDevice, Response}

    // Ξεκινάει μια ροή δεδομένων στην οποία τα δεδομένα εισέρχονται απο μια πηγή mqtt 
    // επεξεργάζονται έπειτα απο τον device manager, με τα αποτελέσματα να προωθούνται σε μια δεξαμενή mqtt 
    // Η πηγή κάνει εγγραφή στο topic με αναγνωριστικό "/register". 
    // Περιμένει κάθε φορά τα στοιχεία μιας καινούργιας συσκευής που θα διαχειρίζεται σε αυτό το κτήριο 
    // σε μορφή json. Εφόσον τα στοιχεία είναι επαρκή κατασκευάζεται ο αντίστοιχος actor 
    // υπεύθυνος για τα δεδομένα αυτά και δίνεταi ως απάντηση ένα μήνυμα επιτυχίας. 
    // Σε περίπτωση που δεν είναι δυνατή η επεξεργασία των δεδομένων στέλνεται αντίστοιχο μήνυμα αποτυχίας
    // πίσω στη συσκευή.
    def apply(buildingId: String,  buildingManager: ActorRef[RegisterDevice]): Behavior[Msg] = 
        Behaviors
            .setup{ context => 

                implicit val system = context.system
                implicit val ec = system.classicSystem.dispatcher
                implicit val timeout: Timeout = 5.seconds

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

                // receive message with acknowledgement
                // signal arrival to mqtt broker and map to message payload if successfull 
                // having a string payload try to parse json in order to obtain register info
                // if successfull send message to manager for device creation
                // and update edge device by publishing result to mqtt broker
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
