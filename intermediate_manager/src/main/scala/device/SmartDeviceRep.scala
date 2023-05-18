import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import akka.stream.alpakka.mqtt.MqttMessage
import akka.stream.scaladsl.Source
import akka.util.ByteString
import plh40_iot.domain.DeviceTypes._
import plh40_iot.util.MqttConnector

import scala.util.Failure
import scala.util.Success

final class SmartDeviceRep[A <: DeviceData, B <: DeviceCmd](
    context: ActorContext[DeviceRep.Msg], 
    device: SmartDevice[A, B], 
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
                    
                    val response = 
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
                    replyTo ! DataResponse(device.id, response)
                    Behaviors.same 

                case PublishCommand(cmdJson, replyTo) => 
                    val mqttSink = 
                        MqttConnector.publisherSink(s"IoT_PUB_CMD_${device.id}")

                    val result =
                        Source
                            .single(MqttMessage(s"$modulePath/cmd", ByteString(cmdJson)))
                            .runWith(mqttSink)

                    result.onComplete {
                        case Success(_) => replyTo ! StatusReply.Success(s"Command published sucessfully for ${device.id}")
                        case Failure(exception) => replyTo ! StatusReply.Error(s"Failed to publish command $cmdJson for ${device.id}. Reason: $exception")
                    }

                    Behaviors.same
            }

}