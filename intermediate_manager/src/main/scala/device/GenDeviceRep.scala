import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import plh40_iot.domain.DeviceTypes._


final class GenDeviceRep[A <: DeviceData](
    context: ActorContext[DeviceRep.Msg], 
    device: GenDevice[A], 
    modulePath: String
) {
    
    import DeviceRep._

    implicit val system = context.system
    implicit val ec = system.classicSystem.dispatcher

    /**
      * General device rep behavior.
      * When new data is received updates current state and responds to sender.
      * When current state is requested it is converted to json format and passed through response message.
      * When a message for publishing command arrives, a response with error message is replied back.
      * @param data latest data received from device
      */
    def running(data: Option[A]): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case NewData(value, replyTo) =>
                    context.log.info(s"Received from MQTT: $value")
                    replyTo ! DataReceived(device.id, value)
                    running(Some(value.asInstanceOf[A]))

                case RequestData(replyTo) => 
                    
                    val responseJson = 
                        data match {
                            case None => 
                                s""" { "deviceId": "${device.id}", "data": "NO DATA YET" }"""
                                
                            case Some(value) => 
                                device.toJsonString(value.asInstanceOf[A]) match {
                                    case Left(error) => s"""{ "error": "FAILED TO GET LATEST DATA: $error "}"""
                                    case Right(json) => json
                                }   
                        }

                    context.log.info(s"Sending data to: $replyTo")
                    replyTo ! DataResponse(device.id, responseJson)
                    Behaviors.same 

                case PublishCommand(cmdJson, replyTo) => 
                    replyTo ! StatusReply.Error(s"Failed to publish command $cmdJson for ${device.id}. Reason: Function not Supported")
                    Behaviors.same
            }

}