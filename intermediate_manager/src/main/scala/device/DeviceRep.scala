import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import plh40_iot.domain.DeviceTypes._

import scala.concurrent.duration._
import akka.actor.typed.SupervisorStrategy

//Δεν παράγει δεδομένα 
// τα λαμβάνει μέσω mqtt
// ενημερώνει την κατάστασή του
// και τα προωθεί μέσω kafka 
// ο μηχανισμός για τα queries λείτουργεί διαφορετικά απο τις εντολές 
// οι εντολές στέλνονται ως json κατευθείαν στις συσκευές 
// οι ερωτήσεις μπορούν να απαντηθούν απο τα υπάρχοντα δεδομένα 
// θα πρέπει να υλοποιηθουν 
object DeviceRep {

    sealed trait Msg

    final case class NewData(value: DeviceData, replyTo: ActorRef[DataReceived]) extends Msg
    final case class RequestData(replyTo: ActorRef[DataResponse]) extends Msg
    final case class PublishCommand(cmdJson: String, replyTo: ActorRef[StatusReply[String]]) extends Msg

    final case class DataReceived(id: String, data: DeviceData) extends Msg
    final case class DataResponse(id: String, dataJson: String) extends Msg

    // δυο τρόποι κατασκευής
    
    // απλές συσκευές
    def apply[A <: DeviceData](
        device: GenDevice[A], 
        modulePath: String,
        buildingId: String
    ): Behavior[Msg] = 
        Behaviors
            .setup { context =>
                // subscribe στα /device/deviceid: /data και /cmd/ack ωστε να ενημερώνεται 
                // για τα καινούργια δεδομένα και την αναγνώριση εκτέλεσης μιας εντολής αντίστοιχα 
    
                // publish στο /device/deviceid/cmd για να στέλνει εντολές απο τα παραπάνω επίπεδα

                context.spawn[Nothing](
                    DeviceDataStreamer(device, modulePath, s"Data-$buildingId", context.self), 
                    name = s"SUB_${device.id}"
                )

                new GenDeviceRep(context, device, modulePath).running(None)
            }

    // έξυπνες συσκευές
    def apply[A <: DeviceData, B <: DeviceCmd](
        device: SmartDevice[A, B], 
        modulePath: String,
        buildingId: String
    ): Behavior[Msg] = 
        Behaviors
            .setup { context =>
                // subscribe στα /device/deviceid: /data και /cmd/ack ωστε να ενημερώνεται 
                // για τα καινούργια δεδομένα και την αναγνώριση εκτέλεσης μιας εντολής αντίστοιχα 
    
                // publish στο /device/deviceid/cmd για να στέλνει εντολές απο τα παραπάνω επίπεδα

            
                context.spawn[Nothing](
                    Behaviors.supervise[Nothing](
                        DeviceDataStreamer(device,modulePath, s"Data-$buildingId", context.self) 
                        
                    ).onFailure(SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2d)),
                    name = s"SUB_${device.id}"
                )

                new SmartDeviceRep(context, device, modulePath).running(None)
            }
    
}


