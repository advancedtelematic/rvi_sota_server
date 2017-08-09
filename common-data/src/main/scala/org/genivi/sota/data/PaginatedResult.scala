/**
  * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
  * License: MPL-2.0
  */
package org.genivi.sota.data

case class PaginatedResult[Value](total: Long, limit: Long, offset: Long, values: Seq[Value])

object PaginatedResult {
  import io.circe.generic.semiauto._
  import io.circe.{Decoder, Encoder}

  implicit def decoderPaginatedResult[T : Decoder]: Decoder[PaginatedResult[T]] = deriveDecoder
  implicit def encoderPaginatedResult[T : Encoder]: Encoder[PaginatedResult[T]] = deriveEncoder
}
