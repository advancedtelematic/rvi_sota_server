package org.genivi.sota.device_registry.db

import org.genivi.sota.data.CredentialsType

import slick.jdbc.MySQLProfile.api._

object SlickMappings {

  implicit val enumMapperCredentialsType =
    MappedColumnType.base[CredentialsType.CredentialsType, String](_.toString,
                                                                   (s: String) => CredentialsType.withName(s))
}
