ThisBuild / scalaVersion := "2.13.10" 
ThisBuild / version      := "0.1.0-SNAPSHOT"


val AkkaVersion = "2.7.0"
val AlpakkaKafkaVersion = "4.0.0"
val AlpakkaMqttVersion = "5.0.0"
val LogbackVersion = "1.4.6"

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

lazy val plh40_iot = 
  project
    .in(file("."))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(
            "ch.qos.logback" % "logback-classic" % LogbackVersion,
            "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
            "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
            "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion,
            "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
            "org.scalatest" %% "scalatest" % "3.2.12" % Test,
            "com.typesafe.akka" %% "akka-stream-kafka" % AlpakkaKafkaVersion,
            "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
            "com.lightbend.akka" %% "akka-stream-alpakka-mqtt" % AlpakkaMqttVersion,
            "io.spray" %%  "spray-json" % "1.3.6"
        )
    )