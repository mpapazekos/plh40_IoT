package plh40_iot.domain

import plh40_iot.domain.devices.Battery
import plh40_iot.domain.devices.Thermostat

object DeviceTypes {

    // generic trait representing some data produced by a device
    trait DeviceData

    // generic trait representing some command consumed by a smart device
    trait DeviceCmd

    /**
      * Represents a general device with a unique identifier.
      * The generic type A is a type of data that this device produces.
      * @param id The unique id of a device instance.
      */
    abstract class GenDevice[A <: DeviceData](val id: String) {
        
        /** String representation of device type.*/
        val typeStr: String

        /**
          * @return Initial data state for when device starts.
          */
        def initState: A

        /**
          * Computes the next data state based the current one.
          * @param currState current data state
          * @return next state
          */
        def generateData(currState: A): A
        
        /**
          * Parses a json string to some device data.
          * @param json json string to parse
          * @return either the parsed data or an error message
          */
        def fromJsonString(json: String): Either[String, A] 
        
        /**
          * Converts some device data to a json string.
          * @param data device data to convert
          * @return either the converted data string or an error message
          */
        def toJsonString(data: A): Either[String, String]
    }

    /**
      * Represents a smart device, with a unique identifier, capable of receiving certain commands.
      * It inherits basic device functionality from general device and 
      * supports executing and parsing commands of a certain generic type B.
      * @param id The unique id of a device instance.
      */
    abstract class SmartDevice[A <: DeviceData, B <: DeviceCmd](override val id: String) extends GenDevice[A](id) {
        
        /**
          * Computes the next data state of the smart device 
          * based on a certain command and the current data state
          * @param cmd command to execute
          * @param currState current data state to be used by cmd
          * @return new data state after applying the command
          */
        def execute(cmd: B, currState: A): A

        /**
          * Parses a json string to some device command.
          * @param json json string to parse
          * @return either the parsed command or an error message
          */
        def cmdFromJsonString(json: String): Either[String, B]
    }

    /**
      * Matches given device name with possible existing device implementation 
      * and returns a new device instance with a unique id. 
      * @param name Device typeStr name
      * @param deviceId Unique device id
      * @return Either a new device instance if found, or an error message otherwise.
      */
    def getDevice(name: String, deviceId: String) =
        name match {
                case "thermostat" => Right(new Thermostat(deviceId))
                case "battery" => Right(new Battery(deviceId))
                case _ => Left("INVALID DEVICE NAME")
            } 
}
