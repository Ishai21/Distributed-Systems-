package edu.uic.cs553

import edu.uic.cs553.api.AlgorithmContext
import edu.uic.cs553.recovery.{PetersonKearnsRollback, RequestRollback}
import edu.uic.cs553.recovery.RollbackMessages.given
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

class RollbackRecoveryTest extends AnyFlatSpec with Matchers:
  private val logger = LoggerFactory.getLogger(getClass)

  final case class Sent(to: Int, kind: String, payload: String)

  final class StubContext(val nodeId: Int) extends AlgorithmContext:
    var sent: Vector[Sent] = Vector.empty
    override val neighborIds: Set[Int] = Set(1, 2)
    override def send(to: Int, kind: String, payload: String): Unit =
      sent = sent :+ Sent(to, kind, payload)
    override val log = logger

  "PetersonKearnsRollback" should "take checkpoints every checkpoint interval messages" in
    {
      val ctx1 = new StubContext(0)
      val algorithm1 = PetersonKearnsRollback(0, initiator = false, checkpointInterval = 2)
      algorithm1.onStart(ctx1)
      algorithm1.recordReceive(1, 1)
      algorithm1.recordReceive(1, 2)
      algorithm1.onMessage(ctx1, "non-json")
      algorithm1.checkpointCount should be >= 1
    }

  it should "roll back to the latest checkpoint on request" in
    {
      val ctx2 = new StubContext(0)
      val algorithm2 = PetersonKearnsRollback(0, initiator = false, checkpointInterval = 1)
      algorithm2.onStart(ctx2)
      algorithm2.recordReceive(1, 1)
      algorithm2.onMessage(ctx2, "non-json")
      val before = algorithm2.latestCheckpoint.map(_._1).getOrElse(0)
      algorithm2.onMessage(ctx2, (RequestRollback(9, before): edu.uic.cs553.recovery.RollbackMsg).asJson.noSpaces)
      algorithm2.latestCheckpoint.map(_._1).getOrElse(0) shouldBe before
    }

  it should "send rollback acknowledgements after rollback" in
    {
      val ctx3 = new StubContext(0)
      val algorithm3 = PetersonKearnsRollback(0, initiator = false, checkpointInterval = 1)
      algorithm3.onStart(ctx3)
      algorithm3.onMessage(ctx3, (RequestRollback(9, 0): edu.uic.cs553.recovery.RollbackMsg).asJson.noSpaces)
      ctx3.sent.exists(_.payload.contains("RollbackAck")) shouldBe true
    }
