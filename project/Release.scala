import sbt._
import sbt.Keys._
import sbtrelease._
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations.{setReleaseVersion => _, _}

object Release {

  def settings(toPublish: Project*) = {
    val publishSteps = toPublish.map(p => ReleaseStep(releaseStepTask(publish in p)))

    val dockerPublishSteps: Seq[ReleaseStep] = Seq(
      releaseStepCommand("sota-core/docker:publish"),
      releaseStepCommand("sota-resolver/docker:publish"),
      releaseStepCommand("sota-webserver/docker:publish"),
      releaseStepCommand("sota-device_registry/docker:publish")
    )

    Seq(
      releaseIgnoreUntrackedFiles := true,
      releaseProcess := Seq(checkSnapshotDependencies) ++ publishSteps ++ dockerPublishSteps
    )
  }
}
