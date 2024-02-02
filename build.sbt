import scala.collection.immutable.Seq

ThisBuild / name := "nanobank"
ThisBuild / organization := "bmaso"
ThisBuild  /  idePackagePrefix := Some("bmaso")
ThisBuild / version := "0.0.1-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.8"

Compile / mainClass := Some("bmaso.NanobankMainApp")

libraryDependencies ++= Seq(
  "com.typesafe"  %  "config"              % "1.4.3",
  "dev.zio"       %% "zio"                 % "2.0.19",
  "dev.zio"       %% "zio-json"            % "0.6.2",
  "dev.zio"       %% "zio-http"            % "3.0.0-RC2",
  "dev.zio"       %% "zio-jdbc"            % "0.1.2",
  "mysql"         % "mysql-connector-java" % "8.0.33"
)

//...might need to make additional endpoints avail on another port, esp for transaction status updates...
Docker / dockerExposedPorts := Seq(9000)

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)
