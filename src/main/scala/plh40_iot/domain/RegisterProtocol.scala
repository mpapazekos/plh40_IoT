package plh40_iot.domain

import spray.json._

final case class RegisterInfo(groupId: String, devId: String, devType: String, modulePath: String) 

final case class GroupDevices(group: String, devices: List[String])
final case class ParsedQuery(queryId: String, groups: List[GroupDevices])

final case class CommandInfo(deviceId: String, command: JsValue)
final case class GroupCommandInfo(groupId: String, devices: List[CommandInfo])
final case class ParsedCommands(commands: List[GroupCommandInfo])

object ProtocolJsonFormats extends DefaultJsonProtocol {

    implicit val registerInfoFormat = jsonFormat4(RegisterInfo)
    implicit val groupDevicesFormat = jsonFormat2(GroupDevices)
    implicit val parsedQueryFormat = jsonFormat2(ParsedQuery)
    implicit val commandInfoFormat = jsonFormat2(CommandInfo)
    implicit val groupCmdInfoFormat = jsonFormat2(GroupCommandInfo)
    implicit val parsedCommandsFormat = jsonFormat1(ParsedCommands)
}