import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply
import plh40_iot.domain.ParsedCommands
import plh40_iot.domain.ParsedQuery
import plh40_iot.domain.RegisterInfo
import plh40_iot.util.Aggregator

import scala.collection.immutable.HashMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

object BuildingManager {

    type GroupToJsonInfo = Map[String, Iterable[String]]

    sealed trait Msg

    final case class RegisterDevice(info: RegisterInfo, replyTo: ActorRef[DeviceGroup.Response]) extends Msg

    final case class QueryDevices(parsedQuery: ParsedQuery, replyTo: ActorRef[AggregatedResults]) extends Msg
    final case class AggregatedResults(resultsJson: String) extends Msg

    final case class SendCommands(parsedCmds: ParsedCommands, replyTo: ActorRef[StatusReply[String]]) extends Msg
   
    def apply(buildingId: String): Behavior[Msg] = 
       Behaviors.setup(ctx => new BuildingManager(ctx, buildingId).mainBehavior())  
}


final class BuildingManager private (context: ActorContext[BuildingManager.Msg], buildingId: String) {

    import BuildingManager._
    import DeviceGroup.{GetLatestDataFrom, AggregatedData}

    // Tracks device groups in building by using a map from groupId to actorRef
    private var groupToActor: HashMap[String, ActorRef[DeviceGroup.Msg]] = HashMap.empty

    context.spawn(RegisterListener(buildingId, buildingManager = context.self), "RegisterListener")
    context.spawn(QueryConsumer(buildingId, buildingManager = context.self), "QueryConsumer")
    context.spawn(CmdConsumer(buildingId, buildingManager = context.self), "CmdConsumer")

    def mainBehavior(): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case RegisterDevice(devInfo, replyTo) =>
                
                    val groupRef =          
                        if (groupToActor.contains(devInfo.groupId)) {
                            context.log.info("Group already created: {}", devInfo.groupId)
                            groupToActor(devInfo.groupId) 
                        }
                        else {
                            context.log.info("Creating group: {}", devInfo.groupId)
                            val ref = context.spawn(DeviceGroup(devInfo.groupId, buildingId), name = devInfo.groupId)  
                            groupToActor = groupToActor.updated(devInfo.groupId, ref)
                            ref
                        }

                    groupRef ! DeviceGroup.NewDevice(devInfo, replyTo)
                    Behaviors.same  

                case QueryDevices(parsedQuery, replyTo) => 

                    context.log.debug("RECEIVED {}", parsedQuery)
                    queryDevices(parsedQuery, replyTo, timeout = 10.seconds)
                    Behaviors.same

                case SendCommands(parsedCommands, replyTo) => 

                    context.log.debug("RECEIVED {}", parsedCommands)
                    sendCommandsToGroups(parsedCommands, replyTo, timeout = 10.seconds)
                    Behaviors.same
            }


    /** Forwards commands to existing device groups */
    private def sendCommandsToGroups(parsedCmds: ParsedCommands, replyTo: ActorRef[StatusReply[String]], timeout: FiniteDuration): Unit = {
    
        val aggregatorBehavior = 
            Aggregator.statusReplyCollector[String](
                sendRequests = { receiver => 
                    parsedCmds.commands.foreach { groupCmd =>  
                        if (groupToActor.contains(groupCmd.groupId)){
                            val (groupRef, commandList) = (groupToActor(groupCmd.groupId) , groupCmd.devices)
                            groupRef ! DeviceGroup.SendToDevices(commandList, receiver)     
                        }
                        else
                            receiver ! StatusReply.Error(s"Group ${groupCmd.groupId} does not exist")
                    }
                },
                expectedReplies = parsedCmds.commands.size,
                replyTo,
                aggregateReplies = (replies => StatusReply.Success(replies.mkString("{",",\n","}"))),
                timeout
            )

        context.spawnAnonymous(aggregatorBehavior)  
    }

    /** Forwards queries to existing device groups */
    private def queryDevices(parsedQuery: ParsedQuery, replyTo: ActorRef[AggregatedResults], timeout: FiniteDuration): Unit = {
         val aggregatorBehavior = 
            Aggregator[AggregatedData, AggregatedResults](
                sendRequests = { receiver => 
                    parsedQuery.groups.foreach { groupDev =>  
                        if (groupToActor.contains(groupDev.group)){
                            val (groupRef, deviceIdList) = (groupToActor(groupDev.group) , groupDev.devices)
                            groupRef ! GetLatestDataFrom(deviceIdList, receiver)     
                        }
                    }
                },
                expectedReplies = parsedQuery.groups.size,
                replyTo,
                aggregateReplies = { replies => 

                    val resultsJson = 
                        s"""|{
                            |"buildingId": "$buildingId",
                            |"queryId": "${parsedQuery.queryId}"",
                            |"results":[${replies.flatMap(_.jsonList).mkString(",")}] 
                            |}"""
                            .stripMargin

                    AggregatedResults(resultsJson)
                }, 
                timeout
            ) 

        context.spawnAnonymous(aggregatorBehavior)
    }
}