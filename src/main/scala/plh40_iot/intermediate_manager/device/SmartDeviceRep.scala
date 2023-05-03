package plh40_iot.intermediate_manager.device

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import akka.stream.alpakka.mqtt.MqttMessage
import akka.stream.alpakka.mqtt.MqttQoS
import akka.stream.scaladsl.Source
import akka.util.ByteString
import plh40_iot.domain.DeviceTypes._
import plh40_iot.util.MqttConnector
import spray.json.JsString

import scala.util.Failure
import scala.util.Success

final class SmartDeviceRep[A <: DeviceData, B <: DeviceCmd](
    context: ActorContext[DeviceRep.Msg], 
    device: SmartDevice[A, B], 
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
                    
                    val response = 
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
                    replyTo ! DataResponse(deviceId, response)
                    Behaviors.same 

                case PublishCommand(cmdJson, replyTo) => 
                    val mqttSink = 
                        MqttConnector.publisherSink(s"IoT_PUB_CMD_$deviceId")

                    val result =
                        Source
                            .single(MqttMessage(s"$modulePath/cmd", ByteString(cmdJson)).withQos(MqttQoS.atLeastOnce))
                            .runWith(mqttSink)

                    result.onComplete {
                        case Success(_) => replyTo ! StatusReply.Success(s"Command published sucessfully for $deviceId")
                        case Failure(exception) => replyTo ! StatusReply.Error(s"Failed to publish command $cmdJson for $deviceId. Reason: $exception")
                    }

                    Behaviors.same
            }

}