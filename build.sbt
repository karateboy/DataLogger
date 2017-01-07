name := """DataLogger"""

version := "1.0"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  specs2 % Test,
  "com.github.nscala-time" %% "nscala-time" % "2.10.0",
//  "org.scalikejdbc" %% "scalikejdbc"                  % "2.3.1",
//  "org.scalikejdbc" %% "scalikejdbc-config"           % "2.3.1",
//  "org.scalikejdbc" %% "scalikejdbc-play-initializer" % "2.4.3", 
  "org.json4s" %% "json4s-native" % "3.3.0",
  "org.json4s" %% "json4s-ext" % "3.3.0", 
  "org.mongodb.scala" %% "mongo-scala-driver" % "1.1.0"
)

mappings in Universal ++=
(baseDirectory.value / "report_template" * "*" get) map
    (x => x -> ("report_template/" + x.getName))
	
//libraryDependencies += "com.google.guava" % "guava" % "19.0"

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

scalacOptions ++= Seq("-feature")

fork in run := false