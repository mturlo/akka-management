/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.management.cluster.bootstrap

import java.time.Duration

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

import akka.actor.ActorSystem
import akka.actor.Address
import akka.discovery.SimpleServiceDiscovery.ResolvedTarget
import akka.event.Logging

/**
 * The decision of joining "self" is made by deterministically sorting the discovered services
 * and picking the *lowest* address. Only the node with lowest address joins itself.
 *
 * If any of the contact-points returns a list of seed nodes it joins the existing cluster immediately.
 *
 * Joining "self" is only done when enough number of contact points have been discovered (`required-contact-point-nr`)
 * and there have been no changes to the discovered contact points during the `stable-margin`.
 *
 * There must also be seed node observations from all discovered contact points before joining "self".
 */
class LowestAddressJoinDecider(system: ActorSystem, settings: ClusterBootstrapSettings) extends JoinDecider {
  private val log = Logging(system, getClass)

  override def decide(info: SeedNodesInformation): Future[JoinDecision] =
    if (info.hasSeedNodes) {
      val seeds = joinOtherSeedNodes(info)
      if (seeds.isEmpty) KeepProbing.asCompletedFuture else JoinOtherSeedNodes(seeds).asCompletedFuture
    } else if (!hasEnoughContactPoints(info)) {
      log.info("Discovered [{}] contact points, confirmed [{}], which is less than the required [{}], retrying",
        info.contactPoints.size, info.seedNodesObservations.size,
        settings.contactPointDiscovery.requiredContactPointsNr)
      KeepProbing.asCompletedFuture
    } else if (!isPastStableMargin(info)) {
      log.debug(
          "Contact points observations have changed more recently than the stable-margin [{}], changed at [{}], " +
          "not joining myself. This process will be retried.", settings.contactPointDiscovery.stableMargin,
          info.contactPointsChangedAt)
      KeepProbing.asCompletedFuture
    } else {
      // no seed nodes
      val contactPointsWithoutSeedNodesObservations =
        if (isConfirmedCommunicationWithAllContactPointsRequired(info))
          info.contactPoints -- info.seedNodesObservations.map(_.contactPoint)
        else
          Set.empty[ResolvedTarget]
      if (contactPointsWithoutSeedNodesObservations.isEmpty) {
        // got info from all contact points as expected
        if (isAllowedToJoinSelf(info))
          JoinSelf.asCompletedFuture
        else {
          if (log.isInfoEnabled) {
            if (settings.formNewCluster)
              log.info(
                  "Exceeded stable margins without locating seed-nodes, however this node {} is NOT the lowest address " +
                  "out of the discovered endpoints in this deployment, thus NOT joining self. Expecting node [{}] " +
                  "(out of [{}]) to perform the self-join and initiate the cluster.",
                  selfContactPoints().map(_.productIterator.mkString(":")).mkString("[", ",", "]"),
                  lowestAddressContactPoint(info).getOrElse(""), info.contactPoints.mkString(", "))
            else
              log.warning(
                  "Exceeded stable margins without locating seed-nodes, however this node {} is configured with " +
                  "form-new-cluster=off, thus NOT joining self. Expecting existing cluster or node [{}] " +
                  "(out of [{}]) to perform the self-join and initiate the cluster.",
                  selfContactPoints().map(_.productIterator.mkString(":")).mkString("[", ",", "]"),
                  lowestAddressContactPoint(info).getOrElse(""), info.contactPoints.mkString(", "))
          }

          // the probing will continue until the lowest addressed node decides to join itself.
          // note, that due to DNS changes this may still become this node! We'll then await until the dns stableMargin
          // is exceeded and would decide to try joining self again (same code-path), that time successfully though.
          KeepProbing.asCompletedFuture
        }
      } else {
        // missing info from some contact points (e.g. because of probe failing)
        if (log.isInfoEnabled)
          log.info(
              "Exceeded stable margins but missing seed node information from some contact points [{}] (out of [{}])",
              contactPointsWithoutSeedNodesObservations.mkString(", "), info.contactPoints.mkString(", "))

        KeepProbing.asCompletedFuture
      }

    }

  /**
   * May be overridden by subclass to extract the nodes to use as seed nodes when joining
   * existing cluster. `info.allSeedNodes` contains all existing nodes.
   * If the returned `Set` is empty it will continue probing.
   */
  protected def joinOtherSeedNodes(info: SeedNodesInformation): Set[Address] =
    info.allSeedNodes.take(5)

  /**
   * May be overridden by subclass to decide if enough contact points have been discovered.
   * `info.contactPoints.size` is the number of discovered (e.g. via DNS lookup) contact points
   * and `info.seedNodesObservations.size` is the number that has been confirmed that they are
   * reachable and running.
   */
  protected def hasEnoughContactPoints(info: SeedNodesInformation): Boolean =
    info.seedNodesObservations.size >= settings.contactPointDiscovery.requiredContactPointsNr

  /**
   * May be overridden by subclass to decide if the set of discovered contact points is stable.
   * `info.contactPointsChangedAt` was the time when the discovered contact points were changed
   * last time. Subsequent lookup attempts after that returned the same contact points.
   */
  protected def isPastStableMargin(info: SeedNodesInformation): Boolean = {
    val contactPointsChanged = Duration.between(info.contactPointsChangedAt, info.currentTime)
    contactPointsChanged.toMillis >= settings.contactPointDiscovery.stableMargin.toMillis
  }

  /**
   * May be overridden by subclass to allow joining self even though some of the discovered
   * contact points have not been confirmed (unreachable or not running).
   * `hasEnoughContactPoints` and `isPastStableMargin` must still be fulfilled.
   */
  protected def isConfirmedCommunicationWithAllContactPointsRequired(info: SeedNodesInformation): Boolean =
    true

  private def isAllowedToJoinSelf(info: SeedNodesInformation): Boolean = {
    if (settings.formNewCluster) {
      val bootstrap = ClusterBootstrap(system)

      // we KNOW this await is safe, since we set the value before we bind the HTTP things even
      val selfContactPoints =
        Try(Await.result(bootstrap.selfContactPoints, 10.second)).getOrElse(throw new IllegalStateException(
              "Bootstrap.selfContactPoint was NOT set! This is required for the bootstrap to work! " +
              "If binding bootstrap routes manually and not via akka-management"))

      // some discovery mechanism can return both host name and IP address. this checks for both.
      def hostMatches(host: String, lowest: ResolvedTarget): Boolean =
        (host == lowest.host) || (Some(host) == lowest.address.map(_.getHostAddress))

      def matchesSelf(target: ResolvedTarget): Boolean = target.port match {
        case None =>
          selfContactPoints.exists { case (host, _) => hostMatches(host, target) }
        case Some(lowestPort) =>
          selfContactPoints.exists { case (host, port) => hostMatches(host, target) && port == lowestPort }
      }

      lowestAddressContactPoint(info) match {
        case Some(lowest) =>
          if (matchesSelf(lowest)) true
          else {
            if (!info.contactPoints.exists(matchesSelf)) {
              log.warning("Self contact point [{}] not found in targets [{}]", selfContactPoints.mkString(", "),
                info.contactPoints.mkString(", "))
            }
            false
          }
        case None =>
          false
      }
    } else false
  }

  private def selfContactPoints(): Set[(String, Int)] = {
    val bootstrap = ClusterBootstrap(system)

    // we KNOW this await is safe, since we set the value before we bind the HTTP things even
    val selfContactPoints =
      Try(Await.result(bootstrap.selfContactPoints, 10.second)).getOrElse(throw new IllegalStateException(
            "Bootstrap.selfContactPoint was NOT set! This is required for the bootstrap to work! " +
            "If binding bootstrap routes manually and not via akka-management"))
    selfContactPoints
  }

  /**
   * Contact point with the "lowest" contact point address,
   * it is expected to join itself if no other cluster is found in the deployment.
   *
   * May be overridden by subclass for example if another sort order is desired.
   */
  protected def lowestAddressContactPoint(info: SeedNodesInformation): Option[ResolvedTarget] = {
    // Note that we are using info.seedNodesObservations and not info.contactPoints here, but that
    // is the same when isConfirmedCommunicationWithAllContactPointsRequired == true
    info.seedNodesObservations.toList.map(_.contactPoint).sorted.headOption
  }
}
