package edu.uic.cs553.recovery

import edu.uic.cs553.api.{AlgorithmContext, DistributedAlgorithm}
import edu.uic.cs553.recovery.RollbackMessages.given
import io.circe.parser.decode
import io.circe.syntax.*
import org.slf4j.LoggerFactory

final class PetersonKearnsRollback(nodeId: Int, initiator: Boolean, checkpointInterval: Int) extends DistributedAlgorithm:
  private val logger = LoggerFactory.getLogger(getClass)

  // Akka actors are single-threaded, so these actor-local vars are safe.
  private var checkpointLog: List[(Int, String)] = List.empty
  private var sendLog: List[(Int, Int, Int, String)] = List.empty
  private var receiveLog: List[(Int, Int, Int)] = List.empty
  private var localSeqNo: Int = 0
  private var messagesProcessed: Int = 0
  private var rollbackTriggered: Boolean = false
  private var ackedNodes: Set[Int] = Set.empty
  /** Without this, dense graphs re-broadcast every duplicate RequestRollback and cause a CONTROL storm. */
  private var seenRollbackKeys: Set[(Int, Int)] = Set.empty

  override def name: String = "rollback"

  override def onStart(ctx: AlgorithmContext): Unit =
    takeCheckpoint(ctx)

  override def onTick(ctx: AlgorithmContext): Unit =
    if initiator && checkpointLog.nonEmpty && !rollbackTriggered then
      rollbackTriggered = true
      val target = checkpointLog.last._1
      ctx.neighborIds.foreach { neighbor =>
        ctx.send(neighbor, "CONTROL", (RequestRollback(ctx.nodeId, target): RollbackMsg).asJson.noSpaces)
      }
      logger.info("Node {} initiated rollback to checkpoint {}", Int.box(ctx.nodeId), Int.box(target))

  override def onMessage(ctx: AlgorithmContext, msg: Any): Unit =
    msg match
      case payload: String =>
        decode[RollbackMsg](payload).foreach(handleDecoded(ctx, _))
      case _ =>
        messagesProcessed = messagesProcessed + 1
        maybeCheckpoint(ctx)

  private def handleDecoded(ctx: AlgorithmContext, decoded: RollbackMsg): Unit =
    decoded match
      case Checkpoint(fromNode, sequenceNo, state) =>
        logger.debug("Node {} saw checkpoint {} from {}", Int.box(ctx.nodeId), Int.box(sequenceNo), Int.box(fromNode))
        receiveLog = (fromNode, localSeqNo, sequenceNo) :: receiveLog
        messagesProcessed = messagesProcessed + 1
        maybeCheckpoint(ctx)

      case LogSend(fromId, toId, msgSeqNo, msgKind) =>
        receiveLog = (fromId, localSeqNo, msgSeqNo) :: receiveLog
        logger.debug("Node {} observed send log {} -> {} kind {}", Int.box(ctx.nodeId), Int.box(fromId), Int.box(toId), msgKind)
        messagesProcessed = messagesProcessed + 1
        maybeCheckpoint(ctx)

      case LogReceive(fromId, _, msgSeqNo) =>
        receiveLog = (fromId, localSeqNo, msgSeqNo) :: receiveLog
        messagesProcessed = messagesProcessed + 1
        maybeCheckpoint(ctx)

      case RequestRollback(initiatorId, targetCheckpointSeq) =>
        val key = (initiatorId, targetCheckpointSeq)
        if seenRollbackKeys.contains(key) then ()
        else
          seenRollbackKeys += key
          rollbackTo(targetCheckpointSeq)
          ctx.neighborIds.foreach { neighbor =>
            if neighbor != initiatorId then ctx.send(neighbor, "CONTROL", (RequestRollback(initiatorId, targetCheckpointSeq): RollbackMsg).asJson.noSpaces)
          }
          ctx.send(initiatorId, "CONTROL", (RollbackAck(ctx.nodeId, targetCheckpointSeq): RollbackMsg).asJson.noSpaces)
          logger.info("Node {} rolled back to checkpoint {}", Int.box(ctx.nodeId), Int.box(targetCheckpointSeq))

      case RollbackAck(ackNodeId, rolledBackTo) =>
        ackedNodes = ackedNodes + ackNodeId
        logger.info("Node {} received rollback ack from {} for checkpoint {}", Int.box(ctx.nodeId), Int.box(ackNodeId), Int.box(rolledBackTo))
        if initiator && ackedNodes.size >= ctx.neighborIds.size then
          logger.info("Consistent global state restored")

  def recordSend(to: Int, msgSeqNo: Int, kind: String): Unit =
    localSeqNo = localSeqNo + 1
    sendLog = (to, localSeqNo, msgSeqNo, kind) :: sendLog

  def recordReceive(from: Int, msgSeqNo: Int): Unit =
    localSeqNo = localSeqNo + 1
    receiveLog = (from, localSeqNo, msgSeqNo) :: receiveLog
    messagesProcessed = messagesProcessed + 1

  def checkpointCount: Int = checkpointLog.size

  def latestCheckpoint: Option[(Int, String)] = checkpointLog.headOption

  private def maybeCheckpoint(ctx: AlgorithmContext): Unit =
    if messagesProcessed > 0 && messagesProcessed % checkpointInterval == 0 then takeCheckpoint(ctx)

  private def takeCheckpoint(ctx: AlgorithmContext): Unit =
    val snapshot = s"node=${ctx.nodeId};seq=$localSeqNo;processed=$messagesProcessed"
    checkpointLog = (localSeqNo, snapshot) :: checkpointLog
    logger.info("Node {} took checkpoint {}", Int.box(ctx.nodeId), Int.box(localSeqNo))

  private def rollbackTo(targetCheckpointSeq: Int): Unit =
    val retained = checkpointLog.filter(_._1 <= targetCheckpointSeq).sortBy(_._1)
    val selected = retained.lastOption.orElse(checkpointLog.sortBy(_._1).lastOption)
    selected.foreach { case (seq, _) =>
      checkpointLog = checkpointLog.filter(_._1 <= seq)
      sendLog = sendLog.filter(_._2 <= seq)
      receiveLog = receiveLog.filter(_._2 <= seq)
      localSeqNo = seq
      messagesProcessed = math.min(messagesProcessed, seq)
    }
