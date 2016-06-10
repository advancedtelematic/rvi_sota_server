/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.packages

import org.genivi.sota.datatype.Namespace._
import org.genivi.sota.data.PackageId
import org.genivi.sota.resolver.filters.Filter

/**
 * A case class for packages
 * Packages have an id, a String description and a String vendor
 */
case class Package(
  namespace  : Namespace,
  id         : PackageId,
  description: Option[String],
  vendor     : Option[String]
)

/**
 * A case class for package filters
 * Filters have a package name, package version and filter name
 */
case class PackageFilter(
  namespace     : Namespace,
  packageName   : PackageId.Name,
  packageVersion: PackageId.Version,
  filterName    : Filter.Name
) {
  override def toString(): String = s"PackageFilter(${packageName.get}, ${packageVersion.get}, ${filterName.get})"
}

/**
 * The Package object
 * Represents Packages
 */
object Package {

  case class Metadata(
                       namespace: Namespace,
                       description: Option[String],
                       vendor     : Option[String]
                     )

}
