package edu.uic.cs553.config

import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import java.io.File
import scala.jdk.CollectionConverters.*

final case class SimConfig(
  messageTypes: List[String],
  defaultEdgeLabels: Set[String],
  edgeOverrides: List[(Int, Int, Set[String])],
  defaultPdf: Map[String, Double],
  perNodePdf: Map[Int, Map[String, Double]],
  timerNodes: List[(Int, Long)],
  inputNodes: List[Int],
  runDurationSeconds: Int,
  seed: Long,
  syntheticGraph: Boolean,
  nodeCount: Int,
  edgeProbability: Double,
  graphFilePath: Option[String],
  algorithmName: String,
  tickIntervalMs: Long,
  rollbackCheckpointInterval: Int
):
  def tickEveryFor(nodeId: Int): Option[Long] =
    timerNodes.collectFirst { case (`nodeId`, tick) => tick }

  def isTimerNode(nodeId: Int): Boolean = tickEveryFor(nodeId).nonEmpty

  def isInputNode(nodeId: Int): Boolean = inputNodes.contains(nodeId)

object SimConfig:
  private val logger = LoggerFactory.getLogger(getClass)

  def load(path: Option[String] = None): SimConfig =
    val base =
      path match
        case Some(filePath) =>
          val file = new File(filePath)
          ConfigFactory.parseFile(file).withFallback(ConfigFactory.defaultApplication()).resolve()
        case None =>
          ConfigFactory.load().resolve()
    fromConfig(base)

  def fromConfig(config: Config): SimConfig =
    val sim = config.getConfig("sim")

    val messageTypes = sim.getStringList("messages.types").asScala.toList
    val defaultEdgeLabels = sim.getStringList("edgeLabeling.default").asScala.toSet
    val edgeOverrides =
      sim.getConfigList("edgeLabeling.overrides").asScala.toList.map { entry =>
        (
          entry.getInt("from"),
          entry.getInt("to"),
          entry.getStringList("allow").asScala.toSet
        )
      }

    val defaultPdf = parsePdfEntries(sim.getConfigList("traffic.defaultPdf").asScala.toList)
    val perNodePdf =
      sim.getConfigList("traffic.perNodePdf").asScala.toList.map { entry =>
        val nodeId = entry.getInt("node")
        nodeId -> parsePdfEntries(entry.getConfigList("pdf").asScala.toList)
      }.toMap

    val timerNodes =
      sim.getConfigList("initiators.timers").asScala.toList.map { entry =>
        entry.getInt("node") -> entry.getLong("tickEveryMs")
      }

    val inputNodes =
      sim.getConfigList("initiators.inputs").asScala.toList.map(_.getInt("node"))

    val graphFilePath =
      if sim.hasPath("graphFilePath") && sim.getString("graphFilePath").nonEmpty then Some(sim.getString("graphFilePath"))
      else None

    val parsed = SimConfig(
      messageTypes = messageTypes,
      defaultEdgeLabels = defaultEdgeLabels,
      edgeOverrides = edgeOverrides,
      defaultPdf = defaultPdf,
      perNodePdf = perNodePdf,
      timerNodes = timerNodes,
      inputNodes = inputNodes,
      runDurationSeconds = sim.getInt("runDurationSeconds"),
      seed = sim.getLong("seed"),
      syntheticGraph = sim.getBoolean("syntheticGraph"),
      nodeCount = sim.getInt("nodeCount"),
      edgeProbability = sim.getDouble("edgeProbability"),
      graphFilePath = graphFilePath,
      algorithmName = sim.getString("algorithmName"),
      tickIntervalMs = sim.getLong("traffic.tickIntervalMs"),
      rollbackCheckpointInterval = sim.getInt("rollback.checkpointInterval")
    )
    logger.info("Loaded simulation config: algorithm={}, syntheticGraph={}", parsed.algorithmName, Boolean.box(parsed.syntheticGraph))
    parsed

  private def parsePdfEntries(entries: List[Config]): Map[String, Double] =
    entries.map(entry => entry.getString("msg") -> entry.getDouble("p")).toMap
