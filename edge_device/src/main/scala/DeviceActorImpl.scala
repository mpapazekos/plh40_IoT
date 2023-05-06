
import akka.Done
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import akka.stream.ActorAttributes
import akka.stream.CompletionStrategy
import akka.stream.KillSwitches
import akka.stream.OverflowStrategy
import akka.stream.Supervision
import akka.stream.UniqueKillSwitch
import akka.stream.alpakka.mqtt.MqttMessage
import akka.stream.alpakka.mqtt.MqttQoS
import akka.stream.alpakka.mqtt.MqttSubscriptions
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.typed.scaladsl.ActorFlow
import akka.stream.typed.scaladsl.ActorSource
import akka.util.ByteString
import akka.util.Timeout
import plh40_iot.domain.DeviceTypes._
import plh40_iot.util.MqttConnector

import scala.concurrent.duration._
import plh40_iot.domain.RegisterInfo


final class SmartDeviceActor[A <: DeviceData, B <: DeviceCmd] (
    ctx: ActorContext[DeviceActor.Msg], 
    device: SmartDevice[A, B], 
    modulePath: String,
    buildingId: String
) extends GenDeviceActor(ctx, device, modulePath, buildingId) {

   import DeviceActor._

    protected override def running(state: A): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case Tick =>
                    //on tick generate new data
                    val newState = device.generateData(state)
                    
                    // create new message
                    publishData(newState)
                    
                case ExecuteCommand(cmd, replyTo) => 
                    
                    ctx.log.info(s"RECEIVED COMMAND: $cmd")      
                    val newState = device.execute(cmd.asInstanceOf[B], state)  
                    replyTo ! StatusReply.Ack
                    publishData(newState)

                case Emitted => 
                    ctx.log.debug("message passed downstream")
                    Behaviors.same
            }

}


sealed class GenDeviceActor[A <: DeviceData] (ctx: ActorContext[DeviceActor.Msg], device: GenDevice[A], modulePath: String,  buildingId:  String){

    import DeviceActor._
    import MqttConnector._

    import spray.json._
    import plh40_iot.domain.ProtocolJsonFormats.registerInfoFormat

    protected implicit val system = ctx.system
   
    // publisher
    protected val publisher = runPublisherStream()

    protected val registerStreamKillSwitch = runRegisterStream()

    def registering(groupId: String, registerTopic: String): Behavior[Msg] = {
        Behaviors
            .receiveMessagePartial {
                case Tick => 
                    val payload = 
                        RegisterInfo(groupId, device.id, device.typeStr, modulePath).toJson.toString()
                      
                    val mqttMessage = 
                        MqttMessage(registerTopic, ByteString(payload))

                    publisher ! Publish(mqttMessage)
                    Behaviors.same

                case RegisterMsg(info, replyTo) =>
                    info match {
                        case "REGISTERED" | "DEVICE ALREADY RUNNING" => 
                            ctx.log.info("REGISTERED {}", device.id)
                            replyTo ! StatusReply.Ack
                            registerStreamKillSwitch.shutdown()
                            running(device.initState)
                        case msg: String => 
                            ctx.log.warn(msg)
                            replyTo ! StatusReply.Ack
                            Behaviors.same
                    }

                case Emitted => 
                    ctx.log.debug("message passed downstream")
                    Behaviors.same
            }
    }

    protected def running(state: A): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case Tick =>
                    //on tick generate new data
                    val newState = device.generateData(state)
                    
                    // create new message
                    publishData(newState)

                case Emitted => 
                    ctx.log.debug("message passed downstream")
                    Behaviors.same
            }

    protected def publishData(data: A): Behavior[Msg] = 
        device.toJsonString(data) match {
            case Right(payload) => 
                val mqttMessage = 
                    MqttMessage(modulePath, ByteString(payload))
                        .withQos(MqttQoS.AtLeastOnce)
                        .withRetained(true)
                //publish to mqtt
                ctx.log.info("PUBLISHING {}", payload)
                publisher ! Publish(mqttMessage)
                running(data)
            case Left(errorMsg) =>
                ctx.log.error(s"Could not create json from: $data, reason: $errorMsg")
                Behaviors.same 
        }
    
    protected def runRegisterStream(): UniqueKillSwitch = {

        implicit val ec = system.classicSystem.dispatcher
        implicit val timeout: Timeout = 5.seconds
    
        //subsciber
        val subSource = 
            MqttConnector.subscriberSource(s"REG_${device.id}", MqttSubscriptions(s"$buildingId/register/${device.id}", MqttQoS.AtLeastOnce))

        val actorFlow =
            ActorFlow.askWithStatus[String, RegisterMsg, Done](ctx.self)(RegisterMsg.apply)

        val killSwitch = 
            subSource
                .viaMat(KillSwitches.single)(Keep.right)
                .via(actorFlow)
                .toMat(Sink.ignore)(Keep.left)
                .run()

        killSwitch
    }

    protected def runPublisherStream() = {

        val actorSource =
            ActorSource
                .actorRefWithBackpressure[Event, Emitted.type](
                    // get demand signalled to this actor receiving Ack
                    ackTo = ctx.self, 
                    ackMessage = Emitted,
                    // complete when we send ReachedEnd
                    completionMatcher = { case ReachedEnd => CompletionStrategy.draining},
                    failureMatcher = { case FailureOccured(ex) => ex }
                )
                .buffer(30, OverflowStrategy.backpressure)
                .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))

  
        MqttConnector.runClientStream(s"PUB_${device.id}", actorSource)
    }
}