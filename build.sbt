
import sbt.Keys._
import sbt._

lazy val ourLicense = Seq("Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0"))

lazy val publishSettings = Seq(
  organization := "ee.cone",
  version := "0.4.1",
  //name := "c4proto",
  //description := "Protobuf scalameta macros",
  publishMavenStyle := false,
  //publishArtifact in Test := false,
  bintrayOrganization := Some("conecenter2b"),  
  //bintrayOrganization in bintray.Keys.bintray := None,
  licenses := ourLicense,
  fork := true //looks like sbt hangs for a minute on System.exit
)

scalaVersion in ThisBuild := "2.11.8"

////////////////////////////////////////////////////////////////////////////////
// from https://github.com/scalameta/sbt-macro-example/blob/master/build.sbt

lazy val metaMacroSettings: Seq[Def.Setting[_]] = Seq(
  ivyConfigurations += config("compileonly").hide,
  libraryDependencies += "org.scalameta" %% "scalameta" % "1.4.0.544" % "compileonly",
  unmanagedClasspath in Compile ++= update.value.select(configurationFilter("compileonly")),
  // New-style macro annotations are under active development.  As a result, in
  // this build we'll be referring to snapshot versions of both scala.meta and
  // macro paradise.
  resolvers += Resolver.url(
    "scalameta",
    url("http://dl.bintray.com/scalameta/maven"))(Resolver.ivyStylePatterns),
  // A dependency on macro paradise 3.x is required to both write and expand
  // new-style macros.  This is similar to how it works for old-style macro
  // annotations and a dependency on macro paradise 2.x.
  addCompilerPlugin(
    "org.scalameta" % "paradise" % "3.0.0.132" cross CrossVersion.full),
  scalacOptions += "-Xplugin-require:macroparadise",
  // temporary workaround for https://github.com/scalameta/paradise/issues/10
  scalacOptions in (Compile, console) := Seq(), // macroparadise plugin doesn't work in repl yet.
  // temporary workaround for https://github.com/scalameta/paradise/issues/55
  sources in (Compile, doc) := Nil // macroparadise doesn't work with scaladoc yet.
  
)

////////////////////////////////////////////////////////////////////////////////



lazy val descr = "C4 framework"

lazy val `c4proto-macros` = project.settings(publishSettings ++ metaMacroSettings)
  .settings(description := s"$descr / scalameta macros to generate Protobuf adapters for case classes")
lazy val `c4proto-api` = project.settings(publishSettings)
  .settings(description := s"$descr / runtime dependency for generated Protobuf adapters")
  .settings(libraryDependencies += "com.squareup.wire" % "wire-runtime" % "2.2.0")

lazy val `c4proto-types` = project.settings(publishSettings)
  .settings(description := s"$descr / additional data types to use in messages")
  .settings(metaMacroSettings).dependsOn(`c4proto-macros`,`c4proto-api`)
lazy val `c4gate-proto` = project.settings(publishSettings)
  .settings(description := s"$descr / http message definitions")
  .settings(metaMacroSettings).dependsOn(`c4proto-macros`,`c4proto-api`)

lazy val `c4actor-base` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(metaMacroSettings).dependsOn(`c4proto-macros`,`c4proto-api`)

lazy val `c4actor-base-examples` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(metaMacroSettings)
  .dependsOn(`c4actor-base`,`c4proto-types`)

lazy val `c4actor-kafka` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(libraryDependencies += "org.apache.kafka" % "kafka-clients" % "0.10.1.0")
  .dependsOn(`c4actor-base`)

lazy val `c4gate-server` = project.settings(publishSettings)
  .settings(description := s"$descr / http/tcp gate server to kafka")
  .settings(libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.21")
  .enablePlugins(JavaServerAppPackaging)
  .dependsOn(`c4gate-proto`, `c4actor-kafka`)

lazy val `c4gate-consumer-example` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4actor-kafka`, `c4gate-proto`)
  .settings(metaMacroSettings).dependsOn(`c4proto-macros`,`c4proto-api`)
  .enablePlugins(JavaServerAppPackaging)

lazy val `c4gate-sse` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4gate-proto`, `c4actor-base`)

lazy val `c4gate-sse-example` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4actor-kafka`, `c4gate-sse`)
  .settings(metaMacroSettings).dependsOn(`c4proto-macros`,`c4proto-api`)

lazy val `c4vdom-base` = project.settings(publishSettings)

//publishArtifact := false -- bintrayEnsureBintrayPackageExists fails if this
lazy val `c4proto-aggregate` = project.in(file(".")).settings(publishSettings).aggregate(
  `c4actor-base`,
  `c4actor-base-examples`,
  `c4actor-kafka`,
  `c4gate-consumer-example`,
  `c4gate-proto`,
  `c4gate-server`,
  `c4gate-sse`,
  `c4gate-sse-example`,
  `c4proto-api`,
  `c4proto-macros`,
  `c4proto-types`
)




