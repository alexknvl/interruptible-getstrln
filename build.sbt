organization := "com.alexknvl"

lazy val zioConsole2 = project.in(file("console2"))
  .settings(
    name := "zio-console2",
    libraryDependencies ++= Seq(
      compilerPlugin(Dependencies.Plugin.silencerPlugin),
      Dependencies.Plugin.silencer),
    libraryDependencies ++= List(
      Dependencies.zio,
      Dependencies.zioStream))
  .settings(Settings.common():_*)

lazy val testApp = project.in(file("test-app"))
  .dependsOn(zioConsole2)
  .enablePlugins(PackPlugin)
  .settings(packMain += ("test" -> "zio.Main"))

fork in run := true