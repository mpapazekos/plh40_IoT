
import akka.actor.typed.ActorSystem
import com.typesafe.config.ConfigFactory

object Main {
    
    def main(args: Array[String]): Unit = {  

        val config = ConfigFactory.load()
        val buildingId = 
            if (args.isEmpty) 
                config.getString("intermediate_manager.building.id")
            else {
            
                require(args.length == 1, "Building id is required")
        
                args(0)   
            } 

        ActorSystem(BuildingManager(buildingId), s"Building-$buildingId-Manager-System", config)
       
    } 
}

