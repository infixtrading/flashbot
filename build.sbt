import sbtcrossproject.{crossProject, CrossType}

lazy val server = (project in file("server")).settings(commonSettings).settings(
  scalaJSProjects := Seq(client),
  pipelineStages in Assets := Seq(scalaJSPipeline),
  pipelineStages := Seq(digest, gzip),
  // triggers scalaJSPipeline when using compile or continuous compilation
  compile in Compile := ((compile in Compile) dependsOn scalaJSPipeline).value,
  libraryDependencies ++= ((
    serviceDeps ++ networkDeps ++ graphQLServerDeps ++
      dataStores ++ timeSeriesDeps ++ testDeps ++ statsDeps
    ) ++ Seq(
    "org.jgrapht" % "jgrapht" % "1.3.0",
    "org.jgrapht" % "jgrapht-core" % "1.3.0",
    "com.quantego" % "clp-java" % "1.16.10",
    "com.vmunier" %% "scalajs-scripts" % "1.1.2",
    "com.github.inamik.text.tables" % "inamik-text-tables" % "0.8",
    "com.lihaoyi" %% "fansi" % "0.2.5",
    "com.github.andyglow" % "scala-jsonschema-core_2.12" % "0.0.8",
    "com.github.andyglow" % "scala-jsonschema-api_2.12" % "0.0.8",
    "com.github.andyglow" % "scala-jsonschema-circe-json_2.12" % "0.0.8",
    "de.sciss" %% "fingertree" % "1.5.2",
    guice, ws,
    specs2 % Test
  )),
  // Compile the project before generating Eclipse files, so that generated .scala or .class files for views and routes are present
  EclipseKeys.preTasks := Seq(compile in Compile)
).enablePlugins(WebScalaJSBundlerPlugin, PlayScala).
  dependsOn(sharedJvm)

lazy val client = (project in file("client")).settings(commonSettings).settings(
  resolvers += "Apollo Bintray" at "https://dl.bintray.com/apollographql/maven/",
  libraryDependencies ++= Seq(
    "org.scala-js" %%% "scalajs-dom" % "0.9.5",
    "com.apollographql" %%% "apollo-scalajs-react" % "0.4.3",
    "me.shadaj" %%% "slinky-core" % "0.5.1", // core React functionality, no React DOM
    "me.shadaj" %%% "slinky-web" % "0.5.1", // React DOM, HTML and SVG tags
    "me.shadaj" %%% "slinky-hot" % "0.5.1", // Hot loading, requires react-proxy package
    "org.scala-js" %%% "scalajs-java-time" % "0.2.5"
  ),
  scalaJSUseMainModuleInitializer := true,
  scalacOptions += "-P:scalajs:sjsDefinedByDefault",
  webpackDevServerExtraArgs := Seq("--inline", "--hot"),
  npmDependencies in Compile ++= Seq(
    "react" -> "16.5.2",
    "react-dom" -> "16.5.2",
    "react-proxy" -> "1.1.8",

    "apollo-boost" -> "0.1.16",
    "react-apollo" -> "2.2.2",
    "graphql-tag" -> "2.9.2",
    "graphql" -> "14.0.2",

    "react-jsonschema-form" -> "1.0.6"
  ),
  npmDevDependencies in Compile ++= Seq(
    "file-loader" -> "1.1.5",
    "style-loader" -> "0.19.0",
    "css-loader" -> "0.28.7",
    "html-webpack-plugin" -> "2.30.1",
    "copy-webpack-plugin" -> "4.2.0",
    "react-jsonschema-form" -> "1.0.6"
  )
).enablePlugins(ScalaJSPlugin, ScalaJSBundlerPlugin, ScalaJSWeb).
  dependsOn(sharedJs)

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("shared"))
  .settings(commonSettings)
lazy val sharedJvm = shared.jvm
lazy val sharedJs = shared.js

lazy val akkaVersion = "2.5.18"
lazy val akkaHttpVersion = "10.1.5"
lazy val circeVersion = "0.10.0"

lazy val networkDeps = List(
  // Akka libs
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-remote" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,

  // CORS
  "ch.megard" %% "akka-http-cors" % "0.3.0",

  // Persistent storage
  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",

  //  "com.github.andyglow" %% "websocket-scala-client" % "0.2.4" % Compile,
  "org.java-websocket" % "Java-WebSocket" % "1.3.8",

  "de.heikoseeberger" %% "akka-http-circe" % "1.20.0",

  // Pusher
  "com.pusher" % "pusher-java-client" % "1.8.1"
)

lazy val testDeps = List(
  "org.scalactic" %% "scalactic" % "3.0.5",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test"
)

lazy val graphQLServerDeps = List(
  "org.sangria-graphql" %% "sangria" % "1.4.2",
  "org.sangria-graphql" %% "sangria-circe" % "1.2.1",
  "org.sangria-graphql" %% "sangria-akka-streams" % "1.0.1"
)

lazy val dataStores = List(
  "net.openhft" % "chronicle-queue" % "5.17.1",
  "net.openhft" % "chronicle-map" % "3.16.4"
)

lazy val serviceDeps = List(
  "com.github.scopt" % "scopt_2.11" % "3.7.0",
  "com.typesafe" % "config" % "1.3.2" % Compile,
  // Metrics with prometheus
  "io.prometheus" % "simpleclient" % "0.3.0",
  "io.prometheus" % "simpleclient_httpserver" % "0.3.0"
)

lazy val timeSeriesDeps = List(
  "org.ta4j" % "ta4j-core" % "0.12"
)

lazy val statsDeps = List(
  "org.la4j" % "la4j" % "0.6.0"
)


lazy val commonSettings = Seq(
  scalaVersion := "2.12.5",
  version := "0.1.0",
  organization := "com.infixtrading",
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full),
  libraryDependencies ++= Seq(
    "io.circe" %%% "circe-core" % circeVersion,
    "io.circe" %%% "circe-generic" % circeVersion,
    "io.circe" %%% "circe-parser" % circeVersion,
    "io.circe" %%% "circe-optics" % circeVersion,
    "io.circe" %%% "circe-literal" % circeVersion
  )
)

// loads the server project at sbt startup
onLoad in Global := (onLoad in Global).value andThen {s: State => "project server" :: s}