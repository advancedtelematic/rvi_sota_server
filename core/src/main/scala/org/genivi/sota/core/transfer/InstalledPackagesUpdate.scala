/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.transfer

import org.genivi.sota.core.ExternalResolverClient
import org.genivi.sota.core.rvi.InstalledPackages

import scala.concurrent.Future

class InstalledPackagesUpdate(resolverClient: ExternalResolverClient) {

  def update(installedPackages: InstalledPackages): Future[Unit] =
    resolverClient.setInstalledPackages(installedPackages.vin, installedPackages.packages)
}
