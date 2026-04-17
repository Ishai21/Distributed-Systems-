package edu.uic.cs553

import edu.uic.cs553.api.DistributedAlgorithm
import edu.uic.cs553.config.SimConfig
import edu.uic.cs553.election.ProbAnonymousRingElection
import edu.uic.cs553.graph.{GraphEnricher, GraphLoader}
import edu.uic.cs553.graph.GraphWriter
import edu.uic.cs553.metrics.MetricsCollector
import edu.uic.cs553.recovery.PetersonKearnsRollback
import edu.uic.cs553.runtime.SimRunner
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

private val logger = LoggerFactory.getLogger("edu.uic.cs553.simMain")

private final case class CliOptions(
  configPath: Option[String] = None,
  graphPath: Option[String] = None,
  runSecondsOverride: Option[Int] = None,
  algorithmOverride: Option[String] = None,
  injectFile: Option[String] = None,
  interactive: Boolean = false,
  outDir: Option[String] = None
)

@main def simMain(args: String*): Unit =
  val options = parseArgs(args.toList, CliOptions())
  val loaded = SimConfig.load(options.configPath)
  val config =
    loaded.copy(
      runDurationSeconds = options.runSecondsOverride.getOrElse(loaded.runDurationSeconds),
      graphFilePath = options.graphPath.orElse(loaded.graphFilePath),
      syntheticGraph = if options.graphPath.nonEmpty then false else loaded.syntheticGraph,
      algorithmName = options.algorithmOverride.getOrElse(loaded.algorithmName)
    )

  val rawGraph = GraphLoader.load(config).fold(error => throw IllegalArgumentException(error), identity)
  val enrichedGraph = GraphEnricher.enrich(rawGraph, config)
  val algorithms = buildAlgorithms(enrichedGraph.nodes.map(_.id).toList, config)
  val injections = options.injectFile.map(readInjectionFile).getOrElse(Nil)
  val outPath = options.outDir.map(Path.of(_))
  outPath.foreach { dir =>
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("graph.json"), GraphWriter.toJson(enrichedGraph))
  }

  SimRunner.runWithInjections(
    enrichedGraph,
    config,
    algorithms,
    injections,
    interactive = options.interactive,
    outDir = outPath
  )
  MetricsCollector.printSummary()

private def buildAlgorithms(nodeIds: List[Int], config: SimConfig): Map[Int, DistributedAlgorithm] =
  val sorted = nodeIds.sorted
  val ringNext = sorted.zip(sorted.drop(1) :+ sorted.head).toMap

  sorted.map { nodeId =>
    val selected =
      config.algorithmName match
        case "ring-election" =>
          Some(ProbAnonymousRingElection(nodeId, ringNext, sorted.size, config.seed))
        case "rollback" =>
          Some(PetersonKearnsRollback(nodeId, config.isTimerNode(nodeId), config.rollbackCheckpointInterval))
        case "both" =>
          val election = ProbAnonymousRingElection(nodeId, ringNext, sorted.size, config.seed)
          val recovery = PetersonKearnsRollback(nodeId, config.isTimerNode(nodeId), config.rollbackCheckpointInterval)
          Some(combine(election, recovery))
        case _ => None
    nodeId -> selected
  }.collect { case (nodeId, Some(algorithm)) => nodeId -> algorithm }.toMap

private def combine(first: DistributedAlgorithm, second: DistributedAlgorithm): DistributedAlgorithm =
  new DistributedAlgorithm:
    override def name: String = s"${first.name}+${second.name}"
    override def onStart(ctx: edu.uic.cs553.api.AlgorithmContext): Unit =
      first.onStart(ctx)
      second.onStart(ctx)
    override def onMessage(ctx: edu.uic.cs553.api.AlgorithmContext, msg: Any): Unit =
      first.onMessage(ctx, msg)
      second.onMessage(ctx, msg)
    override def onTick(ctx: edu.uic.cs553.api.AlgorithmContext): Unit =
      first.onTick(ctx)
      second.onTick(ctx)

private def readInjectionFile(path: String): List[SimRunner.Injection] =
  Files.readAllLines(Path.of(path)).asScala.toList.collect {
    case line if line.trim.nonEmpty =>
      val parts = line.split("\\s+", 4).toList
      SimRunner.Injection(parts(0).toLong, parts(1).toInt, parts(2), parts.lift(3).getOrElse(""))
  }

private def parseArgs(args: List[String], current: CliOptions): CliOptions =
  args match
    case Nil => current
    case "--config" :: path :: tail => parseArgs(tail, current.copy(configPath = Some(path)))
    case "--graph" :: path :: tail => parseArgs(tail, current.copy(graphPath = Some(path)))
    case "--run" :: raw :: tail => parseArgs(tail, current.copy(runSecondsOverride = Some(parseDurationSeconds(raw))))
    case "--algorithm" :: name :: tail => parseArgs(tail, current.copy(algorithmOverride = Some(name)))
    case "--inject" :: path :: tail => parseArgs(tail, current.copy(injectFile = Some(path)))
    case "--out" :: path :: tail => parseArgs(tail, current.copy(outDir = Some(path)))
    case "--interactive" :: tail => parseArgs(tail, current.copy(interactive = true))
    case other :: _ => throw IllegalArgumentException(s"Unknown CLI argument: $other")

private def parseDurationSeconds(raw: String): Int =
  if raw.endsWith("s") then raw.dropRight(1).toInt else raw.toInt
