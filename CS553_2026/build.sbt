ThisBuild / scalaVersion := "3.3.3"
ThisBuild / organization := "edu.uic.cs553"
ThisBuild / version := "0.1.0"

val akkaVersion = "2.6.20"
val circeVersion = "0.14.6"
val scalaTestVersion = "3.2.17"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
  Test / fork := true,
  libraryDependencies ++= Seq(
    "com.typesafe" % "config" % "1.4.3",
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "ch.qos.logback" % "logback-classic" % "1.4.11",
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test
  )
)

lazy val akkaSettings = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test
  )
)

lazy val simCore = (project in file("sim-core"))
  .settings(commonSettings)
  .settings(name := "sim-core")

lazy val simAlgorithms = (project in file("sim-algorithms"))
  .dependsOn(simCore)
  .settings(commonSettings)
  .settings(name := "sim-algorithms")

lazy val simRuntimeAkka = (project in file("sim-runtime-akka"))
  .dependsOn(simCore, simAlgorithms)
  .settings(commonSettings)
  .settings(akkaSettings)
  .settings(name := "sim-runtime-akka")

lazy val simCli = (project in file("sim-cli"))
  .dependsOn(simCore, simAlgorithms, simRuntimeAkka)
  .settings(commonSettings)
  .settings(akkaSettings)
  .settings(
    name := "sim-cli",
    Compile / mainClass := Some("edu.uic.cs553.simMain"),
    // Forked run uses build root cwd so --config conf/... and --out paths resolve (conf lives in repo root, not sim-cli).
    Compile / run / fork := true,
    Compile / run / forkOptions := ForkOptions().withWorkingDirectory(Some((ThisBuild / baseDirectory).value))
  )

lazy val root = (project in file("."))
  .aggregate(simCore, simAlgorithms, simRuntimeAkka, simCli)
  .settings(commonSettings)
  .settings(name := "CS553_2026")
