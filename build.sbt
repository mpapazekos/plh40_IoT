ThisBuild / scalaVersion := "2.13.10" 
ThisBuild / version      := "0.1.0-SNAPSHOT"

val AkkaVersion = "2.7.0"
val AlpakkaKafkaVersion = "4.0.0"
val AlpakkaMqttVersion = "5.0.0"
val LogbackVersion = "1.4.6"

val dockerRepo = "zekoms"

lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Ywarn-unused:imports",
  "-encoding", "UTF-8"
)

lazy val commonJavacOptions = Seq(
  "-Xlint:unchecked",
  "-Xlint:deprecation"
)

lazy val commonSettings = Seq(
  Compile / scalacOptions ++= commonScalacOptions,
  Compile / javacOptions ++= commonJavacOptions,
  run / javaOptions ++= Seq("-Xms128m", "-Xmx1024m"),
  run / fork := true,
  Global / cancelable := true
)

lazy val commonDependencies = Seq (
  "ch.qos.logback" % "logback-classic" % LogbackVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.2.12" % Test,
  "io.spray" %%  "spray-json" % "1.3.6"
)
//============================================================================= 
lazy val plh40_iot = 
  project
    .in(file("."))
    .settings(
      commonSettings,
      libraryDependencies ++= commonDependencies
    )
    .settings(
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-stream-kafka" % AlpakkaKafkaVersion,
        "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
        "com.lightbend.akka" %% "akka-stream-alpakka-mqtt" % AlpakkaMqttVersion, 
      )
    )

//============================================================================= 
lazy val edge_device = 
  project
    .in(file("edge_device"))
    .dependsOn(plh40_iot)
    .settings(
      commonSettings,
      libraryDependencies ++= commonDependencies,
      mainClass := Some("Main")
    )
    .settings(
      libraryDependencies ++= Seq("com.lightbend.akka" %% "akka-stream-alpakka-mqtt" % AlpakkaMqttVersion)
    )
    .enablePlugins(JavaAppPackaging, DockerPlugin)
    .settings(
      dockerBaseImage := "arm64v8/eclipse-temurin:11.0.19_7-jre",
      dockerBuildCommand ++= Seq("--platform=linux/arm64/v8"),
      packageName := s"$dockerRepo/edge_devices",
      version := "latest",
      dockerExposedPorts ++= Seq(9001)
    )

//============================================================================= 
lazy val intermediate_manager = 
  project
    .in(file("intermediate_manager"))
    .dependsOn(plh40_iot)
    .settings(
      commonSettings,
      libraryDependencies ++= commonDependencies,
      mainClass := Some("Main")
    )
    .settings(
        libraryDependencies ++= Seq(
          "com.typesafe.akka" %% "akka-stream-kafka" % AlpakkaKafkaVersion,
          "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
          "com.lightbend.akka" %% "akka-stream-alpakka-mqtt" % AlpakkaMqttVersion    
        )
    )
    .enablePlugins(JavaAppPackaging, DockerPlugin)
    .settings(
      dockerBaseImage := "amd64/eclipse-temurin:11.0.19_7-jre",
      packageName := s"$dockerRepo/intermediate_manager",
      version := "latest",
      dockerExposedPorts ++= Seq(9002)
    )
    
//============================================================================= 
lazy val region_manager = 
  project
    .in(file("region_manager"))
    .dependsOn(plh40_iot)
    .settings(
      commonSettings,
      libraryDependencies ++= commonDependencies,
      mainClass := Some("Main")
    )
    .settings(
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-stream-kafka" % AlpakkaKafkaVersion,
        "com.typesafe.akka" %% "akka-discovery" % AkkaVersion, 
      )
    )
    .enablePlugins(JavaAppPackaging, DockerPlugin)
    .settings(
      dockerBaseImage := "amd64/eclipse-temurin:11.0.19_7-jre",
      packageName := s"$dockerRepo/region_manager",
      version := "latest",
      dockerExposedPorts ++= Seq(9003)
    )
