package plh40_iot.domain

import spray.json._

/**
  * Data sent by a device containing info for registration
  * @param groupId Module group that contains the device 
  * @param devId Unique device id
  * @param devType Device type (e.g. thermostat, battery)
  * @param modulePath Topic in which device publishes data
  */
final case class RegisterInfo(groupId: String, devId: String, devType: String, modulePath: String) 

/**
  * Represents a successfully parsed query json.  
  * @param queryId The wuery id 
  * @param groups List of groups with device ids to send query
  */
final case class ParsedQuery(queryId: String, groups: List[GroupDevices])

/** Contains a group id and a list of device ids possibly in that group */
final case class GroupDevices(group: String, devices: List[String])

/**
  * Represents a successfully parsed command json.  
  * @param commands list of commands for certain groups
  */
final case class ParsedCommands(commands: List[GroupCommandInfo])

/** Contains a group id and a list of commands for devices possibly in that group */
final case class GroupCommandInfo(groupId: String, devices: List[CommandInfo])

/** Contains command in json form in order to send to given device */
final case class CommandInfo(deviceId: String, command: JsValue)

// Used to convert above classes to and from json format
object ProtocolJsonFormats extends DefaultJsonProtocol {

    implicit val registerInfoFormat   = jsonFormat4(RegisterInfo)
    implicit val groupDevicesFormat   = jsonFormat2(GroupDevices)
    implicit val parsedQueryFormat    = jsonFormat2(ParsedQuery)
    implicit val commandInfoFormat    = jsonFormat2(CommandInfo)
    implicit val groupCmdInfoFormat   = jsonFormat2(GroupCommandInfo)
    implicit val parsedCommandsFormat = jsonFormat1(ParsedCommands)
}