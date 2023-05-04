package plh40_iot.region_manager

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.ProducerMessage
import akka.pattern.StatusReply
import org.apache.kafka.clients.producer.ProducerRecord
import plh40_iot.region_manager.consumers.CommandConsumer
import plh40_iot.region_manager.consumers.RegionQueryActor
import plh40_iot.region_manager.building.BuildingRep

import scala.collection.immutable.HashMap
import scala.concurrent.duration._


final case class DeviceInfo(groupId: String, devId: String, devType: String, modulePath: String)    

object RegionManager {

    type KafkaRecords = ProducerMessage.Envelope[String, String, NotUsed]

    // θα πρέπει να διατηρεί μια σύνδεση μέσω kafka απο την οποία θα λαμβάνει πληροφορίες για ενα κτήριο 
    // για να κατασκευάσει εναν actor που το αντιπροσωπεύει χρειάζεται τις εξής πληροφορίες
    //  group, building_id, kafka_topic, 

    // kafka consumers 
    // για εγγραφή νέας ομάδας 
    // για εγγραφή στη λήψη δεδομένων απο intermediate manager
    
    // kafka producers 
    // για αποστολή εντολων και ερωτημάτων 

    sealed trait Msg

    final case class RegisterBuilding(buildingId: String, replyTo: ActorRef[StatusReply[String]]) extends Msg
    final case class SendToBuilding(buildingMap: Map[String, String], topicPrefix: String, replyTo: ActorRef[StatusReply[KafkaRecords]]) extends Msg
   
    def apply(regionId: String, buildingIds: Iterable[String]): Behavior[Msg] = 
        Behaviors
            .setup(ctx => new RegionManager(ctx, regionId).init(buildingIds))
}


final class RegionManager private (context: ActorContext[RegionManager.Msg], regionId: String) {

    import RegionManager._

   
    context.spawn[Nothing](
        Behaviors
            .supervise[Nothing](RegionQueryActor(regionId,s"$regionId-qry-group", context.self))
            .onFailure[Exception](SupervisorStrategy.restartWithBackoff(0.5.seconds, 20.seconds, 0.2)), 
            name = s"$regionId-query-actor"
        )

    context.spawn[Nothing](
                Behaviors
                    .supervise[Nothing](CommandConsumer(regionId, s"$regionId-cmd-group", context.self))
                    .onFailure[Exception](SupervisorStrategy.restartWithBackoff(0.5.seconds, 20.seconds, 0.2)),
                name = s"$regionId-cmd-actor"
            )

    def init(buildingIds: Iterable[String]): Behavior[Msg] = {
        val buildingIdToActor =
            buildingIds.map(id => (id, spawnBuildingActor(id)))
        
        mainBehavior(HashMap.from(buildingIdToActor))
    }

  
    private def mainBehavior(buildingIdToActor: HashMap[String, ActorRef[BuildingRep.Msg]]): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case RegisterBuilding(buildingId, replyTo) => 
                    if (buildingIdToActor.contains(buildingId)) {
                        replyTo ! StatusReply.Error(s"Building $buildingId is already registered")
                        Behaviors.same
                    }
                    else {
                        val buildingRef = spawnBuildingActor(buildingId)
                        replyTo ! StatusReply.Success(s"Building $buildingId registered successfully")
                        mainBehavior(buildingIdToActor.updated(buildingId, buildingRef))
                    }  

                case SendToBuilding(buildingMap, topicPrefix, replyTo) => 

                    val records = 
                        buildingMap.collect {
                            case entry if (buildingIdToActor.contains(entry._1)) =>
                                val (buildingId, json) = (entry._1, entry._2)
                                new ProducerRecord[String, String](s"$topicPrefix-$buildingId", json)
                        }
                        
                    val kafkaRecords = 
                        ProducerMessage.multi(records.toSeq)

                    replyTo ! StatusReply.Success(kafkaRecords)

                    Behaviors.same       
            }

    private def spawnBuildingActor(buildingId: String): ActorRef[BuildingRep.Msg] = {
        context.log.info("Spawning actor for building: {}", buildingId)
        context.spawnAnonymous(
            Behaviors
                .supervise(BuildingRep(buildingId, regionId))
                .onFailure[Exception](SupervisorStrategy.restartWithBackoff(0.5.seconds, 10.seconds, 0.2))
        )
    }
}