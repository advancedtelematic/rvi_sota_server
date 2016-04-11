/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.resolver.common

import akka.actor.ActorSystem
import akka.event.Logging
import eu.timepit.refined._
import eu.timepit.refined.string._
import org.genivi.sota.data.Namespace._

/**
  * Helpers for extracting namespace from request context.
  */

trait Namespaces {
  def extractNamespace(implicit system: ActorSystem): Namespace = {
    val log = Logging.getLogger(system, "org.genivi.sota.resolver.common.NamespaceExtractor")

    // TODO: get namespace from user context
    val defaultNs = system.settings.config.getString("resolver.defaultNs")
    refineV[eu.timepit.refined.string.Uri](defaultNs).right.get
  }
}
