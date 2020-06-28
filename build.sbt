name := "SipgateContactSync"

version := "0.1"

scalaVersion := "2.13.3"

val playWsVersion = "2.1.2"

libraryDependencies ++= Seq(
    "com.typesafe.play" %% "play-json" % "2.9.0",
    "com.typesafe.play" %% "play-ahc-ws-standalone" % playWsVersion,
    "com.typesafe.play" %% "play-ws-standalone-json" % playWsVersion,
    "org.apache.directory.api" % "api-all" % "2.0.1",
    "com.googlecode.libphonenumber" % "libphonenumber" % "8.12.6",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
)

scalacOptions ++= Seq("-unchecked", "-deprecation")

assemblyJarName := "contacts2ldap.jar"
mainClass := Some("tel.schich.sipgatecontactsync.Main")

assemblyMergeStrategy in assembly := {
    case PathList("OSGI-INF", _) => MergeStrategy.discard
    case p =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(p)
}
