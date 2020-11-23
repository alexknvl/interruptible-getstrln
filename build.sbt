enablePlugins(PackPlugin)

organization := "com.alexknvl"
name := "interruptible-getstrln"

libraryDependencies ++= List(
  Dependencies.zio,
  Dependencies.zioStream
)

fork in run := true
packMain := Map("test" -> "com.alexknvl.zio.console.Main")