/**
  * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
  * License: MPL-2.0
  */
package org.genivi.sota.core.data

import org.genivi.sota.data.{Namespace, PackageId, Uuid}

case class AutoInstall(namespace: Namespace,
                       pkgName: PackageId.Name,
                       device: Uuid)

object AutoInstall {
  import org.genivi.sota.marshalling.CirceInstances._
  import io.circe.generic.semiauto._
  import io.circe.{Decoder, Encoder}

  implicit val decoderAutoInstall: Decoder[AutoInstall] = deriveDecoder
  implicit val encoderAutoInstall: Encoder[AutoInstall] = deriveEncoder
}
