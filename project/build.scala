import sbt._
import sbt.Keys._
import android.Keys._
object Build extends android.AutoBuild {
  lazy val mySettings = super.settings ++ android.Plugin.androidBuild ++ Seq (
    name := "wasuramoti",
    version := "0.8.22",
    versionCode := Some(60),
    scalaVersion := "2.11.6",
    platformTarget in Android := "android-22",
    buildToolsVersion in Android := Some("22.0.1"),
    // See https://github.com/pfn/android-sdk-plugin/issues/88
    sourceGenerators in Compile <<= (sourceGenerators in Compile) (g => Seq(g.last)),
    // Android support library >= 20 tries to emulate Material Design instead of Holo design.
    // I think wasuramoti fits to Holo rather than Material Design because Holo is more darker.
    // Therefore I will use older support library (19.1.0) instead of newer one.
    // I will stick to AppCompat 19.1.0 until most of the user thinks `Holo theme looks old`
    // TODO: use newer support library when penetration rate of android 5.x exceeds 80%
    libraryDependencies ++= Seq(
      "com.android.support" % "support-v4" % "19.1.0",
      android.Dependencies.aar("com.android.support" % "appcompat-v7" % "19.1.0")
    ),
    scalacOptions in Compile ++= Seq(
        "-unchecked",
        "-deprecation",
        "-feature",
        "-Xlint",
        // "-Xfatal-warnings", // treat warning as error
        "-Ywarn-dead-code",
        //"-Ywarn-numeric-widen",
        //"-Ywarn-value-discard",
        "-Ywarn-unused",
        "-Ywarn-unused-import"
        ),
    proguardOptions in Android ++= Seq(
    "-dontwarn scala.collection.**", // see http://blog.scaloid.org/2014_10_01_archive.html
    "-keepattributes Signature",
    "-verbose"
    ),
    useProguard := true
  )
  lazy val root = Project(
      id = "wasuramoti",
      base = file(".")
  ).settings(
    mySettings:_*
  )
}

// vim: set ts=4 sw=4 et:
