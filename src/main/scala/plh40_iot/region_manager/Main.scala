package plh40_iot.region_manager

import akka.actor.typed.ActorSystem

object Main {
    /**
      * 
      * The region management actor is a higher-level actor that has the same responsibilities as the intermediate actor. 
      * The only difference is that its children are intermediate actors and not sensor actors. 
      * Responsibilities:
            1)Gather and aggregate data from intermediate actors. Each actor will be subscribed to the relative kafka topic 
            where all children managing actors publish their data. An aggregation step is also possible to happen here as an 
            intermediate step before calculations (to be decided later).

            2)Stream data towards main managing actor through kafka. The actor will publish to another kafka topic 
            (is it possible to do this directly from within kafka? To forward the stream to another broker directly? Need to check)

            3)Act as intermediate for queries and orders that come from higher levels. Queries from the managing actor are given to region actors. 
            An example is a command to force a device action (heating increase for a certain room in a building, or for several buildings, 
            or a query to report the status of several devices in an area). This would be in the form {building id -> command} 
            or query (return status of all devices). For queries in bibliography a separate actor is created to create and handle the query. 
            Is this needed? Need to check.

            4)Deploys a supervision policy for all actors beneath it for fault tolerance. An intermediate managing actor that fails must be supervised 
            by the corresponding region actor. What happens to the intermediate manager’s sensor actors? Do they have to be created again? 
            If yes what happens to their sensor IDs? Need to check.
      */

      def main(args: Array[String]): Unit = {

            require(
                  args.length >= 2, 
                  "Invalid number of arguements. Correct use: [region_id] [building_id]*"
            )

            val regionId = args(0)
            val buildingIds = args.tail

            ActorSystem(RegionManager(regionId, buildingIds), s"Region-$regionId-Manager-System")
      }
  
}
