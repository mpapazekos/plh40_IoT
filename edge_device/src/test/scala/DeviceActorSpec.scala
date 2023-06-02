import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.stream.alpakka.mqtt.MqttMessage
import akka.stream.alpakka.mqtt.MqttQoS
import akka.stream.alpakka.mqtt.MqttSubscriptions
import akka.stream.scaladsl.Source
import akka.util.ByteString
import edge_device.DeviceActor
import org.scalatest.wordspec.AnyWordSpecLike
import plh40_iot.domain.DeviceTypes
import plh40_iot.domain.devices.ThermostatData
import plh40_iot.util.MqttConnector
import plh40_iot.util.Utils

import java.util.UUID
import scala.concurrent.duration.DurationInt

class DeviceActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

    // needs mqtt broker running 
    "A device" must {
        "publish initial state after registration" in {

            implicit val ec = system.classicSystem.dispatcher

            val receiverActorProbe = TestProbe[Double]()

            val randomId = "test_id_" + UUID.randomUUID 

            val thermostat = 
                DeviceTypes
                    .create("thermostat", randomId)
                    .getOrElse(throw new Exception("could not create device"))

            spawn(
                DeviceActor(
                device = thermostat,
                buildingId = "building1",
                module = "module1",
                pubTopic = s"/building1/module1/temperature/$randomId",
                tickPeriod = 1.seconds
            ))

            Source
                .single(MqttMessage(topic = s"/building1/register/$randomId", payload = ByteString("REGISTERED")))
                .runWith(MqttConnector.publisherSink("test-publisher"))
            
            MqttConnector
                .subscriberSource(
                    "test-subscriber", 
                    MqttSubscriptions(s"/building1/module1/temperature/$randomId", MqttQoS.AtLeastOnce)
                )
                .take(1)
                .map(thermostat.fromJsonString)
                .via(Utils.errorHandleFlow())
                .runForeach(data => receiverActorProbe ! data.asInstanceOf[ThermostatData].value)

            receiverActorProbe.expectMessage(25.0)
        }
    }
}
