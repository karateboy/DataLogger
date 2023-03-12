name := """DataLogger"""

version := "1.1.27-tsmc"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  ws,
  "com.github.nscala-time" %% "nscala-time" % "2.16.0",
  "net.sf.marineapi" % "marineapi" % "0.10.0",
  "com.github.tototoshi" %% "scala-csv" % "1.3.5"
)

// https://mvnrepository.com/artifact/org.mongodb.scala/mongo-scala-driver
libraryDependencies += "org.mongodb.scala" %% "mongo-scala-driver" % "2.9.0"


mappings in Universal ++=
(baseDirectory.value / "report_template" * "*" get) map
    (x => x -> ("report_template/" + x.getName))

mappings in Universal ++=
(baseDirectory.value / "importEPA" * "*" get) map
    (x => x -> ("importEPA/" + x.getName))

scalacOptions ++= Seq("-feature")

fork in run := false