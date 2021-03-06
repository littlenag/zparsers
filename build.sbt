/*
 * Copyright 2015 Daniel Spiewak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//import com.typesafe.sbt.SbtGit._

organization := "io.github.littlenag"

name := "zparsers" //"sparse"  zroutines?

scalaVersion := "2.12.10"

addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full)

resolvers += "Scalaz Bintray Repo" at "https://dl.bintray.com/scalaz/releases"

//val zioVersion = "1.0.0-RC20+43-6bbfac81-SNAPSHOT"
val zioVersion = "1.0.0-RC21-2"

libraryDependencies ++= Seq(
  "com.lihaoyi"            %% "sourcecode"      % "0.1.9",

  "org.scalaz"             %% "scalaz-core"     % "7.2.30",
  "org.scalaz.stream"      %% "scalaz-stream"   % "0.8.6a",

  // Coroutines!
  "io.github.littlenag"    %% "coroutines-impl" % "0.9-SNAPSHOT",

  "dev.zio"                %% "zio"             % zioVersion,
  "dev.zio"                %% "zio-streams"     % zioVersion,

  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",

  "co.fs2"                 %% "fs2-core"        % "2.2.1",

  "com.lihaoyi"            %% "fastparse"       % "2.2.2",

  "com.typesafe.akka"      %% "akka-stream"     % "2.6.5",

  "io.monix"               %% "monix"           % "3.2.1",
  "io.monix"               %% "monix-eval"      % "3.2.1",
  "io.monix"               %% "monix-tail"      % "3.2.1",

  "org.specs2"             %% "specs2-core"     % "4.9.3" % Test,
  "org.scalatest"          %% "scalatest"       % "3.1.1" % Test
)

scalacOptions in Test ++= Seq("-Yrangepos")

logBuffered in Test := false

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/"))

publishMavenStyle := true

//versionWithGit

// I prefer not to hinder my creativity by predicting the future
//git.baseVersion := "master"

//bintraySettings