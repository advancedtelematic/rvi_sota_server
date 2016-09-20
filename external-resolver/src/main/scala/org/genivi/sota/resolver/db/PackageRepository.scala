/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.db

import org.genivi.sota.data.{Device, Namespace, PackageId}
import org.genivi.sota.db.Operators._
import org.genivi.sota.db.SlickExtensions._
import org.genivi.sota.refined.SlickRefined._
import org.genivi.sota.resolver.common.Errors
import slick.driver.MySQLDriver.api._

import scala.concurrent.ExecutionContext


object Wat {

  case class LiftedPackageId(name: Rep[PackageId.Name], version: Rep[PackageId.Version])

  implicit object LiftedPackageShape extends CaseClassShape(LiftedPackageId.tupled,
    (p: (PackageId.Name, PackageId.Version)) => PackageId(p._1, p._2))

}

/**
 * Data access object for Packages
 */
object PackageRepository {

  /**
   * DAO Mapping Class for the Package table in the database
   */
  // scalastyle:off
  private[db] class PackageTable(tag: Tag) extends Table[Package](tag, "Package") {

    def namespace   = column[Namespace]("namespace")
    def name        = column[PackageId.Name]("name")
    def version     = column[PackageId.Version]("version")
    def description = column[String]("description")
    def vendor      = column[String]("vendor")

    // insertOrUpdate buggy for composite-keys, see Slick issue #966.
    def pk = primaryKey("pk_package", (namespace, name, version))

    def * = (namespace, name, version, description.?, vendor.?).shaped <>
      (pkg => Package(pkg._1, PackageId(pkg._2, pkg._3), pkg._4, pkg._5),
        (pkg: Package) => Some((pkg.namespace, pkg.id.name, pkg.id.version, pkg.description, pkg.vendor)))
  }
  // scalastyle:on

  protected[db] val packages = TableQuery[PackageTable]

  /**
   * Adds a package to the Package table. Updates an existing package if already present.
 *
   * @param pkg   The package to add
   * @return      A DBIO[Int] for the number of rows inserted
   */
  def add(pkg: Package): DBIO[Int] =
    packages.insertOrUpdate(pkg)

  /**
   * Lists the packages in the Package table
 *
   * @return     A DBIO[Seq[Package]] for the packages in the table
   */
  def list: DBIO[Seq[Package]] =
    packages.result

  /**
   * Checks to see if a package exists in the database
 *
   * @param pkgId   The Id of the package to check for
   * @return        The DBIO[Package] if the package exists
   * @throws        Errors.MissingPackageException if thepackage does not exist
   */
  def exists(namespace: Namespace, pkgId: PackageId)(implicit ec: ExecutionContext): DBIO[Package] =
    packages
      .filter(p => p.namespace === namespace
                && p.name      === pkgId.name
                && p.version   === pkgId.version)
      .result
      .headOption
      .failIfNone(Errors.MissingPackage)

  /**
   * Loads a group of Packages from the database by ID
 *
   * @param ids     A Set[Package.Id] of Ids to load
   * @return        A DBIO[Set[Package]] of matched packages
   */
  def load(namespace: Namespace, ids: Set[PackageId])
          (implicit ec: ExecutionContext): DBIO[Set[Package]] = {
    packages.filter( x =>
      x.namespace === namespace &&
      (x.name.mappedTo[String] ++ x.version.mappedTo[String] inSet ids.map(id => id.name.get + id.version.get))
    ).result.map( _.toSet )
  }
}
