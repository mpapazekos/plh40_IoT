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

object DeviceGroup {

    sealed trait Msg

    final case class NewDevice(devInfo: RegisterInfo, replyTo: ActorRef[Response]) extends Msg
    case object DeviceSaved extends Msg

    // testing purposes
    final case class GetLatestData(deviceIds: Iterable[ActorRef[DeviceRep.Msg]], replyTo: ActorRef[AggregatedData]) extends Msg

    final case class GetLatestDataFrom(deviceIds: Iterable[String], replyTo: ActorRef[AggregatedData]) extends Msg
    final case class AggregatedData(jsonList: List[String]) extends Msg

    final case class SendToDevices(commands: List[CommandInfo], replyTo: ActorRef[StatusReply[String]]) extends Msg

    sealed abstract class Response(deviceId: String) extends Msg
    final case class DeviceCreated(id: String) extends Response(id)
    final case class AlreadyRunning(id: String) extends Response(id)
    final case class CouldNotCreateDevice(id: String, error: String) extends Response(id)


    // actor υπεύθυνος για την διαχείριση μιας ομάδας συσκευών 
    // ως μηνύματα 
    // θα δέχεται ένα με τις πληροφορίες για την κατασκευή μιας νέας συσκευής συσκευής 
    // στο οποιο θα απαντάει με αντίστοιχο μήνυμα μόλις ολοκληρώνεται 
    def apply(groupId: String, buildingId: String): Behavior[Msg] =
        Behaviors.setup(ctx => new DeviceGroup(ctx, groupId, buildingId).mainBehavior())
}

final class DeviceGroup private (context: ActorContext[DeviceGroup.Msg], groupId: String, buildingId: String) {
    
    import DeviceGroup._
    import DeviceRep.{RequestData, DataResponse}

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

    private def spawnDevice(devInfo: RegisterInfo): Either[String, ActorRef[DeviceRep.Msg]] =        
        DeviceTypes
            .getDevice(devInfo.devType, devInfo.devId) match {
                case Right(device) => 
                    val ref = context.spawnAnonymous(DeviceRep(device, devInfo.modulePath, buildingId))
                    Right(ref)
                case Left(error) => Left(error)
            }
        
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

     /**
         * commandsJson =
         * Iterable(
         *     {"deviceId": "deviceId1", "command": { "name": "cmd1", "info": {...} }},
         *     {"deviceId": "deviceId2", "command": { "name": "cmd1", "info": {...} }},
         *     ... 
         * )
         */
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