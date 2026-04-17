package edu.uic.cs553.runtime

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import edu.uic.cs553.api.DistributedAlgorithm
import edu.uic.cs553.config.SimConfig
import edu.uic.cs553.graph.EnrichedGraph
import edu.uic.cs553.metrics.MetricsCollector
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.*

object SimRunner:
  private val logger = LoggerFactory.getLogger(getClass)

  final case class Injection(delayMs: Long, nodeId: Int, kind: String, payload: String)
  final case class NodeSummary(nodeId: Int, messagesSent: Long, messagesReceived: Long)
  final case class RunSummary(durationSeconds: Int, nodes: Vector[NodeSummary])

  def run(graph: EnrichedGraph, config: SimConfig, algorithms: Map[Int, DistributedAlgorithm]): Unit =
    runWithInjections(graph, config, algorithms, Nil)

  def runWithInjections(
    graph: EnrichedGraph,
    config: SimConfig,
    algorithms: Map[Int, DistributedAlgorithm],
    injections: List[Injection],
    interactive: Boolean = false,
    outDir: Option[java.nio.file.Path] = None
  ): Unit =
    given Timeout = Timeout(5.seconds)
    val system = ActorSystem("sim-system")
    given akka.actor.Scheduler = system.scheduler
    given scala.concurrent.ExecutionContext = system.dispatcher

    val nodeRefs: Map[Int, ActorRef] =
      graph.nodes.map(node => node.id -> system.actorOf(NodeActor.props(node.id, config.seed), s"node-${node.id}")).toMap

    graph.nodes.foreach { node =>
      val outgoing = graph.outNeighbors(node.id)
      val neighbors = outgoing.flatMap(to => nodeRefs.get(to).map(to -> _)).toMap
      val incidentNeighborIds =
        graph.edges.collect {
          case edge if edge.from == node.id => edge.to
          case edge if edge.to == node.id => edge.from
        }.distinct

      val allowedOnIncident =
        incidentNeighborIds.map { other =>
          val allowed = graph.allowedTypes(node.id, other) ++ graph.allowedTypes(other, node.id)
          other -> allowed
        }.toMap

      val tickEveryMs = config.tickEveryFor(node.id).getOrElse(config.tickIntervalMs)

      nodeRefs(node.id) ! NodeActor.Init(
        neighbors = neighbors,
        allowedOnEdge = allowedOnIncident,
        pdf = graph.pdfFor(node.id),
        isTimer = config.isTimerNode(node.id),
        tickEveryMs = tickEveryMs,
        isInput = config.isInputNode(node.id),
        algorithmPlugin = algorithms.get(node.id)
      )
    }

    startInjectionThreads(nodeRefs, injections)
    if interactive then startInteractiveThread(nodeRefs)

    logger.info("Simulation running for {} seconds", Int.box(config.runDurationSeconds))
    Thread.sleep(config.runDurationSeconds.toLong * 1000L)

    val states =
      Await.result(
        scala.concurrent.Future.sequence(
          graph.nodes.map { node =>
            (nodeRefs(node.id) ? NodeActor.GetState).mapTo[NodeActor.StateReply]
          }
        ),
        10.seconds
      )

    states.sortBy(_.nodeId).foreach { state =>
      logger.info("Node {} sent={} received={}", Int.box(state.nodeId), Long.box(state.messagesSent), Long.box(state.messagesReceived))
    }

    MetricsCollector.printSummary()
    outDir.foreach { dir =>
      writeSummary(dir, config, states.toVector)
      MetricsCollector.writeJson(dir.resolve("metrics.json"))
    }
    Await.result(system.terminate(), 10.seconds)

  private def startInjectionThreads(nodeRefs: Map[Int, ActorRef], injections: List[Injection]): Unit =
    injections.foreach { injection =>
      val thread = Thread(() =>
        Thread.sleep(injection.delayMs)
        nodeRefs.get(injection.nodeId).foreach(_ ! NodeActor.ExternalInput(injection.kind, injection.payload))
      )
      thread.setDaemon(true)
      thread.start()
    }

  private def startInteractiveThread(nodeRefs: Map[Int, ActorRef]): Unit =
    val thread = Thread(() =>
      Iterator
        .continually(scala.io.StdIn.readLine())
        .takeWhile(_ != null)
        .map(_.trim)
        .filter(_.nonEmpty)
        .foreach { line =>
          val parts = line.split("\\s+", 3).toList
          parts match
            case nodeIdRaw :: kind :: payload :: Nil =>
              nodeRefs.get(nodeIdRaw.toInt).foreach(_ ! NodeActor.ExternalInput(kind, payload))
            case nodeIdRaw :: kind :: Nil =>
              nodeRefs.get(nodeIdRaw.toInt).foreach(_ ! NodeActor.ExternalInput(kind, ""))
            case _ =>
              logger.warn("Interactive injection format: <nodeId> <kind> <payload>. Got: {}", line)
        }
    )
    thread.setDaemon(true)
    thread.start()

  private def writeSummary(dir: java.nio.file.Path, config: SimConfig, states: Vector[NodeActor.StateReply]): Unit =
    import java.nio.file.Files
    import io.circe.Encoder
    import io.circe.syntax.*

    given Encoder[NodeSummary] = Encoder.forProduct3("nodeId", "messagesSent", "messagesReceived")(s => (s.nodeId, s.messagesSent, s.messagesReceived))
    given Encoder[RunSummary] = Encoder.forProduct2("durationSeconds", "nodes")(s => (s.durationSeconds, s.nodes))

    val summary = RunSummary(
      durationSeconds = config.runDurationSeconds,
      nodes = states.sortBy(_.nodeId).map(s => NodeSummary(s.nodeId, s.messagesSent, s.messagesReceived))
    )
    Files.writeString(dir.resolve("summary.json"), summary.asJson.spaces2)
