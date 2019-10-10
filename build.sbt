
ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "com.infixtrading"

lazy val core = (project in file("modules/core"))
  .settings(
    name := "flashbot-core"
  )

lazy val tests = (project in file("modules/tests"))
  .settings(
    name := "flashbot-tests",
    libraryDependencies += "org.scalatest" % "scalatest_2.13" % "3.0.8" % "test"
  )


lazy val tools = (project in file("modules/tools"))
  .settings(
    name := "flashbot-tools"
   )
  .dependsOn(core)
  .aggregate(core)

