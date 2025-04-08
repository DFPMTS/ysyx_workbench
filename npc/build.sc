// import Mill dependency
import mill._
import mill.scalalib._
import mill.scalalib.scalafmt.ScalafmtModule
import mill.scalalib.TestModule.Utest
// support BSP
import mill.bsp._

object Core extends ScalaModule with ScalafmtModule { m =>
  val useChisel7            = false
  override def scalaVersion = "2.13.15"
  override def scalacOptions = Seq(
    "-language:reflectiveCalls",
    "-deprecation",
    "-feature",
    "-Xcheckinit"
  )
  override def ivyDeps = Agg(
    if (useChisel7) ivy"org.chipsalliance::chisel:7.0.0-M1"
    else
      ivy"org.chipsalliance::chisel:6.7.0"
  )
  override def scalacPluginIvyDeps = Agg(
    if (useChisel7) ivy"org.chipsalliance:::chisel-plugin:7.0.0-M1"
    else
      ivy"org.chipsalliance:::chisel-plugin:6.7.0"
  )
  object test extends ScalaTests with Utest {
    override def ivyDeps = m.ivyDeps() ++ Agg(
      ivy"com.lihaoyi::utest:0.8.1",
      ivy"edu.berkeley.cs::chiseltest:6.0.0"      
    )
  }
  def repositoriesTask = T.task {
    Seq(
      coursier.MavenRepository("https://maven.aliyun.com/repository/central"),
      coursier.MavenRepository("https://repo.scala-sbt.org/scalasbt/maven-releases")
    ) ++ super.repositoriesTask()
  }
}
