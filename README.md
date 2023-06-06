# PLH40-IoT

University project for demonstrating uses of the Akka toolkit libraries (Actors, Akka-Streams, Alpakka MQTT, Kafka).
This project contains a multi-tiered hierarchical architecture for monitoring and controlling edge devices located in some buildings of a city. 

The goal of this particular project was to gain an understanding of the basics around the actor model, akka streams, kafka protocol, mqtt protocol, git version control system, docker platform and certain programming design practices used. It is by no means a complete exemplary attempt but a good beginning step for learning and gaining some software development experience. 

Each tier has its own project folder. The devices in each building constitute the lowest tier (Level 0 - edge_device) and are grouped by the different modules in that building. Each device, simulated as an actor, is programmed to send collected data to the next tier (Level 1 - intermediate_manager) which basically is a central system in that particular building in charge of monitoring the devices. This central system is programmed as an actor system containing actors for each module group and device in that building. The data collected is transferred using the MQTT protocol through the Alpakka Mqtt implementation. The final tier (Level 2 - region_manager) for this projectimplements a region manager system in charge of passing data from a selection of buildings to the upper level(not implemented, perhaps a central management system) through a Kafka broker. Device control is achieved by sending data queries and commands to specific devices from the region manager, that are then passed down the hierarchy until finally arriving to a device.

---

![Project hierarchy](/assets/images/project-architecture.png)

*Icon source: https://www.flaticon.com/authors/vitaly-gorbachev*

---

So far the only types of implented devices include a thermostat and a smart battery for testing purposes. Using the [docker plugin](https://sbt-native-packager.readthedocs.io/en/latest/formats/docker.html) for the sbt platform and the already uploaded images for kafka and mqtt brokers on docker hub, a docker compose example file is provided which includes a region manager image, two different building manager images and four devices for each building split in two groups, also in two different images in order to save some memory instead of creating eight different images for a simple example. In some cases, for a better simulation attempt, an arm64 architecture was used as a base system image.

The specifics for each devices to be running in a building is given to the application as input through the environmental variable DEVICE_LIST_JSON, with the following format:

```
{
    "buildingId": "{unique building id}",
    "devices": [
        {
            "deviceType": "{thermostat, battery, etc.}",
            "deviceId": "{unique device id}",
            "module": "{device group}",
            "publishingTopic": "{final mqtt topic path in which data is published}",
            "dataSendingPeriod": {data sending period in seconds}
        }, ...
    ]
}
```

## Running the example

--- 

To run this example locally make sure the docker engine is running. The use of docker-desktop is recommended because you can see the logs of the data transferred between each level conveniently. The images in this predefined example are configured either directly in the docker-compose file, or by the use of external files in the example folder (mqtt.conf for mqtt broker, .env files for device json input).

### Using existing docker images

First change the directory to the example folder:
> cd docker_setup_example

Then run docker compose in the background:
> docker compose up -d

### Creating new images with sbt docker plugin

When a new device type is implemented the images for edge_device and intermediate_manager projects must be rebuild in order to include the changes.
It is possible to change the settings in the built.sbt file and create a new image for each project using sbt by running 

> sbt project {project-name} Docker / publishLocal 

for creating new images to be used locally.

---

### Sending data queries and commands 

In order to send queries through the region manager and get the latest data from specific devices, you have to use an interactive shell for the kafka broker container in order to publish the queries in json format to the *Query-{regionId}* topic. The query format structure is: 

```
{
    "buildings": [
        {
            "building": "{building id to send groupList value}",
            "groupList": {
                "queryId": "{query id}",
                "groups": [
                    {
                        "group": "{device group unique id}",
                        "devices": ["{device1}", "{device2}", ... ]
                    }, ...
                ]
            }
        },...
    ]
}
```

Use the interactive shell with: 
> docker exec -it kafka-broker bash 

Publish the queries json (compact form) with the kafka console producer:
> kafka-console-producer --topic Query-region1  --bootstrap-server localhost:9092  

The results can be seen on the region container logs after a few seconds.


In order to send commands through the region manager you have to use an interactive shell for the kafka broker container in order to publish the commands in json format to the *Command-{regionId}* topic. The command format structure is:

```
{
    "buildings": [
        {
            "building": "{building id to send cmdList value}",
            "cmdList": {
                "commands": [
                    {
                        "groupId": "{device group unique id}",
                        "devices": [
                            {
                                "deviceId": "{device unique id}",
                                "command": {
                                    "name": "{command name }",
                                    "value": {command json value}
                                }
                            }, ...
                        ]
                    }, ...
                ]
            }
        }, ...
    ]
}                       
```
Use the interactive shell with: 
> docker exec -it kafka-broker bash 

Publish the queries json (compact form) with the kafka console producer:
> kafka-console-producer --topic Command-region1  --bootstrap-server localhost:9092  

The results can be seen on the edge_device container logs after a few seconds.