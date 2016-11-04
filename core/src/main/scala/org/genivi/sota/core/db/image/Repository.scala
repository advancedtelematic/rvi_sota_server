/*
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 *  License: MPL-2.0
 */

package org.genivi.sota.core.db.image

import java.time.Instant

import org.genivi.sota.core.SotaCoreErrors
import org.genivi.sota.core.data.UpdateStatus
import org.genivi.sota.core.data.UpdateStatus.UpdateStatus
import org.genivi.sota.data.{Namespace, Uuid}
import slick.driver.MySQLDriver.api._

import scala.concurrent.{ExecutionContext, Future}


trait ImageRepositorySupport {
  def imageRepository(implicit ec: ExecutionContext, db: Database) = new ImageRepository
}

protected class ImageRepository()(implicit ec: ExecutionContext, db: Database) {
  import DataType._
  import ImageSchema._
  import org.genivi.sota.db.SlickExtensions._
  import org.genivi.sota.db.SlickAnyVal._
  import org.genivi.sota.refined.SlickRefined._

  def persist(ns: Namespace, commit: Commit, imageRef: RefName, desc: String, pullUri: PullUri): Future[Image] = {
    val now = Instant.now()
    val image = Image(ns, ImageId.generate(), commit, imageRef, desc, pullUri, now, now)

    val io = images
      .insertOrUpdateWithKey(image,
        _.filter(_.namespace === ns).filter(_.commit === commit).filter(_.imageRef === imageRef),
        _.copy(commit = commit, imageRef = imageRef,
          description = desc, pullUri = pullUri, updatedAt = now)
      )

    db.run(io)
  }

  def findAll(ns: Namespace): Future[Seq[Image]] =
    db.run(images.filter(_.namespace === ns).result)
}

trait ImageUpdateRepositorySupport {
  def imageUpdateRepository(implicit ec: ExecutionContext, db: Database) = new ImageUpdateRepository
}


protected class ImageUpdateRepository()(implicit ec: ExecutionContext, db: Database) {
  import DataType._
  import ImageSchema._
  import org.genivi.sota.core.db.UpdateSpecs.UpdateStatusColumn
  import org.genivi.sota.db.SlickExtensions._
  import SotaCoreErrors.MissingImageForUpdate
  import org.genivi.sota.db.SlickAnyVal._

  def persist(ns: Namespace, imageId: ImageId, device: Uuid): Future[ImageUpdate] = {
    val now = Instant.now()
    val imageUpdate = ImageUpdate(ns, ImageUpdateId.generate(), imageId, device, UpdateStatus.Pending, now, now)

    val io = imageUpdates
      .insertOrUpdateWithKey(
        imageUpdate,
        _.filter(_.imageId === imageId).filter(_.device === device),
        _.copy(imageId = imageId, device = device, updatedAt = now)
      )

    db.run(io.handleIntegrityErrors(MissingImageForUpdate))
  }

  def findForDevice(device: Uuid, status: UpdateStatus): Future[Seq[(ImageUpdate, Image)]] = {
    val io =
      imageUpdates
        .filter(_.device === device)
        .filter(_.status === status)
        .join(images).on(_.imageId === _.id)

    db.run(io.result)
  }
}
