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

    def running(data: Option[A]): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case NewData(value, replyTo) =>
                    context.log.info(s"Received new data from MQTT: $value")
                    replyTo ! DataReceived(device.id, value)
                    running(Some(value.asInstanceOf[A]))

                case RequestData(replyTo) => 
                    
                    val responseJson = 
                        data match {
                            case None => 
                                s"""|{
                                    |  "deviceId": "${device.id}",
                                    |  "data": "NO DATA YET"
                                    |}"""
                                    .stripMargin
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