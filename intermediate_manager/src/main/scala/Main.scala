
import akka.actor.typed.ActorSystem
import com.typesafe.config.ConfigFactory

object Main {
    
    def main(args: Array[String]): Unit = {  

        if (args.isEmpty) {
            val config = ConfigFactory.load()
            val buildingId = config.getString("intermediate_manager.building.id")

            ActorSystem(BuildingManager(buildingId), s"Building-$buildingId-Manager-System", config)
        }
        else {
            
            require(args.length == 1, "Building id is required")
        
            val buildingId = args(0)

            ActorSystem(BuildingManager(buildingId), s"Building-$buildingId-Manager-System")
        } 
       
    } 
}

