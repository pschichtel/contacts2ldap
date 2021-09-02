name := "SipgateContactSync"

version := "0.1"

scalaVersion := "2.13.6"

val playWsVersion = "2.1.3"

libraryDependencies ++= Seq(
    "com.typesafe.play" %% "play-json" % "2.9.2",
    "com.typesafe.play" %% "play-ahc-ws-standalone" % playWsVersion,
    "com.typesafe.play" %% "play-ws-standalone-json" % playWsVersion,
    "org.apache.directory.api" % "api-all" % "2.1.0",
    "com.googlecode.libphonenumber" % "libphonenumber" % "8.12.31",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    "ch.qos.logback" % "logback-classic" % "1.2.5",
)

scalacOptions ++= Seq("-unchecked", "-deprecation")

mainClass := Some("tel.schich.sipgatecontactsync.Main")

jibOrganization := "pschichtel"
jibName := "contacts2ldap"

jibBaseImage := "adoptopenjdk/openjdk15:alpine-slim"
