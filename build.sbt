name := "SipgateContactSync"

version := "0.1"

scalaVersion := "2.13.1"

val playWsVersion = "2.0.7"

libraryDependencies ++= Seq(
    "com.typesafe.play" %% "play-json" % "2.7.4",
    "com.typesafe.play" %% "play-ahc-ws-standalone" % playWsVersion,
    "com.typesafe.play" %% "play-ws-standalone-json" % playWsVersion,
    "org.apache.directory.api" % "api-all" % "2.0.0.AM4",
    "com.googlecode.libphonenumber" % "libphonenumber" % "8.10.20",
)
