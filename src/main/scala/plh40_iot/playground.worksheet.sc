import plh40_iot.domain.devices.Battery
import plh40_iot.domain.devices.Thermostat
import plh40_iot.domain._
import plh40_iot.domain.DeviceJsonProtocol._
import plh40_iot.edge_device._

import spray.json._
import spray.json.DefaultJsonProtocol._

val initTemperature = Thermostat.initState
val initBattery = Battery.initState

val randomTemperature = Thermostat.generateData(initTemperature)

val randomBattery = Battery.generateData(initBattery)

randomBattery.toJson.compactPrint


val s = "{\"command\": \"set\" , \"value\": 34.1}"
Thermostat.cmdFromJsonString(s)

val registerJson = 
    """{
        "id": "3df15d5b-33e0-45bf-978d-d93eb92ad24a",
        "group": "test_group",
        "module_path": "region1/building1/module1/temperature",
        "device": "thermostat"
    }"""

val payload = 
    s"""{
        |    "id": ${JsString("uuid")},
        |    "group": ${JsString("groupId")},
        |    "module_path": ${JsString("modulePath")},
        |    "device": ${JsString("dev")}
        |}"""
        .stripMargin


val fields = registerJson.parseJson.asJsObject("JSON object expected").fields.map {
            case (field, JsString(value)) => (field, value)
        } 

def testDevice[D <: DeviceTypes.DeviceData] (device: DeviceTypes.GenDevice[D]): Unit = {
    println(device.fromJsonString("""{
        "unit": "Celsius",
        "value": 25.0
    }"""))
}

Thermostat.toJsonString(Thermostat.initState)

testDevice(Thermostat)

//------------------------------------------------------------------------------

val data = Thermostat.initState

val parsed = Thermostat.toJsonString(data)

val dataRes = parsed.getOrElse("FAILED TO GET LATEST  DATA")

val deviceId = "sdgsd"
                    
val result =
    s"""{
        | "deviceId": "$deviceId",
        | "data": $dataRes
        |}"""
        .stripMargin

result.parseJson.asJsObject.getFields("data")

//---------------------------------------------------------------------------------

case class Arr(x: String, y: List[String])

val arr = Arr("testing", List("asdfas", "dfasdfas", "fasdfa"))

implicit val arrFormat = jsonFormat2(Arr)

arr.toJson.compactPrint

val regionJson = 
    """{
       "buildings": [
        {
           "building": "building1", 
           "groupList": {
                "groups": [ 
                    { "group": "group1", "devices": ["deviceId1", "deviceId2"] },
                    { "group": "group2", "devices": ["deviceId1", "deviceId2"] }
                ] 
            }     
        },
        {
           "building": "building2",
           "groupList": {
                "groups": [ 
                    { "group": "group1", "devices": ["deviceId1", "deviceId2"] },
                    { "group": "group2", "devices": ["deviceId1", "deviceId2"] }
                ] 
            }      
        }
       ]
    }
    """




val buildings = regionJson.parseJson.asJsObject.getFields("buildings").head

val buildingsMap = 
    buildings match {
        case JsArray(elements) => 
            elements.map { jsValue =>  
                val fields = jsValue.asJsObject.fields
                (fields("building").toString , fields("groupList"))
            }.toMap
    }

//-------------------------------------------------------------------------------------

private def parseJson[Result](msg: String, parse: String => Result): Either[String, Result] =    
    try  
        Right(parse(msg))
    catch {
        case e: Exception => Left(e.toString())
    }   
       

    
def parseGroups(json: String): Map[String, Iterable[String]] = {

        val groups = 
            json.parseJson.asJsObject.getFields("groups").head

        val parsed = 
            groups match {
                case JsArray(elements) =>  
                    elements.map{e =>
                        val fields  = e.asJsObject.fields
                        val group   = fields("group").asInstanceOf[JsString].value
                        val devices = fields("devices").asInstanceOf[JsArray].elements.map(_.asInstanceOf[JsString].value)

                        (group, devices)
                    }  
                }
                
        parsed.toMap
    }

val groupListJson = 
    """
    {
        "groups": [ 
            { "group": "group1", "devices": ["deviceId1", "deviceId2"] },
            { "group": "group2", "devices": ["deviceId1", "deviceId2"] }
        ] 
    }  
    """

val jsonMap = parseJson(groupListJson, parseGroups).getOrElse(Map.empty)

jsonMap("group1")

val jsonList = List(groupListJson, groupListJson)

  
val resultsJson = 
    s"""|{
        |"results":[${jsonList.mkString(",")}] 
        |}""".stripMargin
   

resultsJson.parseJson.asJsObject.getFields("results")

//=====================================================================================


case class GroupDevices(group: String, devices: List[String])

case class Query(queryId: String, groups: List[GroupDevices])


implicit val groupDevicesFormat = jsonFormat2(GroupDevices)
implicit val queryFormat = jsonFormat2(Query)


val queryJson  = """{"queryId":"query1","groups":[{"group":"factory","devices":["tb1"]},{"group":"room","devices":["bb1"]}]}"""

val parsedQuery = queryJson.parseJson.convertTo[Query]

parsedQuery.groups.mkString

//=========================================================================================

val commandJson = 
    """
    {"commands":[{"groupId":"group1","devices":[{"deviceId":"deviceId1","command":{"name":"cmd1","info":{"value":"t"}}}]}]}
    """
    

case class CommandInfo(deviceId: String, command: JsValue)
case class GroupCommandInfo(groupId: String, devices: List[CommandInfo])
case class ParsedCommands(commands: List[GroupCommandInfo])

implicit val commandInfoFormat = jsonFormat2(CommandInfo)
implicit val groupCmdInfoFormat = jsonFormat2(GroupCommandInfo)
implicit val parsedCommandsFormat = jsonFormat1(ParsedCommands)

val parsedCommands = commandJson.parseJson.convertTo[ParsedCommands]

parsedCommands.commands






