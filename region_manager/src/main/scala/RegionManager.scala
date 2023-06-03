package region_manager 

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.ProducerMessage
import akka.pattern.StatusReply
import org.apache.kafka.clients.producer.ProducerRecord

import scala.collection.immutable.HashMap
import scala.concurrent.duration._

import spray.json._

object RegionManager {

    // Convenient types for readability
    type BuildingToJsonMap = Map[String, String]
    type KafkaRecords      = ProducerMessage.Envelope[String, String, NotUsed]

    sealed trait Msg

    /** Sent to create a new BuildingRep actor. Replies to sender with appropriate status report message. */
    final case class RegisterBuilding(buildingId: String, replyTo: ActorRef[StatusReply[String]]) extends Msg

    /**
      * Sent when some info is needed to forwarded to particular buildings in the region.
      * Given a map which contains a json string for each building and a general topic for publishing, 
      * creates the appropriate kafka records.
      * @param buildingMap buildingId to json map 
      * @param topicPrefix needed to build publishing topic for each building
      * @param replyTo sender to reply with the created records
      */
    final case class SendToBuilding(buildingMap: BuildingToJsonMap, topicPrefix: String, replyTo: ActorRef[StatusReply[KafkaRecords]]) extends Msg

    /** Request for buildingIds of this region. Replies with a BuildingList message. */
    final case class GetBuildingIds(replyTo: ActorRef[BuildingList]) extends Msg
    final case class BuildingList(buildingsIds: Iterable[String])
   
    def apply(regionId: String, buildingIds: Iterable[String]): Behavior[Msg] = 
        Behaviors
            .setup(ctx => new RegionManager(ctx, regionId).init(buildingIds))


    /** Used to parse a json format message containing queries for some buildings. */
    private def parseBuildingsQueryJson(msg: String): BuildingToJsonMap = {
        
        val buildings = 
            msg.parseJson.asJsObject.getFields("buildings").head

        val parsed = 
            buildings
                .asInstanceOf[JsArray]
                .elements
                .map { elem =>  
                    val fields = elem.asJsObject.fields
                    (fields("building").asInstanceOf[JsString].value , fields("groupList").toString())
                }      
                
        parsed.toMap
    }

    /** Used to parse a json format message containing commands for some buildings. */
    private def parseBuildingsCmdJson(msg: String): BuildingToJsonMap = {
        
        val fields = 
            msg.parseJson.asJsObject.fields

        val parsed = 
            fields("buildings") match {
                case JsArray(elements) => 
                    elements
                        .map { elem =>  
                            val fields = elem.asJsObject.fields
                            (fields("building").asInstanceOf[JsString].value , fields("cmdList").toString())
                        }  
            }

        parsed.toMap
    }   
}


final class RegionManager private (context: ActorContext[RegionManager.Msg], regionId: String) {

    import RegionManager._

    // Tracks buildings in region using a map from buildingId to actorRef
    private var buildingIdToActor: HashMap[String, ActorRef[BuildingRep.Msg]] = HashMap.empty

    // default restart strategy for spawned actors
    private val restartStrategy = 
        SupervisorStrategy.restartWithBackoff(0.5.seconds, 20.seconds, 0.2)

    // creates a query consumer
    context.spawn(
        Behaviors
            .supervise(BuildingInfoConsumer(
                regionId, 
                topicPrefix = "Query", 
                consumerGroup = s"$regionId-qry-group", 
                regionManager = context.self, 
                parseBuildingsQueryJson))
            .onFailure[Exception](restartStrategy), 
            name = s"$regionId-query-actor"
        )

    // creates a command consumer
    context.spawn(
            Behaviors
                .supervise(BuildingInfoConsumer(regionId, "Command", s"$regionId-cmd-group", context.self, parseBuildingsCmdJson))
                .onFailure[Exception](restartStrategy),
            name = s"$regionId-cmd-actor"
        )

    /** Initial behavior. Updates tracked buildings map with given input values. */
    def init(buildingIds: Iterable[String]): Behavior[Msg] = {
        val buildings =
            buildingIds.map(id => (id -> spawnBuildingActor(id)))
        
        buildingIdToActor = buildingIdToActor.concat(buildings)

        mainBehavior()
    }

    private def mainBehavior(): Behavior[Msg] = 
        Behaviors
            .receiveMessagePartial {
                case GetBuildingIds(replyTo) =>
                    replyTo ! BuildingList(buildingIdToActor.keySet)
                    Behaviors.same
                case RegisterBuilding(buildingId, replyTo) => 
                    if (buildingIdToActor.contains(buildingId)) {
                        replyTo ! StatusReply.Error(s"Building $buildingId is already registered")
                        Behaviors.same
                    }
                    else {
                        val buildingRef = spawnBuildingActor(buildingId)
                        replyTo ! StatusReply.Success(s"Building $buildingId registered successfully")

                        buildingIdToActor = buildingIdToActor.updated(buildingId, buildingRef)
                        Behaviors.same
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

    /** Creates a supervised BuildingRep actor with the given buildingId. */
    private def spawnBuildingActor(buildingId: String): ActorRef[BuildingRep.Msg] = {
        context.log.debug("Spawning actor for building: {}", buildingId)
        context.spawnAnonymous(
            Behaviors
                .supervise(BuildingRep(buildingId, regionId))
                .onFailure[Exception](SupervisorStrategy.restartWithBackoff(0.5.seconds, 10.seconds, 0.2))
        )
    }
}