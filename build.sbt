name := "SipgateContactSync"

version := "0.2"

scalaVersion := "3.4.2"

libraryDependencies ++= Seq(
    "org.apache.directory.api" % "api-all" % "2.1.6",
    "com.googlecode.libphonenumber" % "libphonenumber" % "8.13.37",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
    "ch.qos.logback" % "logback-classic" % "1.5.6",
    "com.dripower" %% "play-circe" % "3014.1",
    "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2",
)

Compile / mainClass := Some("tel.schich.sipgatecontactsync.Main")

jibRegistry := "ghcr.io"
jibOrganization := "pschichtel"
jibName := "contacts2ldap"
jibBaseImage := "docker.io/library/eclipse-temurin:21-jre-alpine"
