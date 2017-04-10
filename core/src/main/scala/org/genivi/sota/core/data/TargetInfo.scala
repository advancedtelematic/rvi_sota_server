package org.genivi.sota.core.data

import com.advancedtelematic.libtuf.data.TufDataType.Checksum
import org.genivi.sota.data.Uuid

case class TargetInfo(id: Uuid, deviceId: String, targetUpdates: String, checksum: Checksum, targetSize: Long)
