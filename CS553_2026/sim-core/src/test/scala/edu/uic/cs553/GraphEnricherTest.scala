package edu.uic.cs553

import com.typesafe.config.ConfigFactory
import edu.uic.cs553.config.SimConfig
import edu.uic.cs553.graph.{EnrichedGraph, GraphEnricher, SimEdge, SimNode}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GraphEnricherTest extends AnyFlatSpec with Matchers:
  private def config(raw: String): SimConfig =
    SimConfig.fromConfig(ConfigFactory.parseString(raw).resolve())

  private val baseGraph = EnrichedGraph(
    nodes = Vector(SimNode(0, "n0"), SimNode(1, "n1")),
    edges = Vector(SimEdge(0, 1, Set.empty)),
    nodePdfs = Map.empty
  )

  private val baseConfig =
    """
      |sim {
      |  seed = 1
      |  runDurationSeconds = 10
      |  algorithmName = "both"
      |  syntheticGraph = true
      |  nodeCount = 2
      |  edgeProbability = 1.0
      |  graphFilePath = ""
      |  messages.types = ["CONTROL","PING","WORK"]
      |  edgeLabeling.default = ["PING"]
      |  edgeLabeling.overrides = []
      |  traffic.tickIntervalMs = 100
      |  traffic.defaultPdf = [{ msg = "PING", p = 1.0 }]
      |  traffic.perNodePdf = []
      |  initiators.timers = []
      |  initiators.inputs = []
      |  rollback.checkpointInterval = 5
      |}
      |""".stripMargin

  "GraphEnricher" should "apply default edge labels to all edges" in
    {
      val enriched1 = GraphEnricher.enrich(baseGraph, config(baseConfig))
      enriched1.allowedTypes(0, 1) should contain("PING")
    }

  it should "replace defaults with per-edge overrides" in
    {
      val cfg1 = config(baseConfig.replace("edgeLabeling.overrides = []", """edgeLabeling.overrides = [{ from = 0, to = 1, allow = ["WORK"] }]"""))
      val enriched2 = GraphEnricher.enrich(baseGraph, cfg1)
      enriched2.allowedTypes(0, 1) should contain("WORK")
      enriched2.allowedTypes(0, 1) should not contain "PING"
    }

  it should "always include CONTROL on every edge" in
    {
      val enriched3 = GraphEnricher.enrich(baseGraph, config(baseConfig))
      enriched3.allowedTypes(0, 1) should contain("CONTROL")
    }

  it should "reject PDFs that do not sum to one" in
    {
      val cfg2 = config(baseConfig.replace("""traffic.defaultPdf = [{ msg = "PING", p = 1.0 }]""", """traffic.defaultPdf = [{ msg = "PING", p = 0.2 }, { msg = "WORK", p = 0.2 }]"""))
      an[IllegalArgumentException] should be thrownBy GraphEnricher.enrich(baseGraph, cfg2)
    }
