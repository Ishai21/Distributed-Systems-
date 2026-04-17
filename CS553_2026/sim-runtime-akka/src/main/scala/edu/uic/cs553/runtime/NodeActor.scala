package edu.uic.cs553.runtime

import akka.actor.{Actor, ActorRef, Props, Timers}
import edu.uic.cs553.api.DistributedAlgorithm
import edu.uic.cs553.metrics.MetricsCollector
import edu.uic.cs553.pdf.PdfSampler
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.util.Random

object NodeActor:
  def props(id: Int, seed: Long = 0L): Props = Props(new NodeActor(id, seed))

  sealed trait NodeMsg
  final case class Init(
    neighbors: Map[Int, ActorRef],
    allowedOnEdge: Map[Int, Set[String]],
    pdf: Map[String, Double],
    isTimer: Boolean,
    tickEveryMs: Long,
    isInput: Boolean,
    algorithmPlugin: Option[DistributedAlgorithm]
  ) extends NodeMsg
  final case class Envelope(from: Int, kind: String, payload: String) extends NodeMsg
  final case class ExternalInput(kind: String, payload: String) extends NodeMsg
  /** Use with `ask` so `sender()` is the temporary reply actor (no `ActorRef` payload; avoids null `noSender`). */
  case object GetState extends NodeMsg
  final case class StateReply(nodeId: Int, messagesSent: Long, messagesReceived: Long) extends NodeMsg
  private case object Tick extends NodeMsg

final class NodeActor(id: Int, seed: Long) extends Actor with Timers:
  import NodeActor.*

  private val logger = LoggerFactory.getLogger(getClass)

  // Actor-local vars are safe because Akka classic processes one message at a time.
  private var neighbors: Map[Int, ActorRef] = Map.empty
  private var allowedOnEdge: Map[Int, Set[String]] = Map.empty
  private var pdf: Map[String, Double] = Map.empty
  private var isInput: Boolean = false
  private var algorithmPlugin: Option[DistributedAlgorithm] = None
  private var messagesSent: Long = 0L
  private var messagesReceived: Long = 0L
  private var rng: Random = Random(seed ^ id.toLong)

  override def receive: Receive =
    case Init(initNeighbors, initAllowedOnEdge, initPdf, timerEnabled, tickEveryMs, inputEnabled, plugin) =>
      neighbors = initNeighbors
      allowedOnEdge = initAllowedOnEdge
      pdf = initPdf
      isInput = inputEnabled
      algorithmPlugin = plugin
      if timerEnabled then timers.startTimerAtFixedRate("tick", Tick, tickEveryMs.millis)
      algorithmPlugin.foreach(_.onStart(buildContext()))
      logger.info("Initialized node {} with {} neighbors", Int.box(id), Int.box(neighbors.size))

    case Tick =>
      sendSampledMessage()
      algorithmPlugin.foreach(_.onTick(buildContext()))

    case ExternalInput(kind, payload) =>
      if isInput then sendByKind(kind, payload)
      else logger.warn("Node {} is not configured as input node", Int.box(id))

    case envelope @ Envelope(from, kind, payload) =>
      if allowedOnEdge.getOrElse(from, Set.empty).contains(kind) then
        messagesReceived = messagesReceived + 1
        MetricsCollector.recordReceived(kind)
        logger.debug("Node {} received {} from {}", Int.box(id), kind, Int.box(from))
        algorithmPlugin.foreach(_.onMessage(buildContext(), payload))
      else
        logger.warn("Dropping forbidden message kind {} on edge {} -> {}", kind, Int.box(from), Int.box(id))

    case GetState =>
      val state = StateReply(id, messagesSent, messagesReceived)
      sender() ! state

  private def buildContext(): NodeContext =
    NodeContext(
      nodeId = id,
      neighbors = neighbors,
      selfRef = self,
      send_ = (to, kind, payload) => sendExplicit(to, kind, payload),
      log = logger
    )

  private def sendSampledMessage(): Unit =
    if pdf.nonEmpty then
      val kind = PdfSampler.sample(pdf, rng)
      sendByKind(kind, s"""{"source":"tick","nodeId":$id}""")

  private def sendByKind(kind: String, payload: String): Unit =
    val eligible = neighbors.keysIterator.filter(to => allowedOnEdge.getOrElse(to, Set.empty).contains(kind)).toVector
    chooseRandom(eligible).foreach(sendExplicit(_, kind, payload))

  private def chooseRandom[A](items: Vector[A]): Option[A] =
    Option.when(items.nonEmpty)(items(rng.nextInt(items.size)))

  private def sendExplicit(to: Int, kind: String, payload: String): Unit =
    neighbors.get(to).foreach { ref =>
      ref ! Envelope(id, kind, payload)
      messagesSent = messagesSent + 1
      MetricsCollector.recordSent(kind)
      logger.debug("Node {} sent {} to {}", Int.box(id), kind, Int.box(to))
    }
