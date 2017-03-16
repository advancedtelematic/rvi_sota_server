package org.genivi.sota.core.data

import org.genivi.sota.data.Uuid

case class TargetInfo(id: Uuid, deviceId: String, targetUpdates: String, targetHash: String, targetSize: Long)

case class TargetInfoMeta(deviceId: String, targetUpdates: String, targetHash: String, targetSize: Long)
