import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.pattern.StatusReply

import plh40_iot.util.Aggregator

import scala.collection.immutable.HashMap
import scala.concurrent.duration.DurationInt
import plh40_iot.domain.ParsedQuery
import plh40_iot.domain.RegisterInfo
import plh40_iot.domain.ParsedCommands

object DeviceManager {

    type GroupToJsonInfo = Map[String, Iterable[String]]

    // Δημιουργεί και διαχειρίζεται ομάδες συσκευών 
    // - device group 
    // - device 

    // Θα ενημερώνεται για την εμφάνιση νέας συσκευής μέσω μηνύματος
    sealed trait Msg

    // για την κατασκευή μιας συσκευής είναι απαραίτητα:
    //  - ομάδα
    //  - το topic για την επικοινωνία (μέσω αυτόυ θα κατασκευάζονται τα αντίστοιχα path για pub/sub)
    //  - αναγνωριστικό συσκευής 
    //  - τύπος για να κατασκευαστεί το κατάλληλο αντικείμενο(θερμοστάτης, μπαταρία, κλπ)
    // η ομάδα και οι πληροφορίες για τη συσκευή δίνονται ξεχωριστά ωστε να στέλνονται μετά στον group actor για τη δημιουργία
    final case class RegisterDevice(info: RegisterInfo, replyTo: ActorRef[DeviceGroup.Response]) extends Msg

    final case class QueryDevices(parsedQuery: ParsedQuery, replyTo: ActorRef[AggregatedResults]) extends Msg
    final case class AggregatedResults(resultsJson: String) extends Msg

    final case class SendCommands(parsedCmds: ParsedCommands, replyTo: ActorRef[StatusReply[String]]) extends Msg
   
    def apply(buildingId: String): Behavior[Msg] = 
       Behaviors.setup(ctx => new DeviceManager(ctx, buildingId).mainBehavior())  
}


final class DeviceManager private (context: ActorContext[DeviceManager.Msg], buildingId: String) {

    import DeviceManager._
    import DeviceGroup.{GetLatestDataFrom, AggregatedData}

    private var groupToActor: HashMap[String, ActorRef[DeviceGroup.Msg]] = HashMap.empty

    // create device listener
    context.spawn[Nothing](RegisterListener(buildingId, context.self), "RegisterListener")

    // create device query actor
    context.spawn[Nothing](QueryConsumer(buildingId, context.self), "QueryConsumer")

    context.spawn[Nothing](CommandConsumer(buildingId, context.self), "CommandConsumer")

    // διατηρεί έναν κατάλογο με τις ομάδες συσκευών
    def mainBehavior(): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case RegisterDevice(devInfo, replyTo) =>
                    // οταν λαμβάνει ένα καινουργιο μήνυμα για εγγραφή συσκευής 
                    // πρώτα δημιουργεί την ομάδα αν δεν υπάρχει 
                    // και στέλνει αντίστοιχο μήνυμα σε αυτή για τη δημιουργία συσκευής
                    context.log.info("Finding group {}", devInfo.groupId)
                    if (groupToActor.contains(devInfo.groupId)) {
                        context.log.info("Group already created: {}", devInfo.groupId)
                        val groupRef = groupToActor(devInfo.groupId)
                        groupRef ! DeviceGroup.NewDevice(devInfo, replyTo)
                        Behaviors.same  
                    }
                    else {
                        context.log.info("Creating group: {}", devInfo.groupId)
                        val groupRef = context.spawn(DeviceGroup(devInfo.groupId, buildingId), name = devInfo.groupId) 
                        groupRef ! DeviceGroup.NewDevice(devInfo, replyTo)
                        groupToActor = groupToActor.updated(devInfo.groupId, groupRef)
                        Behaviors.same
                    }

                case QueryDevices(parsedQuery, replyTo) => 

                    context.log.info("RECEIVED {}", parsedQuery)
                    queryDevices(parsedQuery, replyTo)
                    Behaviors.same

                case SendCommands(parsedCommands, replyTo) => 

                    context.log.info("RECEIVED {}", parsedCommands)
                    sendCommandsToGroups(parsedCommands, replyTo)
                    Behaviors.same
            }


    private def sendCommandsToGroups(parsedCmds: ParsedCommands, replyTo: ActorRef[StatusReply[String]]): Unit = {
        /**
         * οι πληροφορίες που φτάνουν σε αυτό το σημείο έχουν τη μορφή 
         * Map(
         *     "group1" -> Iterable(jsonCmd1, jsonCmd2, ...)
         *     "group2" -> Iterable(jsonCmd1, jsonCmd2, ...)
         *     ...  
         * )
         */

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
                timeout = 5.seconds
            )

        context.spawnAnonymous(aggregatorBehavior)  
    }

    private def queryDevices(parsedQuery: ParsedQuery, replyTo: ActorRef[AggregatedResults]): Unit = {
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
                timeout = 10.seconds
            ) 

        context.spawnAnonymous(aggregatorBehavior)
    }
}