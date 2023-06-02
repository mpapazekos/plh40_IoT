import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import plh40_iot.domain.CommandInfo
import plh40_iot.domain.DeviceTypes
import plh40_iot.domain.RegisterInfo
import plh40_iot.util.Aggregator

import scala.collection.immutable.HashMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

// Represents a group of devices in a building
object DeviceGroup {

    sealed trait Msg

    /** Sent when a new device is to be registered to group. Responds with appropriate message.*/
    final case class NewDevice(devInfo: RegisterInfo, replyTo: ActorRef[Response]) extends Msg
    
    /** Types of responses when trying to register a new device for this group*/
    sealed abstract class Response(deviceId: String) extends Msg
    final case class DeviceCreated(id: String) extends Response(id)
    final case class AlreadyRunning(id: String) extends Response(id)
    final case class CouldNotCreateDevice(id: String, error: String) extends Response(id)

    /** Used to obtain aggregated data of devices given their ids. Responds with AggregatedData message. */
    final case class GetLatestDataFrom(deviceIds: Iterable[String], replyTo: ActorRef[AggregatedData]) extends Msg
    final case class AggregatedData(jsonList: List[String]) extends Msg

    /** Used to send specific commands to some devices in this group. */
    final case class SendToDevices(commands: List[CommandInfo], replyTo: ActorRef[StatusReply[String]]) extends Msg

   
    /** Creates a new device group actor. */
    def apply(groupId: String, buildingId: String): Behavior[Msg] =
        Behaviors.setup(ctx => new DeviceGroup(ctx, groupId, buildingId).mainBehavior())
}

final class DeviceGroup private (context: ActorContext[DeviceGroup.Msg], groupId: String, buildingId: String) {
    
    import DeviceGroup._
    import DeviceRep.{RequestData, DataResponse}

    // Tracks device actors in group by using a map from deviceId to actorRef
    private var deviceToActor: HashMap[String, ActorRef[DeviceRep.Msg]] = HashMap.empty

    def mainBehavior(): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case NewDevice(devInfo, replyTo) =>
                         
                    if (deviceToActor.contains(devInfo.devId)) {
                        context.log.warn(s"Device ${devInfo.devId} is already running")
                        replyTo ! AlreadyRunning(devInfo.devId)
                    }
                    else {
                        context.log.info(s"Creating new ${devInfo.devType} with id: ${devInfo.devId}") 
                        spawnDevice(devInfo) match {
                            case Left(errorMsg) =>
                                replyTo ! CouldNotCreateDevice(devInfo.devId, errorMsg)
                               
                            case Right(deviceRef) =>
                                replyTo ! DeviceCreated(devInfo.devId)
                                deviceToActor = deviceToActor.updated(devInfo.devId, deviceRef)    
                        }
                    }
                    Behaviors.same 
            
                case GetLatestDataFrom(deviceIds, replyTo) => 
                    context.log.debug("RECEIVED {}", deviceIds.mkString)
                    val deviceRefs =
                        deviceIds.collect { case devId if (deviceToActor.contains(devId)) => deviceToActor(devId) }

                    if (deviceRefs.isEmpty) 
                        replyTo ! AggregatedData(List(s"""{ "error": "No devices found for group $groupId") }"""))
                    else
                        latestDataFrom(deviceRefs, replyTo, 5.seconds)
                    Behaviors.same


                case SendToDevices(commands, replyTo) => 
                    context.log.debug("RECEIVED {}", commands.mkString)
                    sendCommandsToDevices(commands, replyTo, 5.seconds)
                    Behaviors.same
            }

    /** Spawns new device actor depending on the information given.*/
    private def spawnDevice(devInfo: RegisterInfo): Either[String, ActorRef[DeviceRep.Msg]] =        
        DeviceTypes
            .create(devInfo.devType, devInfo.devId) match {
                case Right(device) => 
                    val ref = context.spawnAnonymous(DeviceRep(device, devInfo.modulePath, buildingId))
                    Right(ref)
                case Left(error) => Left(error)
            }

    /** Gathers latest data from given and replies with the aggregated results. */    
    private def latestDataFrom(devices: Iterable[ActorRef[DeviceRep.Msg]], resultReceiver: ActorRef[AggregatedData], timeout: FiniteDuration): Unit = {
        val aggregatorBehavior = 
            Aggregator[DataResponse, AggregatedData](
                sendRequests = (replyTo => for (device <- devices) device ! RequestData(replyTo)),
                expectedReplies = devices.size,
                replyTo =  resultReceiver,
                aggregateReplies = (replies => AggregatedData(replies.map(_.dataJson).toList)), 
                timeout
            )

        context.spawnAnonymous(aggregatorBehavior)
    }

    /** Sends commands given to some devices in this group and replies with a status report. */   
    private def sendCommandsToDevices(commands: Iterable[CommandInfo], resultReceiver: ActorRef[StatusReply[String]], timeout: FiniteDuration): Unit = {
       
        val aggregatorBehavior = 
            Aggregator.statusReplyCollector[String](
                sendRequests = { receiver => 
                    commands.foreach{ devCmd =>
                        val (devId, command) = (devCmd.deviceId, devCmd.command.toString())
                        // find in group
                        if (deviceToActor.contains(devId)) 
                            //send json command info through mqtt to device
                            deviceToActor(devId) ! DeviceRep.PublishCommand(command, receiver)
                        else
                            receiver ! StatusReply.Error(s"Device $devId does not exist")
                    }
                },
                expectedReplies = commands.size,
                replyTo = resultReceiver,
                aggregateReplies = (replies => StatusReply.Success(replies.mkString("\n{",",\n","}\n"))),
                timeout
            )

        context.spawnAnonymous(aggregatorBehavior)
    }
}