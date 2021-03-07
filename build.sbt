import org.jetbrains.sbtidea.Keys.intellijPlugins
import sbt.Keys.name
import sbtbuildinfo.BuildInfoPlugin.autoImport.buildInfoOptions

// IntelliJ plugin docs here: https://github.com/JetBrains/sbt-idea-plugin
intellijPluginName in ThisBuild := "edg-ide"
intellijBuild in ThisBuild := "2020.2.3"


lazy val compiler = (project in file("PolymorphicBlocks/compiler"))  // proto imported transitively
    .enablePlugins(SbtIdeaPlugin)  // sbt-idea-plugin does if this isn't enabled

lazy val root = (project in file("."))
    .dependsOn(compiler)
    .enablePlugins(SbtIdeaPlugin)
    .settings(
      scalaVersion := "2.13.4",

      name := "edg-ide",
      version := "0.1-SNAPSHOT",

      libraryDependencies ++= Seq(
        "org.scalatest" %% "scalatest" % "3.2.0" % "test",
        "org.eclipse.elk" % "org.eclipse.elk.alg.layered" % "0.7.0",
      ),
      intellijPlugins := Seq(
        "com.intellij.properties",
        "PythonCore:202.7660.27",
      ).map(_.toPlugin),
      patchPluginXml := pluginXmlOptions { xml =>
        xml.version           = version.value
        xml.pluginDescription = "EDG IDE Description"
        xml.sinceBuild        = (intellijBuild in ThisBuild).value
        xml.untilBuild        = "202.*"
      },
    ).enablePlugins(BuildInfoPlugin)
    .settings(
      buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoOptions += BuildInfoOption.BuildTime,
      buildInfoPackage := "edg_ide.build",
    )
