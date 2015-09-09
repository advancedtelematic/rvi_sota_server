package org.genivi.sota.resolver

import io.circe.generic.semiauto._
import org.genivi.sota.resolver.types._


trait CirceInstances {

  implicit val vehicleEncoder         = deriveFor[Vehicle].encoder
  implicit val vinEncoder             = deriveFor[Vehicle.Vin].encoder
  implicit val packageEncoder         = deriveFor[Package].encoder
  implicit val packageIdEncoder       = deriveFor[Package.Id].encoder
  implicit val packageMetadataEncoder = deriveFor[Package.Metadata].encoder
  implicit val filterEncoder          = deriveFor[Filter].encoder
  implicit val packageFilterEncoder   = deriveFor[PackageFilter].encoder

  implicit val vehicleDecoder         = deriveFor[Vehicle].decoder
  implicit val vinDecoder             = deriveFor[Vehicle.Vin].decoder
  implicit val packageDecoder         = deriveFor[Package].decoder
  implicit val packageIdDecoder       = deriveFor[Package.Id].decoder
  implicit val packageMetadataDecoder = deriveFor[Package.Metadata].decoder
  implicit val filterDecoder          = deriveFor[Filter].decoder
  implicit val packageFilterDecoder   = deriveFor[PackageFilter].decoder

}

object CirceInstances extends CirceInstances
