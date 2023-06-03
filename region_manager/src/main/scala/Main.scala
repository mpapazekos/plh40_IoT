package region_manager

import akka.actor.typed.ActorSystem
import com.typesafe.config.ConfigFactory

object Main {
   
      def main(args: Array[String]): Unit = {

            if (args.isEmpty) {
                  val config = ConfigFactory.load()

                  val regionId = config.getString("region_manager.region.id")
                  val buildingIds = config.getString("region_manager.buildingIdList").split(",")

                  ActorSystem(RegionManager(regionId, buildingIds), s"Region-$regionId-Manager-System", config)
            }
            else {
                  require(
                        args.length >= 2, 
                        "Invalid number of arguements. Correct use: [region_id] [building_id]*"
                  )

                  val regionId = args(0)
                  val buildingIds = args.tail

                  ActorSystem(RegionManager(regionId, buildingIds), s"Region-$regionId-Manager-System")
            }    
      }
  
}
