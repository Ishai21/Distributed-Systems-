package edu.uic.cs553.graph

import edu.uic.cs553.config.SimConfig
import io.circe.Decoder
import io.circe.generic.semiauto.*
import io.circe.parser.parse
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Random

object GraphLoader:
  private val logger = LoggerFactory.getLogger(getClass)

  private final case class RawNode(id: Int, label: String)
  private final case class RawEdge(from: Int, to: Int)
  private final case class RawGraph(nodes: List[RawNode], edges: List[RawEdge])

  private given Decoder[RawNode] = deriveDecoder
  private given Decoder[RawEdge] = deriveDecoder
  private given Decoder[RawGraph] = deriveDecoder

  def load(config: SimConfig): Either[String, EnrichedGraph] =
    if config.syntheticGraph then Right(generateSynthetic(config))
    else
      config.graphFilePath.fold[Either[String, EnrichedGraph]](Left("graphFilePath is required when syntheticGraph=false"))(
        loadFromPath(_, config)
      )

  def loadFromPath(path: String, config: SimConfig): Either[String, EnrichedGraph] =
    val filePath = Path.of(path)
    if !Files.exists(filePath) then Left(s"Graph file does not exist: $path")
    else
      val content = Files.readString(filePath)
      val raw =
        if path.endsWith(".json") then fromJson(content)
        else if path.endsWith(".dot") then fromDot(content)
        else fromJson(content).orElse(fromDot(content))
      raw.map(applyElectionRingIfNeeded(_, config.algorithmName))

  private def fromJson(content: String): Either[String, EnrichedGraph] =
    parse(content)
      .left
      .map(_.message)
      .flatMap(_.as[RawGraph].left.map(_.message))
      .map { raw =>
        EnrichedGraph(
          nodes = raw.nodes.toVector.map(node => SimNode(node.id, node.label)),
          edges = raw.edges.toVector.map(edge => SimEdge(edge.from, edge.to, Set.empty)),
          nodePdfs = Map.empty
        )
      }

  private def fromDot(content: String): Either[String, EnrichedGraph] =
    // NetGameSim (and Graphviz) lines often append attributes on the same line, e.g.
    //   "84" -> "89" ["weight"="8.0"]
    // Regex pattern matching uses matches() on the whole line, so we scan each line instead.
    val edgePattern = """"(\d+)"\s*->\s*"(\d+)"""".r
    val nodeDeclPattern = """"(\d+)"\s*\[""".r

    val edges = content.linesIterator.flatMap { line =>
      edgePattern.findFirstMatchIn(line).map(m => m.group(1).toInt -> m.group(2).toInt)
    }.toVector.distinct

    val declaredNodeIds = content.linesIterator.flatMap { line =>
      nodeDeclPattern.findFirstMatchIn(line).map(_.group(1).toInt)
    }.toVector

    val nodeIds =
      (declaredNodeIds ++ edges.flatMap { case (from, to) => Vector(from, to) }).distinct.sorted

    if nodeIds.isEmpty then Left("No nodes found in DOT file")
    else
      logger.info("Loaded DOT graph with {} nodes and {} edges", Int.box(nodeIds.size), Int.box(edges.size))
      Right(
        EnrichedGraph(
          nodes = nodeIds.map(id => SimNode(id, s"n$id")),
          edges = edges.map { case (from, to) => SimEdge(from, to, Set.empty) },
          nodePdfs = Map.empty
        )
      )

  /** Ring election forwards on sorted id order; overlay those edges whenever election (or both) runs. */
  private def applyElectionRingIfNeeded(g: EnrichedGraph, algorithmName: String): EnrichedGraph =
    g.copy(edges = withElectionRingEdges(g.nodes, g.edges, algorithmName))

  private def withElectionRingEdges(nodes: Vector[SimNode], edges: Vector[SimEdge], algorithmName: String): Vector[SimEdge] =
    val ids = nodes.map(_.id).distinct.sorted.toVector
    val ringEdges =
      if ids.size >= 2 && (algorithmName == "ring-election" || algorithmName == "both") then
        Vector.tabulate(ids.size)(i => SimEdge(ids(i), ids((i + 1) % ids.size), Set.empty))
      else Vector.empty
    (edges ++ ringEdges).distinctBy(e => (e.from, e.to))

  private def generateSynthetic(config: SimConfig): EnrichedGraph =
    val rng = Random(config.seed)
    val nodes = Vector.tabulate(config.nodeCount)(id => SimNode(id, s"n$id"))
    val randomEdges =
      nodes.flatMap { fromNode =>
        nodes.collect {
          case toNode if fromNode.id != toNode.id && rng.nextDouble() <= config.edgeProbability =>
            SimEdge(fromNode.id, toNode.id, Set.empty)
        }
      }
    val base = EnrichedGraph(nodes = nodes, edges = randomEdges, nodePdfs = Map.empty)
    val withRing = applyElectionRingIfNeeded(base, config.algorithmName)
    logger.info("Generated synthetic graph with {} nodes and {} edges", Int.box(withRing.nodes.size), Int.box(withRing.edges.size))
    withRing
