name := """DataLogger"""

version := "1.1.37"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.12"

libraryDependencies ++= Seq(
  ws,
  "com.github.nscala-time" %% "nscala-time" % "2.16.0",
  "org.mongodb.scala" %% "mongo-scala-driver" % "2.2.1",
  "com.github.tototoshi" %% "scala-csv" % "1.3.5",
  "net.sf.marineapi" % "marineapi" % "0.10.0"
)

mappings in Universal ++=
(baseDirectory.value / "report_template" * "*" get) map
    (x => x -> ("report_template/" + x.getName))

mappings in Universal ++=
(baseDirectory.value / "importEPA" * "*" get) map
    (x => x -> ("importEPA/" + x.getName))
	
mappings in Universal ++= 
 List(file("public/css/bootstrap.min.css") -> "public/css/bootstrap.min.css",
 	file("public/css/aqm.css") -> "public/css/aqm.css"
 )
 	
//libraryDependencies += "com.google.guava" % "guava" % "19.0"

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"

scalacOptions ++= Seq("-feature")

fork in run := false