/*
 * Copyright 2014 pl.project13.scala
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

package pl.project13.scala.sbt

import java.util.Properties

import sbt._
import sbt.Keys._
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator
import sbt.KeyRanks.AMinusSetting

import scala.tools.nsc.util.ScalaClassLoader.URLClassLoader

object JmhPlugin extends AutoPlugin {

  object JmhKeys {
    val Jmh = config("jmh") extend Test
    val generatorType = settingKey[String]("Benchmark code generator type. Available: `default`, `reflection` or `asm`.")
  }

  import JmhKeys._

  val autoImport = JmhKeys

  val generateJmhSourcesAndResources = taskKey[(Seq[File], Seq[File])]("Generate benchmark JMH Java code and resources")

  /** All we need is Java. */
  override def requires = plugins.JvmPlugin

  /** Plugin must be enabled on the benchmarks project. See http://www.scala-sbt.org/0.13/tutorial/Using-Plugins.html */
  override def trigger = noTrigger

  override def projectConfigurations = Seq(Jmh)

  override def projectSettings = inConfig(Jmh)(Defaults.testSettings ++ Seq(
    // settings in Jmh
    version := jmhVersionFromProps(),
    generatorType := "default",

    mainClass in run := Some("org.openjdk.jmh.Main"),
    fork in run := true, // makes sure that sbt manages classpath for JMH when forking
    // allow users to configure another classesDirectory like e.g. test:classDirectory
    classDirectory := (classDirectory in Compile).value,
    dependencyClasspath := (dependencyClasspath in Compile).value,

    resourceDirectory := (resourceDirectory in Compile).value,
    sourceGenerators := Seq(Def.task { generateJmhSourcesAndResources.value._1 }.taskValue),
    resourceGenerators := Seq(Def.task { generateJmhSourcesAndResources.value._2 }.taskValue),
    generateJmhSourcesAndResources := generateBenchmarkSourcesAndResources(streams.value, crossTarget.value / "jmh-cache", (classDirectory in Jmh).value, sourceManaged.value, resourceManaged.value, generatorType.value, (dependencyClasspath in Jmh).value, new Run(scalaInstance.value, true, taskTemporaryDirectory.value)),
    generateJmhSourcesAndResources := (generateJmhSourcesAndResources dependsOn(compile in Compile)).value
  )) ++ Seq(
    // settings in default

    // includes the asm jar only if needed
    libraryDependencies ++= {
      val jmhV = (version in Jmh).value

      Seq(
        "org.openjdk.jmh"     % "jmh-core"                 % jmhV,    // GPLv2
        "org.openjdk.jmh"     % "jmh-generator-bytecode"   % jmhV,    // GPLv2
        "org.openjdk.jmh"     % "jmh-generator-reflection" % jmhV     // GPLv2
      ) ++ ((generatorType in Jmh).value match {
        case "default" | "reflection" => Nil // default == reflection (0.9)
        case "asm"                    => Seq("org.openjdk.jmh" % "jmh-generator-asm" % jmhV)    // GPLv2
        case unknown                  => throw new IllegalArgumentException(s"Unknown benchmark generator type: $unknown, please use one of the supported generators!")
      })
    }
  )

  private def jmhVersionFromProps(): String = {
    val props = new Properties()
    val is = getClass.getResourceAsStream("/sbt-jmh.properties")
    props.load(is)
    is.close()
    props.get("jmh.version").toString
  }

  private def generateBenchmarkSourcesAndResources(s: TaskStreams, cacheDir: File, bytecodeDir: File, sourceDir: File, resourceDir: File, generatorType: String, classpath: Seq[Attributed[File]], run: Run): (Seq[File], Seq[File]) = {
    val inputs: Set[File] = (bytecodeDir ** "*").filter(_.isFile).get.toSet
    val cachedGeneration = FileFunction.cached(cacheDir, FilesInfo.hash) { _ =>
      // ignore change report and rebuild it completely
      internalGenerateBenchmarkSourcesAndResources(s, bytecodeDir, sourceDir, resourceDir, generatorType, classpath, run, s.log)
    }
    cachedGeneration(inputs).toSeq.partition(f => IO.relativizeFile(sourceDir, f).nonEmpty)
  }

  private def internalGenerateBenchmarkSourcesAndResources(s: TaskStreams, bytecodeDir: File, sourceDir: File,
                                                           resourceDir: File, generatorType: String, classpath: Seq[Attributed[File]],
                                                           run: Run, log: Logger): Set[File] = {
    // rebuild everything
    IO.delete(sourceDir)
    IO.createDirectory(sourceDir)
    IO.delete(resourceDir)
    IO.createDirectory(resourceDir)

    val mainClass = "org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator"
    val options = Seq(bytecodeDir, sourceDir, resourceDir, generatorType).map(_.toString)
    run.run(mainClass, classpath.map(_.data), options, log).get
    ((sourceDir ** "*").filter(_.isFile) +++ (resourceDir ** "*").filter(_.isFile)).get.toSet
  }
}
