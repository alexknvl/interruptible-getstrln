enablePlugins(PackPlugin)

organization := "com.alexknvl"
name := "interruptible-getstrln"

libraryDependencies ++= List(
  "dev.zio" %% "zio"         % "1.0.0-RC18-2",
  "dev.zio" %% "zio-streams" % "1.0.0-RC18-2"
)

fork in run := true
packMain := Map("test" -> "com.alexknvl.zio.console.Main")