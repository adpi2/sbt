import scala.util.matching.Regex

lazy val repo = file("test-repo")
lazy val resolver = Resolver.file("test-repo", repo)

lazy val example = project.in(file("example"))
  .enablePlugins(SbtPlugin)
  .settings(
    organization := "org.example",
    addSbtPlugin("ch.epfl.scala" % "sbt-plugin-example-diamond" % "0.5.0"),
    publishTo := Some(resolver),
    checkPublishedArtifacts := checkPackagedArtifactsDef.value,
    checkPublish := checkPublishDef.value
  )

lazy val testProject = project.in(file("test-project"))
  .settings(
    addSbtPlugin("org.example" % "example" % "0.1.0-SNAPSHOT"),
    externalResolvers += {
      val base = (ThisBuild / baseDirectory).value
      MavenRepository("test-repo", s"file://$base/test-repo")
    },
    // ivyPaths := IvyPaths(baseDirectory.value, Some((ThisBuild / baseDirectory).value / "ivy-cache")),
    // csrCacheDirectory := baseDirectory.value / "coursier-cache",
    checkUpdate := checkUpdateDef(
      "example_2.12_1.0-0.1.0-SNAPSHOT.jar",
      "sbt-plugin-example-diamond_2.12_1.0-0.5.0.jar",
      "sbt-plugin-example-left_2.12_1.0-0.3.0.jar",
      "sbt-plugin-example-right_2.12_1.0-0.3.0.jar",
      "sbt-plugin-example-bottom_2.12_1.0-0.3.0.jar",
    ).value
  )

lazy val checkPublishedArtifacts = taskKey[Unit]("check the published artifacts")
lazy val checkPublish = taskKey[Unit]("check publish")
lazy val checkUpdate = taskKey[Unit]("check update")

def checkPackagedArtifactsDef: Def.Initialize[Task[Unit]] = Def.task {
  val packagedArtifacts = Keys.packagedArtifacts.value
  val name = artifact.value.name
  val nameWithCrossVersion = name + "_2.12_1.0"
  val deprecatedArtifacts = packagedArtifacts.keys.filter(a => a.name == name)
  assert(deprecatedArtifacts.size == 4)

  val artifactsWithCrossVersion = packagedArtifacts.keys.filter(a => a.name == nameWithCrossVersion)
  assert(artifactsWithCrossVersion.size == 4)

  val deprecatedPom = deprecatedArtifacts.find(_.`type` == "pom")
  assert(deprecatedPom.isDefined)
  val deprecatedPomContent = IO.read(packagedArtifacts(deprecatedPom.get))
  assert(deprecatedPomContent.contains(s"<artifactId>$name</artifactId>"))
  assert(deprecatedPomContent.contains(s"<artifactId>sbt-plugin-example-diamond</artifactId>"))

  val pomWithCrossVersion = artifactsWithCrossVersion.find(_.`type` == "pom")
  assert(pomWithCrossVersion.isDefined)
  val pomContent = IO.read(packagedArtifacts(pomWithCrossVersion.get))
  assert(pomContent.contains(s"<artifactId>$nameWithCrossVersion</artifactId>"))
  assert(pomContent.contains(s"<artifactId>sbt-plugin-example-diamond_2.12_1.0</artifactId>"))
}

def checkPublishDef: Def.Initialize[Task[Unit]] = Def.task {
  val _ = publish.value
  val org = organization.value
  val name = artifact.value.name
  val nameWithCross = name + "_2.12_1.0"
  val version = Keys.version.value
  val files = IO.listFiles(repo / org.replace('.', '/') / nameWithCross / version)

  assert(files.nonEmpty)
  
  val Deprecated = s"${Regex.quote(name)}-${Regex.quote(version)}(-javadoc|-sources)?(\\.jar|\\.pom)".r
  val WithCrossVersion = s"${Regex.quote(nameWithCross)}-${Regex.quote(version)}(-javadoc|-sources)?(\\.jar|\\.pom)".r
  
  val deprecatedJars = files.map(_.name).collect { case jar @ Deprecated(_, ".jar") => jar }
  assert(deprecatedJars.size == 3, deprecatedJars.mkString(", ")) // bin, sources and javadoc

  val jarsWithCrossVersion = files.map(_.name).collect { case jar @ WithCrossVersion(_, ".jar") => jar }
  assert(jarsWithCrossVersion.size == 3, jarsWithCrossVersion.mkString(", ")) // bin, sources and javadoc
  
  val deprecatedPom = files
    .find { file => 
      file.name match {
        case pom @ Deprecated(_, ".pom") => true
        case _ => false
      }
    }
  assert(deprecatedPom.isDefined, "missing deprecated pom")
  val deprecatedPomContent = IO.read(deprecatedPom.get)
  assert(deprecatedPomContent.contains(s"<artifactId>$name</artifactId>"))
  assert(deprecatedPomContent.contains(s"<artifactId>sbt-plugin-example-diamond</artifactId>"))

  val pomWithCrossVersion = files
    .find { file =>
      file.name match {
        case pom @ WithCrossVersion(_, ".pom") => true
        case _ => false
      }  
    }
  assert(pomWithCrossVersion.isDefined, "missing pom with sbt cross-version _2.12_1.0")
  val pomContent = IO.read(pomWithCrossVersion.get)
  assert(pomContent.contains(s"<artifactId>$nameWithCross</artifactId>"))
  assert(pomContent.contains(s"<artifactId>sbt-plugin-example-diamond_2.12_1.0</artifactId>"))
}

def checkUpdateDef(expected: String*): Def.Initialize[Task[Unit]] = Def.task {
  val report = update.value
  val obtainedFiles = report.configurations
    .find(_.configuration.name == Compile.name)
    .toSeq
    .flatMap(_.modules)
    .flatMap(_.artifacts)
    .map(_._2)
  val obtainedSet = obtainedFiles.map(_.getName).toSet
  val expectedSet = expected.toSet + "scala-library.jar"
  assert(obtainedSet == expectedSet, obtainedFiles)
}
