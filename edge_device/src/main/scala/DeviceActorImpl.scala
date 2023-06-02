package edge_device

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
import akka.actor.typed.ActorRef


final class SmartDeviceActor[A <: DeviceData, B <: DeviceCmd] (
    ctx: ActorContext[DeviceActor.Msg], 
    device: SmartDevice[A, B], 
    pubTopic: String,
    buildingId: String
) extends GenDeviceActor(ctx, device, pubTopic, buildingId) {

   import DeviceActor._

    protected override def running(state: A): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case Tick =>
                    //on tick generate new data
                    val newData = device.generateData(state)
                    
                    // create new message
                    publishData(newData)
                    
                case ExecuteCommand(cmd, replyTo) => 
                    
                    ctx.log.info(s"RECEIVED COMMAND: $cmd")      
                    val newData = device.execute(cmd.asInstanceOf[B], state)  
                    replyTo ! StatusReply.Ack
                    publishData(newData)

                case Emitted => 
                    ctx.log.debug("message passed downstream")
                    Behaviors.same
            }

}

sealed class GenDeviceActor[A <: DeviceData] (ctx: ActorContext[DeviceActor.Msg], device: GenDevice[A], pubTopic: String, buildingId: String){

    import DeviceActor._
    import MqttConnector._

    import spray.json._
    import plh40_iot.domain.ProtocolJsonFormats.registerInfoFormat

    protected implicit val system = ctx.system
   
    /** Actor used to send mqtt messages for publishing to broker. */
    protected val publisher: ActorRef[Event] = runPublisherStream(bufferSize = 30)

    /** Killswitch in order to kill register stream once the device has successfully registered. */
    protected val registerStreamKillSwitch: UniqueKillSwitch = runRegisterStream(askTimeout = 5.seconds)

    /**
      * Every time a Tick message is received a new message for registration is sent to mqtt.
      * When a new RegisterMsg is received checks the registration status .
      * If sucessfully registered changes behavior to running and begins to send device data,
      * else continues attempts for registration.
      * @param groupId Module group in which device belongs to 
      * @param registerTopic Topic to send mqtt messages for registration.
      */
    def registering(groupId: String, registerTopic: String): Behavior[Msg] = {
        Behaviors
            .receiveMessagePartial {
                case Tick => 
                    val payload = 
                        RegisterInfo(groupId, device.id, device.typeStr, pubTopic).toJson.toString()
                      
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
                            publishData(device.initState)
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

    /**
      * Every time a Tick message is received device data 
      * is generated and published to the mqtt broker.
      * @param state current device data state
      */
    protected def running(state: A): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case Tick =>
                    //on tick generate new data
                    val newData = device.generateData(state)
                    
                    // create new message
                    publishData(newData)

                case Emitted => 
                    ctx.log.debug("message passed downstream")
                    Behaviors.same
            }

    /** Converts given device data to json string and published to mqtt broker. */
    protected def publishData(data: A): Behavior[Msg] = 
        device.toJsonString(data) match {
            case Right(payload) => 
                val mqttMessage = 
                    MqttMessage(pubTopic, ByteString(payload)).withRetained(true)

                //publish to mqtt
                ctx.log.info("PUBLISHING {}", payload)
                publisher ! Publish(mqttMessage)
                running(data)
            case Left(errorMsg) =>
                ctx.log.error(s"Could not create json from: $data, reason: $errorMsg")
                Behaviors.same 
        }
    
    /**
      * Runs a streams for device registration. 
      * Subscribes to mqtt topic for registration results. 
      * When an answer arrives sends result message to this actor, 
      * using askWithStatus pattern.  
      * @param askTimeout how long to wait for device actor reply 
      * @return killswitch for controling specific stream
      */
    protected def runRegisterStream(implicit askTimeout: Timeout): UniqueKillSwitch = {

        implicit val ec = system.classicSystem.dispatcher
    
        val subscriptions = 
            MqttSubscriptions(s"/$buildingId/register/${device.id}", MqttQoS.AtLeastOnce)

        //subsciber
        val subSource = 
            MqttConnector.subscriberSource(s"REG_${device.id}", subscriptions)

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

    /**
      * Creates an actorSource with backpressure with given buffersize.
      * Connects to connects to mqtt broker using the device id to construct the client id.
      * Materializes the created stream.
      * @param bufferSize message limit until backpressure is applied
      * @return actor ref to send mqtt events to the stream
      */
    protected def runPublisherStream(bufferSize: Int): ActorRef[Event] = {

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
                .buffer(bufferSize, OverflowStrategy.backpressure)
                .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))

  
        MqttConnector.runClientStream(s"PUB_${device.id}", actorSource)
    }
}