import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import region_manager.RegionManager
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.pattern.StatusReply

class RegionManagerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

    "A Region Manager" should {
        "be able to register new buildings" in {

            val regionManager =
                spawn(RegionManager("test-region", Set.empty))
            
            val buildingListReceiver = TestProbe[StatusReply[String]]()

            regionManager ! RegionManager.RegisterBuilding("test-buildingId", buildingListReceiver.ref)
        
            buildingListReceiver.expectMessage(StatusReply.Success("Building test-buildingId registered successfully"))
        }
    }
  
}
