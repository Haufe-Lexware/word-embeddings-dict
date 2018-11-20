name := "EmbeddingsDict"
version := "0.1"
scalaVersion := "2.11.12"

lazy val akkaKryoSerializerUpdated =
  RootProject(uri("https://github.com/redsk/akka-kryo-serialization.git"))

lazy val root = (project in file(".")).dependsOn(akkaKryoSerializerUpdated)

// akka
libraryDependencies ++= {
  val akkaVer = "2.5.11"
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaVer,
    "com.typesafe.akka" %% "akka-remote" % akkaVer,
    "com.typesafe.akka" %% "akka-testkit" % akkaVer % Test
  )
}

// spark
libraryDependencies ++= {
  val sparkVer = "2.4.0"
  Seq(
    "org.apache.spark" %% "spark-core" % sparkVer,
    "org.apache.spark" %% "spark-sql" % sparkVer,
    "org.apache.spark" %% "spark-mllib" % sparkVer
  )
}

// blas
libraryDependencies += "com.github.fommil.netlib" % "all" % "1.1.2" pomOnly()

// sbt-native-packager
enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

import com.typesafe.sbt.packager.docker._

// java 8 is needed for Spark
dockerBaseImage := "openjdk:8-jdk"

// installing blas libraries system-wide
dockerCommands ++= Seq(
  Cmd("USER", "root"),
  Cmd("RUN", "apt-get update"),
  Cmd("RUN", "apt-get install -y libgfortran3 libatlas3-base libopenblas-base"),
  Cmd("RUN", "update-alternatives --set libblas.so.3  /usr/lib/openblas-base/libblas.so.3"),
  Cmd("RUN", "update-alternatives --set liblapack.so.3 /usr/lib/openblas-base/liblapack.so.3")

//  The following ones were tried as well and openblas gave better performance
//  Cmd("RUN", "update-alternatives --set libblas.so.3  /usr/lib/atlas-base/atlas/libblas.so.3"),
//  Cmd("RUN", "update-alternatives --set liblapack.so.3 /usr/lib/atlas-base/atlas/liblapack.so.3")
)
