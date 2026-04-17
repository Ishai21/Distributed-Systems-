package edu.uic.cs553

import edu.uic.cs553.api.AlgorithmContext
import edu.uic.cs553.election.{ElectionMsg, LeaderMsg, ProbAnonymousRingElection, RingElectionMessages}
import edu.uic.cs553.election.RingElectionMessages.given
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

class RingElectionTest extends AnyFlatSpec with Matchers:
  private val logger = LoggerFactory.getLogger(getClass)

  final case class Sent(to: Int, kind: String, payload: String)

  final class StubContext(val nodeId: Int) extends AlgorithmContext:
    var sent: Vector[Sent] = Vector.empty
    override val neighborIds: Set[Int] = Set.empty
    override def send(to: Int, kind: String, payload: String): Unit =
      sent = sent :+ Sent(to, kind, payload)
    override val log = logger

  "ProbAnonymousRingElection" should "send one election message on start" in
    {
      val ctx1 = new StubContext(0)
      val algorithm1 = ProbAnonymousRingElection(0, Map(0 -> 1), 5, 11L)
      algorithm1.onStart(ctx1)
      ctx1.sent.size shouldBe 1
    }

  it should "declare leadership after a full ring traversal" in
    {
      val ctx2 = new StubContext(0)
      val algorithm2 = ProbAnonymousRingElection(0, Map(0 -> 0), 1, 11L)
      algorithm2.onStart(ctx2)
      ctx2.sent = Vector.empty
      algorithm2.onMessage(ctx2, (ElectionMsg(0, algorithm2.currentValue, 1): edu.uic.cs553.election.RingElectionMsg).asJson.noSpaces)
      algorithm2.leaderDeclared shouldBe true
    }

  it should "produce exactly one leader in a five node ring" in
    {
      val nodeIds = List.tabulate(5)(identity)
      val ringNext = nodeIds.zip(nodeIds.drop(1) :+ nodeIds.head).toMap
      val algorithms = nodeIds.map(id => id -> ProbAnonymousRingElection(id, ringNext, 5, 55L)).toMap
      val contexts = nodeIds.map(id => id -> new StubContext(id)).toMap
      algorithms.foreach { case (id, algorithm) => algorithm.onStart(contexts(id)) }
      val winner = algorithms.maxBy(_._2.currentValue)._1
      algorithms(winner).onMessage(contexts(winner), (ElectionMsg(winner, algorithms(winner).currentValue, 5): edu.uic.cs553.election.RingElectionMsg).asJson.noSpaces)
      val leaders = algorithms.values.count(_.leaderDeclared)
      leaders shouldBe 1
    }
