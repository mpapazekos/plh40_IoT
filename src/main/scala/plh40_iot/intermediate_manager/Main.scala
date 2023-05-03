package plh40_iot.intermediate_manager

import akka.actor.typed.ActorSystem

object Main {
    
    def main(args: Array[String]): Unit = {   
        require(args.length == 1, "Building id is required")
        
        val buildingId = args(0)

        ActorSystem(DeviceManager(buildingId), s"Building-$buildingId-Manager-System")
    } 
}

