package edu.uic.cs553.graph

import io.circe.Encoder
import io.circe.syntax.*

object GraphWriter:
  final case class JsonNode(id: Int, label: String)
  final case class JsonEdge(from: Int, to: Int, allowedMsgTypes: List[String])
  final case class JsonGraph(nodes: Vector[JsonNode], edges: Vector[JsonEdge], nodePdfs: Map[Int, Map[String, Double]])

  given Encoder[JsonNode] = Encoder.forProduct2("id", "label")(n => (n.id, n.label))
  given Encoder[JsonEdge] = Encoder.forProduct3("from", "to", "allowedMsgTypes")(e => (e.from, e.to, e.allowedMsgTypes))
  given Encoder[JsonGraph] = Encoder.forProduct3("nodes", "edges", "nodePdfs")(g => (g.nodes, g.edges, g.nodePdfs))

  def toJson(graph: EnrichedGraph): String =
    val jg = JsonGraph(
      nodes = graph.nodes.map(n => JsonNode(n.id, n.label)),
      edges = graph.edges.map(e => JsonEdge(e.from, e.to, e.allowedMsgTypes.toList.sorted)),
      nodePdfs = graph.nodePdfs.view.mapValues(_.distribution).toMap
    )
    jg.asJson.spaces2
