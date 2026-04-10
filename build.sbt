val chiselVersion = System.getProperty("chiselVersion", "3.5.6")
val firrtlVersion = System.getProperty("firrtlVersion", "1.5.6")
val defaultVersions = Map("chisel3" -> "3.5.6",
                          "chisel-iotesters" -> "2.5.6")

lazy val commonSettings = Seq (
  organization := "berkeley",
  version      := "3.0",
  scalaVersion := "2.12.16",
  traceLevel   := 15,
  scalacOptions ++= Seq("-deprecation","-unchecked","-Xsource:2.11"),
  resolvers ++= Seq(
    "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases"
  ),
  libraryDependencies ++= (Seq("chisel3","chisel-iotesters").map {
    dep: String => "edu.berkeley.cs" %% dep % sys.props.getOrElse(dep + "Version", defaultVersions(dep)) }) ++
  Seq(
    "edu.berkeley.cs" %% "firrtl" % firrtlVersion
  ),
  addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin_2.12.16" % chiselVersion)
)  

lazy val common = Project("common", file("common")).settings(commonSettings
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/common", 
        scalaSource in Test := baseDirectory.value / "../src/test",
        resourceDirectory in Compile := baseDirectory.value / "../vsrc"))
lazy val rv32_1stage = Project("rv32_1stage", file("rv32_1stage")).settings(commonSettings  
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/rv32_1stage")).dependsOn(common)
lazy val rv32_2stage = Project("rv32_2stage", file("rv32_2stage")).settings(commonSettings  
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/rv32_2stage")).dependsOn(common)
lazy val rv32_3stage = Project("rv32_3stage", file("rv32_3stage")).settings(commonSettings  
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/rv32_3stage")).dependsOn(common)
lazy val rv32_5stage = Project("rv32_5stage", file("rv32_5stage")).settings(commonSettings  
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/rv32_5stage")).dependsOn(common)
lazy val rv32_ucode  = Project("rv32_ucode", file("rv32_ucode")).settings(commonSettings  
  ++Seq(scalaSource in Compile := baseDirectory.value / "../src/rv32_ucode")).dependsOn(common)
