name := "Chordial"

// Needs to be included in each build.sbt until Scalastyle is updated to correctly resolve settings
scalastyleConfig := baseDirectory.value / "project" / "scalastyle_config.xml"

lazy val commonSettings = Seq(
  organization := "com.tristanpenman",
  version := "0.0.1",
  scalaVersion := "2.11.8",
  scalacOptions := Seq("-feature", "-unchecked", "-deprecation")
)

lazy val akkaTracingVersion = "0.5.1"
// Note: Although Akka 2.4.9 is available, we need to use a 2.3.x release here as akka-tracing and spray-websocket
// depend on 2.3.x releases, and we don't want to introduce binary incompatibilities
lazy val akkaVersion = "2.3.15"
lazy val scalatestVersion = "2.2.6"
lazy val shapelessVersion = "2.2.0"
lazy val sprayVersion = "1.3.3"
lazy val sprayJsonVersion = "1.3.2"
lazy val sprayWebsocketVersion = "0.1.4"

lazy val core = project.in(file("modules/core"))
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "org.scalatest" %% "scalatest" % scalatestVersion % "test"
  ))

lazy val demo = project.in(file("modules/demo"))
  .dependsOn(core)
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= Seq(
    // Note: Using akka-tracing-spray here would introduce a dependency on spray-routing which conflicts with
    // spray-routing-shapeless2, which is required by spray-websocket
    "com.github.levkhomich" %% "akka-tracing-core" % akkaTracingVersion,
    "com.typesafe.akka" %% "akka-remote" % akkaVersion,
    "com.wandoulabs.akka" %% "spray-websocket" % sprayWebsocketVersion,
    "io.spray" %% "spray-can" % sprayVersion,
    "io.spray" %% "spray-json" % sprayJsonVersion,
    "io.spray" %% "spray-routing-shapeless2" % sprayVersion,
    "io.spray" %% "spray-testkit" % sprayVersion % "test",
    "org.scalatest" %% "scalatest" % scalatestVersion % "test"
  ))

