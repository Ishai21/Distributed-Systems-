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
    else config.graphFilePath.fold[Either[String, EnrichedGraph]](Left("graphFilePath is required when syntheticGraph=false"))(loadFromPath)

  def loadFromPath(path: String): Either[String, EnrichedGraph] =
    val filePath = Path.of(path)
    if !Files.exists(filePath) then Left(s"Graph file does not exist: $path")
    else
      val content = Files.readString(filePath)
      if path.endsWith(".json") then fromJson(content)
      else if path.endsWith(".dot") then fromDot(content)
      else fromJson(content).orElse(fromDot(content))

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
    val edgeRegex = """"(\d+)"\s*->\s*"(\d+)"""".r
    val nodeRegex = """"(\d+)"\s*\[""".r

    val edges = content.linesIterator.collect { case edgeRegex(from, to) => from.toInt -> to.toInt }.toVector.distinct
    val nodeIds =
      (content.linesIterator.collect { case nodeRegex(id) => id.toInt }.toVector ++
        edges.flatMap { case (from, to) => Vector(from, to) }).distinct.sorted

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

  private def generateSynthetic(config: SimConfig): EnrichedGraph =
    val rng = Random(config.seed)
    val nodes = Vector.tabulate(config.nodeCount)(id => SimNode(id, s"n$id"))
    val edges =
      nodes.flatMap { fromNode =>
        nodes.collect {
          case toNode if fromNode.id != toNode.id && rng.nextDouble() <= config.edgeProbability =>
            SimEdge(fromNode.id, toNode.id, Set.empty)
        }
      }
    logger.info("Generated synthetic graph with {} nodes and {} edges", Int.box(nodes.size), Int.box(edges.size))
    EnrichedGraph(nodes = nodes, edges = edges, nodePdfs = Map.empty)
