/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.resolve

import org.genivi.sota.data.PackageId
import org.genivi.sota.resolver.devices.Device

object ResolveFunctions {

  def makeFakeDependencyMap
    (pkgId: PackageId, vs: Seq[Device])
      : Map[Device.DeviceId, List[PackageId]] =
    vs.map(device => Map(device.id -> List(pkgId)))
      .foldRight(Map[Device.DeviceId, List[PackageId]]())(_++_)

}
