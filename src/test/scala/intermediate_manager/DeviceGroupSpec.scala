package intermediate_manager

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import plh40_iot.intermediate_manager.device.DeviceRep
import plh40_iot.intermediate_manager._
import plh40_iot.domain.devices.Thermostat
import plh40_iot.domain.RegisterInfo


class DeviceGroupSpec  extends ScalaTestWithActorTestKit with AnyWordSpecLike {

    "return temperature value for working devices" in { 

        val devGroupActor = spawn(DeviceGroup("test_group","buidling1"))
        val registerInfo = RegisterInfo("test_group", "test_id", "thermostat", "/module")

        val receiverProbe = createTestProbe[DeviceGroup.Response]()
        val aggregateProbe = createTestProbe[DeviceGroup.AggregatedData]()

        devGroupActor ! DeviceGroup.NewDevice(registerInfo, receiverProbe.ref)

        devGroupActor ! DeviceGroup.GetLatestDataFrom(List("test_id"), aggregateProbe.ref)

        val jsonResponse = 
            """|{
               |  "deviceId": "test_id",
               |  "data": "NO DATA YET"
               |}"""
               .stripMargin

        aggregateProbe.expectMessage(DeviceGroup.AggregatedData(List(jsonResponse)))

        receiverProbe.expectMessage(DeviceGroup.DeviceCreated("test_id"))
    }

    "aggregate data from idle devices" in {

        val thermostat1 = spawn(DeviceRep(Thermostat, "test_id_1", "test_module_path_1", "buidling1"))
        val thermostat2 = spawn(DeviceRep(Thermostat, "test_id_2", "test_module_path_2", "buidling1"))
        val thermostat3 = spawn(DeviceRep(Thermostat, "test_id_3", "test_module_path_3", "buidling1"))

        val aggregateProbe = createTestProbe[DeviceGroup.AggregatedData]()

        val deviceRefs = List(thermostat1, thermostat2, thermostat3)

        val devGroupActor = spawn(DeviceGroup("test_group","buidling1"))

        devGroupActor ! DeviceGroup.GetLatestData(deviceRefs, aggregateProbe.ref)

        aggregateProbe.expectMessage(DeviceGroup.AggregatedData(
            List(
                 """|{
                    |  "deviceId": "test_id_1",
                    |  "data": "NO DATA YET"
                    |}"""
                    .stripMargin,
                    """|{
                    |  "deviceId": "test_id_2",
                    |  "data": "NO DATA YET"
                    |}"""
                    .stripMargin,
                    """|{
                    |  "deviceId": "test_id_3",
                    |  "data": "NO DATA YET"
                    |}"""
                    .stripMargin,
                )
            )
        )
    }

    "aggregate data from running devices" in {

        val thermostat1 = spawn(DeviceRep(Thermostat, "test_id_1", "test_module_path_1", "buidling1"))
        val thermostat2 = spawn(DeviceRep(Thermostat, "test_id_2", "test_module_path_2", "buidling1"))
      
       
        val aggregateProbe = createTestProbe[DeviceGroup.AggregatedData]()
        val receiverProbe = createTestProbe[DeviceRep.DataReceived]()

        val dataJson = Thermostat.toJsonString(Thermostat.initState).getOrElse("ERROR")

        thermostat1 ! DeviceRep.NewData(Thermostat.initState,receiverProbe.ref)
        thermostat2 ! DeviceRep.NewData(Thermostat.initState,receiverProbe.ref)

        val deviceRefs = List(thermostat1, thermostat2)

        val devGroupActor = spawn(DeviceGroup("test_group","buidling1"))

        devGroupActor ! DeviceGroup.GetLatestData(deviceRefs, aggregateProbe.ref)

        aggregateProbe.expectMessage(DeviceGroup.AggregatedData(
            List(
                 s"""|{
                     |"deviceId": "test_id_1",
                     |"data": $dataJson
                     |}"""
                     .stripMargin,
                 s"""|{
                     |"deviceId": "test_id_2",
                     |"data": $dataJson
                     |}"""
                     .stripMargin
                )
            )
        )
    }

}