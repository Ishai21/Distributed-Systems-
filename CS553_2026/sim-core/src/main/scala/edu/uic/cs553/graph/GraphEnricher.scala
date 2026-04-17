package edu.uic.cs553.graph

import edu.uic.cs553.config.SimConfig
import org.slf4j.LoggerFactory

object GraphEnricher:
  private val logger = LoggerFactory.getLogger(getClass)
  private val Tolerance = 0.001

  def enrich(raw: EnrichedGraph, config: SimConfig): EnrichedGraph =
    val overrideMap = config.edgeOverrides.map { case (from, to, allowed) => (from, to) -> allowed }.toMap

    val edges = raw.edges.map { edge =>
      val base = overrideMap.getOrElse((edge.from, edge.to), config.defaultEdgeLabels)
      edge.copy(allowedMsgTypes = base + "CONTROL")
    }

    val nodePdfs =
      raw.nodes.map { node =>
        val distribution = config.perNodePdf.getOrElse(node.id, config.defaultPdf)
        validatePdf(node.id, distribution)
        node.id -> NodePdf(node.id, distribution)
      }.toMap

    logger.info("Enriched graph with {} edges and {} node PDFs", Int.box(edges.size), Int.box(nodePdfs.size))
    raw.copy(edges = edges, nodePdfs = nodePdfs)

  private def validatePdf(nodeId: Int, pdf: Map[String, Double]): Unit =
    val sum = pdf.values.sum
    if math.abs(sum - 1.0) > Tolerance then
      throw IllegalArgumentException(s"PDF for node $nodeId sums to $sum instead of 1.0")
