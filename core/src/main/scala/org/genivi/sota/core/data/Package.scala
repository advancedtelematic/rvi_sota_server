/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core.data

import akka.http.scaladsl.model.Uri
import spray.json._
import eu.timepit.refined._

case class PackageId( name: Package.Name, version: Package.Version )

object PackageId {
  import spray.json.DefaultJsonProtocol._
  import org.genivi.sota.refined.SprayJsonRefined._
  import org.genivi.sota.CirceSupport.{refinedEncoder, refinedDecoder}
  import io.circe._
  import io.circe.generic.semiauto._


  implicit val protocol = jsonFormat2(PackageId.apply)

  implicit val encoderInstance : Encoder[PackageId] = deriveFor[PackageId].encoder
  implicit val decoderInstance : Decoder[PackageId] = deriveFor[PackageId].decoder

  implicit val packageIdShow = cats.Show.show[PackageId]( packageId => s"${packageId.name}-${packageId.version}" )
}

case class Package(
  id: PackageId,
  uri: Uri,
  size: Long,
  checkSum: String,
  description: Option[String],
  vendor: Option[String]
)

object Package {

  trait ValidName
  trait ValidVersion

  type Name    = String Refined ValidName
  type Version = String Refined ValidVersion

  implicit val validPackageName: Predicate[ValidName, String] =
    Predicate.instance( _.nonEmpty, _ => "Package name required" )

  implicit val validPackageVersion: Predicate[ValidVersion, String] =
    Predicate.instance( _.matches( """^\d+\.\d+\.\d+$""" ), _ => "Invalid version format")


  def jsonOption(opt: Option[String]): String = {
    opt match {
      case Some(str) => str
      case None => ""
    }
  }

  implicit object PackageJsonFormat extends RootJsonFormat[Package] {
    def write(pkg: Package) = {
      val description = pkg.description match {
        case Some(d) => d
        case None => ""
      }
      JsObject(
        "id" -> JsObject("name" -> JsString(pkg.id.name.get), "version" -> JsString(pkg.id.version.get)),
        "uri" -> JsString(pkg.uri.toString()),
        "size" -> JsNumber(pkg.size),
        "checksum" -> JsString(pkg.checkSum),
        "description" -> JsString(jsonOption(pkg.description)),
        "vendor" -> JsString(jsonOption(pkg.vendor))
      )
    }
    def read(value: JsValue) = {
      value.asJsObject.getFields("id", "uri", "size", "checksum", "description", "vendor") match {
        case Seq(i, JsString(uri), JsNumber(size), JsString(checksum), JsString(description), JsString(vendor)) =>
          i.asJsObject.getFields("name", "version") match {
            case Seq(JsString(name), JsString(version)) =>
              new Package(PackageId(Refined(name), Refined(version)), Uri(uri), size.toLong, checksum, Some(description), Some(vendor))
            case _                                      => throw new DeserializationException("Package expected")
          }
        case _ => throw new DeserializationException("Package expected")
      }
    }
  }

}
