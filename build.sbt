name := "SipgateContactSync"

version := "0.1"

scalaVersion := "3.4.2"

val playWsVersion = "2.2.7"

libraryDependencies ++= Seq(
    "com.typesafe.play" %% "play-json" % "2.10.5",
    "com.typesafe.play" %% "play-ahc-ws-standalone" % playWsVersion,
    "com.typesafe.play" %% "play-ws-standalone-json" % playWsVersion,
    "org.apache.directory.api" % "api-all" % "2.1.6",
    "com.googlecode.libphonenumber" % "libphonenumber" % "8.13.37",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
    "ch.qos.logback" % "logback-classic" % "1.5.6",
)

Compile / mainClass := Some("tel.schich.sipgatecontactsync.Main")

jibRegistry := "ghcr.io"
jibOrganization := "pschichtel"
jibName := "contacts2ldap"
jibBaseImage := "docker.io/library/eclipse-temurin:21-jre-alpine"
