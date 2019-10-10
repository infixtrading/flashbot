import ReleaseTransformations._
import scala.xml.{ Elem, Node => XmlNode, NodeSeq => XmlNodeSeq }
import scala.xml.transform.{ RewriteRule, RuleTransformer }

/**
  * How to run from the command line with options:
  * Example assumes there is a MyMainApp object in the classpath.
  *
  * $ sbt '; set javaOptions += "-Dflashbot.db=postgres" ; runMain MyMainApp'
  * 
  * $ sbt '; set javaOptions += "-Dflashbot.db=postgres"; set javaOptions += "-Dakka.loglevel=INFO" ; runMain examples.CoinbaseIngest'
  */

lazy val scala212 = "2.12.8"
lazy val scala213 = "2.13.0"

organization in ThisBuild := "com.infixtrading"
parallelExecution in ThisBuild := false
scalaVersion in ThisBuild := scala212

lazy val akkaVersion = "2.5.19"
lazy val akkaHttpVersion = "10.1.5"
lazy val fbCirceVersion = "0.10.0"

lazy val akkaDeps = List(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-remote" % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
)

lazy val networkDeps = List(
  "com.typesafe.akka" %% "akka-slf4j" % "2.5.19",
  "ch.qos.logback" % "logback-classic" % "1.2.3",

  // CORS
  "ch.megard" %% "akka-http-cors" % "0.3.0",

  "org.java-websocket" % "Java-WebSocket" % "1.3.9",
  "com.softwaremill.sttp" %% "core" % "1.5.7",
  "com.softwaremill.sttp" %% "okhttp-backend" % "1.5.7",

  // Warning! Changing this to a newer version may cause a conflict with the circe version (0.10.0)
  "de.heikoseeberger" %% "akka-http-circe" % "1.20.0",

  // Pusher
  "com.pusher" % "pusher-java-client" % "1.8.1"
)

lazy val jsonDeps = List(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-optics",
  "io.circe" %% "circe-literal",
  "io.circe" %% "circe-generic-extras",
).map(_ % fbCirceVersion)

lazy val compatDeps = List(
  "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.2" % Compile
)

lazy val dataStores = List(
  "net.openhft" % "chronicle-queue" % "5.17.1",
  "net.openhft" % "chronicle-map" % "3.16.4",

  "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
  "org.iq80.leveldb"          % "leveldb"          % "0.7",
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8"
)

lazy val configDeps = List(
  "com.typesafe" % "config" % "1.3.2" % Compile,
  "io.circe" %% "circe-config" % "0.5.0"
)

lazy val serviceDeps = configDeps ++ List(
  // Metrics with prometheus
  "io.prometheus" % "simpleclient" % "0.3.0",
  "io.prometheus" % "simpleclient_httpserver" % "0.3.0"
)

lazy val timeSeriesDeps = List(
  // Time series
  "org.ta4j" % "ta4j-core" % "0.12"
)

lazy val statsDeps = List(
  "org.la4j" % "la4j" % "0.6.0",
  "org.scalanlp" %% "breeze" % "1.0-RC2",
  "org.scalanlp" %% "breeze-natives" % "1.0-RC2"
)

lazy val utilDeps = List(
  "com.github.agourlay" %% "json-2-csv" % "0.4.2"
)

val compilerOptions = Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-unchecked",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Xfuture",
  "-explaintypes",
  "-Ywarn-unused-import"
)

val paradiseVersion = "2.1.1"
val scalaTestVersion = "3.0.5"
val scalaCheckVersion = "1.13.5"

/**
 * Some terrible hacks to work around Cats's decision to have builds for
 * different Scala versions depend on different versions of Discipline, etc.
 */
def priorTo2_13(scalaVersion: String): Boolean =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, minor)) if minor < 13 => true
    case _                              => false
  }

def scalaTestVersionFor(scalaVersion: String): String =
  if (priorTo2_13(scalaVersion)) scalaTestVersion else "3.0.6-SNAP2"

def scalaCheckVersionFor(scalaVersion: String): String =
  if (priorTo2_13(scalaVersion)) scalaCheckVersion else "1.14.0"

val previousFBVersion = None

lazy val baseSettings = Seq(
  scalacOptions ++= compilerOptions,
  scalacOptions in (Compile, console) ~= {
    _.filterNot(Set("-Ywarn-unused-import"))
  },
  scalacOptions in (Test, console) ~= {
    _.filterNot(Set("-Ywarn-unused-import"))
  },
  scalacOptions in Tut ~= {
    _.filterNot(Set("-Ywarn-unused-import"))
  },
  resolvers ++= Seq(
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots"),
    "releases" at "http://nexus.tundra.com/repository/maven-releases/"
  ),
  (scalastyleSources in Compile) ++= (unmanagedSourceDirectories in Compile).value,
  ivyConfigurations += CompileTime.hide,
  unmanagedClasspath in Compile ++= update.value.select(configurationFilter(CompileTime.name)),
  unmanagedClasspath in Test ++= update.value.select(configurationFilter(CompileTime.name)),

  libraryDependencies ++= (jsonDeps ++ utilDeps ++ compatDeps),
  crossScalaVersions := List(scala212)
) ++ macroSettings

lazy val allFBSettings = baseSettings ++ publishSettings

def flashbotProject(path: String)(project: Project) = {
  val docName = path.split("-").mkString(" ")
  project.settings(
    description := s"flashbot $docName",
    moduleName := s"flashbot-$path",
    name := s"Flashbot $docName",
    allFBSettings
  )
}

//
//def crossModule(path: String, mima: Option[String], crossType: CrossType = CrossType.Full) = {
//  val id = path.split("-").reduce(_ + _.capitalize)
//  CrossProject(id, file(s"modules/$path"))(JVMPlatform, JSPlatform)
//    .crossType(crossType)
//    .settings(allFBSettings)
//    .configure(flashbotProject(path))
//    .jvmSettings(
//      mimaPreviousArtifacts := mima.map("com.infixtrading" %% moduleName.value % _).toSet
//    )
//}

def flashbotModule(path: String, mima: Option[String]): Project = {
  val id = path.split("-").reduce(_ + _.capitalize)
  Project(id, file(s"modules/$path"))
    .configure(flashbotProject(path))
    .settings(mimaPreviousArtifacts := mima.map("com.infixtrading" %% moduleName.value % _).toSet)
}

def standaloneModule(path: String, mima: Option[String]): Project = {
  val id = path.split("-").reduce(_ + _.capitalize)
  Project(id, file(s"modules/$path"))
//    .configure(flashbotProject(path))
//    .settings(mimaPreviousArtifacts := mima.map("com.infixtrading" %% moduleName.value % _).toSet
//    )
}

lazy val docSettings = allFBSettings ++ Seq(
  micrositeName := "flashbot",
  micrositeDescription := " A Java CryptoCurrency trading engine",
  micrositeAuthor := "Alex Lopatin",
  micrositeHighlightTheme := "atom-one-light",
  micrositeHomepage := "https://infixtrading.github.io/flashbot/",
  micrositeBaseUrl := "flashbot",
  micrositeDocumentationUrl := "api",
  micrositeGithubOwner := "infixtrading",
  micrositeGithubRepo := "flashbot",
//  micrositeExtraMdFiles := Map(file("CONTRIBUTING.md") -> ExtraMdFileConfig("contributing.md", "docs")),
  micrositePalette := Map(
    "brand-primary" -> "#5B5988",
    "brand-secondary" -> "#292E53",
    "brand-tertiary" -> "#222749",
    "gray-dark" -> "#49494B",
    "gray" -> "#7B7B7E",
    "gray-light" -> "#E5E5E6",
    "gray-lighter" -> "#F4F3F4",
    "white-color" -> "#FFFFFF"
  ),
//  micrositeConfigYaml := ConfigYml(yamlInline = s"""
//      |scalafiddle:
//      |  dependency: io.circe %%% circe-core % $scalaFiddleFlashbotVersion,io.circe %%% circe-generic % $scalaFiddleFlashbotVersion,io.circe %%% circe-parser % $scalaFiddleFlashbotVersion
//    """.stripMargin),
  addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), micrositeDocumentationUrl),
  ghpagesNoJekyll := true,
  scalacOptions in (ScalaUnidoc, unidoc) ++= Seq(
    "-groups",
    "-implicits",
    "-skip-packages",
    "scalaz",
    "-doc-source-url",
    scmInfo.value.get.browseUrl + "/tree/master€{FILE_PATH}.scala",
    "-sourcepath",
    baseDirectory.in(LocalRootProject).value.getAbsolutePath,
    "-doc-root-content",
    (resourceDirectory.in(Compile).value / "rootdoc.txt").getAbsolutePath
  ),
  git.remoteRepo := "git@github.com:infixtrading/flashbot.git",
//  unidocProjectFilter in (ScalaUnidoc, unidoc) :=
//    inAnyProject -- inProjects(noDocProjects(scalaVersion.value): _*),
  includeFilter in makeSite := "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.svg" |
    "*.js" | "*.swf" | "*.yml" | "*.md"
)

//lazy val docs = project
//  .dependsOn(fbcore)
//  .settings(
//    moduleName := "flashbot-docs",
//    name := "Flashbot docs",
//    crossScalaVersions := crossScalaVersions.value.filterNot(_.startsWith("2.13")),
////    libraryDependencies += "io.circe" %% "circe-optics" % "0.10.0"
//  )
//  .settings(docSettings)
//  .settings(noPublishSettings)
//  .settings(macroSettings)
//  .enablePlugins(GhpagesPlugin)
//  .enablePlugins(MicrositesPlugin)
//  .enablePlugins(ScalaUnidocPlugin)

//lazy val crossModules = Seq[(Project, Project)](
//  (core, coreJS),
////  (testing, testingJS),
//  (tests, testsJS),
//)

//lazy val jsModules = Seq[Project](scalajs)
//lazy val jvmModules = Seq[Project](server, client, testing)
//lazy val fbDocsModules = Seq[Project](docs)

lazy val jvmProjects: Seq[Project] = Seq[Project](server, testing, tests, tools)

//lazy val jsProjects: Seq[Project] =
//  (crossModules.map(_._2) ++ jsModules)
//
//lazy val aggregatedProjects: Seq[ProjectReference] = (
//  crossModules.flatMap(cp => Seq(cp._1, cp._2)) ++
//    jsModules ++ jvmModules
//).map(p => p: ProjectReference)

lazy val macroSettings: Seq[Setting[_]] = Seq(
  libraryDependencies ++= Seq(
    scalaOrganization.value % "scala-compiler" % scalaVersion.value % Provided,
    scalaOrganization.value % "scala-reflect" % scalaVersion.value % Provided
  ) ++ (
    if (priorTo2_13(scalaVersion.value)) {
      Seq(
        compilerPlugin("org.scalamacros" % "paradise" % paradiseVersion cross CrossVersion.patch)
      )
    } else Nil
  )
)

// rootproj
lazy val flashbot = project
  .in(file("."))
  .settings(allFBSettings)
  .settings(noPublishSettings)
  .settings(
    crossScalaVersions := Nil,
    initialCommands in console :=
      """
        |import scala.concurrent._ 
        |import scala.concurrent.duration._
        |import scala.concurrent.ExecutionContext.Implicits.global
        |import scala.language.postfixOps
        |
        |import akka.actor.ActorSystem
        |import akka.stream.ActorMaterializer
        |
        |import flashbot.client.FlashbotClient
        |import flashbot.core._
        |import flashbot.models._
        |
        |// import console.Console._
      """.stripMargin
  )
  .settings(fork in run := true)
  .aggregate(core, server, testing, tools)
  .dependsOn(core, server, testing, tools)

//lazy val numbersTestingBase = circeCrossModule("numbers-testing", previousCirceVersion, CrossType.Pure).settings(
//  libraryDependencies += "org.scalacheck" %%% "scalacheck" % scalaCheckVersionFor(scalaVersion.value),
//  coverageExcludedPackages := "io\\.circe\\.numbers\\.testing\\..*"
//)
//
//lazy val numbersTesting = numbersTestingBase.jvm
//lazy val numbersTestingJS = numbersTestingBase.js

//lazy val numbersBase = circeCrossModule("numbers", previousCirceVersion)
//  .settings(
//    libraryDependencies ++= Seq(
//      "org.scalacheck" %%% "scalacheck" % scalaCheckVersionFor(scalaVersion.value) % Test,
//      "org.scalatest" %%% "scalatest" % scalaTestVersionFor(scalaVersion.value) % Test
//    )
//  )
//  .dependsOn(numbersTestingBase % Test)
//
//lazy val numbers = numbersBase.jvm
//lazy val numbersJS = numbersBase.js

//lazy val coreBase = circeCrossModule("core", previousCirceVersion)
//  .settings(
//    libraryDependencies += "org.typelevel" %%% "cats-core" % catsVersion,
//    sourceGenerators in Compile += (sourceManaged in Compile).map(Boilerplate.gen).taskValue,
//    Compile / unmanagedSourceDirectories ++= {
//      val baseDir = baseDirectory.value
//      def extraDirs(suffix: String) =
//        CrossType.Full.sharedSrcDir(baseDir, "main").toList.map(f => file(f.getPath + suffix))
//      CrossVersion.partialVersion(scalaVersion.value) match {
//        case Some((2, minor)) if minor <= 12 => extraDirs("-2.12-")
//        case Some((2, minor)) if minor >= 13 => extraDirs("-2.13+")
//        case _                               => Nil
//      }
//    }
//  )
//  .jvmSettings(
//    Compile / unmanagedSourceDirectories ++= {
//      val baseDir = baseDirectory.value
//      def extraDirs(suffix: String) =
//        CrossType.Full.sharedSrcDir(baseDir, "main").toList.map(f => file(f.getPath + suffix))
//      CrossVersion.partialVersion(scalaVersion.value) match {
//        case Some((2, minor)) if minor <= 11 => extraDirs("-no-jdk8")
//        case Some((2, minor)) if minor >= 12 => extraDirs("-with-jdk8")
//        case _                               => Nil
//      }
//    }
//  )
//  .jsSettings(
//    Compile / unmanagedSourceDirectories ++= {
//      val baseDir = baseDirectory.value
//      def extraDirs(suffix: String) =
//        CrossType.Full.sharedSrcDir(baseDir, "main").toList.map(f => file(f.getPath + suffix))
//      extraDirs("-no-jdk8")
//    }
//  )
//  .dependsOn(numbersBase)
//
//lazy val core = coreBase.jvm
//lazy val coreJS = coreBase.js



//lazy val coreBase = crossModule("core", previousFBVersion)
lazy val core = flashbotModule("core", previousFBVersion).settings(
  libraryDependencies ++= (configDeps ++ akkaDeps ++ timeSeriesDeps ++ 
        networkDeps ++ serviceDeps ++ statsDeps ++ Seq(
    "com.github.andyglow" % "scala-jsonschema-core_2.12" % "0.0.8",
    "com.github.andyglow" % "scala-jsonschema-api_2.12" % "0.0.8",
    "com.github.andyglow" % "scala-jsonschema-circe-json_2.12" % "0.0.8",
    "com.voxsupplychain" %% "json-schema-parser" % "0.12.1",
    "org.jgrapht" % "jgrapht" % "1.3.0",
    "org.jgrapht" % "jgrapht-core" % "1.3.0",
    "org.jgrapht" % "jgrapht-io" % "1.3.0",
    "com.quantego" % "clp-java" % "1.16.10",
    "com.vmunier" %% "scalajs-scripts" % "1.1.2",
    "com.github.inamik.text.tables" % "inamik-text-tables" % "0.8",
    "com.lihaoyi" %% "fansi" % "0.2.5",
    "de.sciss" %% "fingertree" % "1.5.4",
    "com.twitter" %% "chill-akka" % "0.9.3",

    // High performance data structures
    "org.spire-math" %% "debox" % "0.8.0",
    "it.unimi.dsi" % "fastutil" % "8.2.2",
    
    // For Java support
    "com.fasterxml.jackson.core" % "jackson-core" % "2.9.8",
    "com.kjetland" %% "mbknor-jackson-jsonschema" % "1.0.32",

    "com.lihaoyi" %% "pprint" % "0.5.5"
  ))
)
//lazy val coreJS = coreBase.js


lazy val server = flashbotModule("server", previousFBVersion).settings(
  libraryDependencies ++= (
    dataStores ++ Seq(
      "com.typesafe.slick" %% "slick" % "3.2.3",
      "com.typesafe.slick" %% "slick-hikaricp" % "3.2.3",
      "com.lightbend.akka" %% "akka-stream-alpakka-slick" % "1.0-M2",
      "com.h2database" % "h2" % "1.4.192",
      "org.postgresql" % "postgresql" % "42.2.5"
    ))
).dependsOn(core)


lazy val tools = flashbotModule("tools", previousFBVersion)
  .settings(
    // Charting
    libraryDependencies ++= Seq(
      "de.sciss" %% "scala-chart" % "0.7.1",
      "org.scalafx" %% "scalafx" % "12.0.1-R17",
      "org.jfree" % "jfreechart" % "1.5.0",
      "org.jfree" % "jfreechart-fx" % "1.0.1",
      "org.gerweck.scalafx" %% "scalafx-utils" % "0.14.0"
    )
  )
  .aggregate(core, server)
  .dependsOn(core, server)


//lazy val toolsnew = standaloneModule("toolsnew", previousFBVersion)
//  .settings(
//    scalaVersion := scala213,
//    crossScalaVersions := List(scala212, scala213),
//    crossPaths := false,
////    autoScalaLibrary := false,
//    libraryDependencies ++= Seq(
//      "org.jfree" % "jfreechart" % "1.5.0",
//      "org.jfree" % "jfreechart-fx" % "1.0.1",
//      "de.sciss" %% "scala-chart" % "0.7.1",
//      "org.scalafx" % "scalafx_2.12" % "12.0.1-R17"
//    )
//  )


//lazy val scalajs = flashbotModule("scalajs", None).enablePlugins(ScalaJSPlugin).dependsOn(coreJS)

lazy val testing = flashbotModule("testing", previousFBVersion)
  .settings(noPublishSettings: _*)
  .settings(
//    libraryDependencies += "org.vegas-viz" %% "vegas" % "0.3.11"
  ).dependsOn(core)

//lazy val testing = testingBase.jvm.dependsOn(server, client)
//lazy val testingJS = testingBase.js

lazy val tests = flashbotModule("tests", previousFBVersion)
  .settings(noPublishSettings: _*)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalactic" %% "scalactic" % "3.0.5",
      "org.scalatest" %% "scalatest" % "3.0.5" % "test",
      "org.scalacheck" %% "scalacheck" % scalaCheckVersion,
      "de.sciss" %% "scala-chart" % "0.6.0",
      "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test
    )
//    sourceGenerators in Test += (sourceManaged in Test).map(Boilerplate.genTests).taskValue,
//    unmanagedResourceDirectories in Compile +=
//      file("modules/tests") / "shared" / "src" / "main" / "resources"
  )
  .dependsOn(core, server, testing)
  .aggregate(core, server, testing)

//lazy val tests = testsBase.jvm.dependsOn(server, client)
//lazy val testsJS = testsBase.js.dependsOn(scalajs)

lazy val publishSettings = Seq(
//  releaseCrossBuild := true,
//  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  homepage := Some(url("https://github.com/infixtrading/flashbot")),
  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ =>
    false
  },
//  publishTo := {
////    val nexus = "https://oss.sonatype.org/"
//    val bintray = "https://api.bintray.com/"
//    if (isSnapshot.value)
//      Some("snapshots" at bintray + "content/repositories/snapshots")
//    else
//      Some("releases" at bintray + "service/local/staging/deploy/maven2")
//  },
  bintrayOrganization := Some("infixtrading"),
  bintrayRepository := "flashbot",
  autoAPIMappings := true,
  apiURL := Some(url("https://infixtrading.com/flashbot/api")),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/infixtrading/flashbot"),
      "scm:git:git@github.com:infixtrading/flashbot.git"
    )
  ),
  developers := List(
    Developer("lopatin", "Alex Lopatin", "aleksander.lopatin@gmail.com", url("https://github.com/lopatin"))
  ),
  pomPostProcess := { (node: XmlNode) =>
    new RuleTransformer(
      new RewriteRule {
        private def isTestScope(elem: Elem): Boolean =
          elem.label == "dependency" && elem.child.exists(child => child.label == "scope" && child.text == "test")

        override def transform(node: XmlNode): XmlNodeSeq = node match {
          case elem: Elem if isTestScope(elem) => Nil
          case _                               => node
        }
      }
    ).transform(node).head
  }
)


lazy val noPublishSettings = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

//credentials ++= (
//  for {
//    username <- Option(System.getenv().build("SONATYPE_USERNAME"))
//    password <- Option(System.getenv().build("SONATYPE_PASSWORD"))
//  } yield
//    Credentials(
//      "Sonatype Nexus Repository Manager",
//      "oss.sonatype.org",
//      username,
//      password
//    )
//).toSeq

lazy val CompileTime = config("compile-time")

val jvmTestProjects = jvmProjects
//val jsTestProjects = jsProjects.filterNot(Set(scalajs))

//val formatCommands = ";scalafmtCheck;test:scalafmtCheck;scalafmtSbtCheck;scalastyle"
val formatCommands = ""

addCommandAlias("buildJVM", jvmProjects.map(";" + _.id + "/compile").mkString)
addCommandAlias(
  "validateJVM",
  ";buildJVM" + jvmTestProjects.map(";" + _.id + "/test").mkString + formatCommands
)
//addCommandAlias("buildJS", jsProjects.map(";" + _.id + "/compile").mkString)
//addCommandAlias(
//  "validateJS",
//  ";buildJS" + jsTestProjects.map(";" + _.id + "/test").mkString + formatCommands
//)
addCommandAlias("validate", ";validateJVM;validateJS")
