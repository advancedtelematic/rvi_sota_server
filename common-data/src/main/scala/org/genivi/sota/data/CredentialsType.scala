package org.genivi.sota.data

final object CredentialsType extends CirceEnum {
  type CredentialsType = Value
  val PEM, OAuthClientCredentials = Value
}
