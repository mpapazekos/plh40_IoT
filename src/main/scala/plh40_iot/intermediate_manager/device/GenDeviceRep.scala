package plh40_iot.intermediate_manager.device

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import plh40_iot.domain.DeviceTypes._
import spray.json.JsString

final class GenDeviceRep[A <: DeviceData](
    context: ActorContext[DeviceRep.Msg], 
    device: GenDevice[A], 
    deviceId: String, 
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
                    replyTo ! DataReceived(deviceId, value)
                    running(Some(value.asInstanceOf[A]))

                case RequestData(replyTo) => 
                    
                    val responseJson = 
                        data match {
                            case None => 
                                s"""|{
                                    |  "deviceId": "$deviceId",
                                    |  "data": "NO DATA YET"
                                    |}"""
                                    .stripMargin
                            case Some(value) => 
                                val parsed = device.toJsonString(value.asInstanceOf[A])
                                val dataRes = parsed.getOrElse(JsString("FAILED TO GET LATEST  DATA"))
                                s"""{
                                    |"deviceId": "$deviceId",
                                    |"data": $dataRes
                                    |}"""
                                    .stripMargin
                        }

                    context.log.info(s"Sending data to: $replyTo")
                    replyTo ! DataResponse(deviceId, responseJson)
                    Behaviors.same 

                case PublishCommand(cmdJson, replyTo) => 
                    replyTo ! StatusReply.Error(s"Failed to publish command $cmdJson for $deviceId. Reason: Function not Supported")
                    Behaviors.same
            }

}