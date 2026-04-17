package edu.uic.cs553.election

import edu.uic.cs553.api.{AlgorithmContext, DistributedAlgorithm}
import edu.uic.cs553.election.RingElectionMessages.given
import io.circe.parser.decode
import io.circe.syntax.*
import org.slf4j.LoggerFactory

import scala.util.Random

final class ProbAnonymousRingElection(nodeId: Int, ringNext: Map[Int, Int], nodeCount: Int, seed: Long) extends DistributedAlgorithm:
  private val logger = LoggerFactory.getLogger(getClass)

  // Akka actors process one message at a time, so actor-local vars are safe here.
  private var myValue: Double = 0.0
  private var isLeader: Boolean = false
  private var leaderAnnounced: Boolean = false
  private var round: Int = 1
  private var rng: Random = Random(seed ^ nodeId.toLong)
  private var seenLeaderValues: Set[Double] = Set.empty

  override def name: String = "ring-election"

  override def onStart(ctx: AlgorithmContext): Unit =
    myValue = rng.nextDouble()
    val msg = ElectionMsg(senderId = nodeId, randomValue = myValue, round = 1)
    ctx.send(ringNext(nodeId), "CONTROL", (msg: RingElectionMsg).asJson.noSpaces)
    logger.info("Node {} started election with value {}", Int.box(nodeId), Double.box(myValue))

  override def onMessage(ctx: AlgorithmContext, msg: Any): Unit =
    msg match
      case payload: String =>
        decode[RingElectionMsg](payload).foreach(handleDecoded(ctx, _))
      case _ =>
        logger.debug("Node {} ignored non-string election payload", Int.box(nodeId))

  override def onTick(ctx: AlgorithmContext): Unit = ()

  private def handleDecoded(ctx: AlgorithmContext, decoded: RingElectionMsg): Unit =
    decoded match
      case ElectionMsg(origin, value, travelled) =>
        if value > myValue then
          ctx.send(ringNext(nodeId), "CONTROL", (ElectionMsg(origin, value, travelled + 1): RingElectionMsg).asJson.noSpaces)
        else if value < myValue then
          logger.debug("Node {} discarded lower value {}", Int.box(nodeId), Double.box(value))
        else if travelled >= nodeCount then
          isLeader = true
          announceLeader(ctx)
        else if origin != nodeId then
          round = round + 1
          myValue = rng.nextDouble()
          ctx.send(ringNext(nodeId), "CONTROL", (ElectionMsg(nodeId, myValue, 1): RingElectionMsg).asJson.noSpaces)
        else
          ctx.send(ringNext(nodeId), "CONTROL", (ElectionMsg(origin, value, travelled + 1): RingElectionMsg).asJson.noSpaces)

      case LeaderMsg(leaderId, leaderValue) =>
        if !seenLeaderValues.contains(leaderValue) then
          leaderAnnounced = true
          seenLeaderValues = seenLeaderValues + leaderValue
          logger.info("Leader elected with value {} at node {}", Double.box(leaderValue), Int.box(leaderId))
          if ringNext(nodeId) != nodeId then ctx.send(ringNext(nodeId), "CONTROL", (LeaderMsg(leaderId, leaderValue): RingElectionMsg).asJson.noSpaces)

      case AckLeader(from) =>
        logger.debug("Node {} received leader ack from {}", Int.box(nodeId), Int.box(from))

  private def announceLeader(ctx: AlgorithmContext): Unit =
    if !leaderAnnounced then
      leaderAnnounced = true
      seenLeaderValues = seenLeaderValues + myValue
      logger.info("Node {} became leader with value {}", Int.box(nodeId), Double.box(myValue))
      ctx.send(ringNext(nodeId), "CONTROL", (LeaderMsg(nodeId, myValue): RingElectionMsg).asJson.noSpaces)

  def currentValue: Double = myValue
  def leaderDeclared: Boolean = isLeader || leaderAnnounced
