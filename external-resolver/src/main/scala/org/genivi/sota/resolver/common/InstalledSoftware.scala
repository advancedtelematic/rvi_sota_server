/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */

package org.genivi.sota.resolver.common

import org.genivi.sota.resolver.data.Firmware
import org.genivi.sota.data.PackageId

case class InstalledSoftware(
  packages: Set[PackageId],
  firmware: Set[(Firmware.Module, Firmware.FirmwareId, Long)]
)

