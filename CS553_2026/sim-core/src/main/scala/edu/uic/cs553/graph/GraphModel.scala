package edu.uic.cs553.graph

final case class SimNode(id: Int, label: String)

final case class SimEdge(from: Int, to: Int, allowedMsgTypes: Set[String])

final case class NodePdf(nodeId: Int, distribution: Map[String, Double])

final case class EnrichedGraph(
  nodes: Vector[SimNode],
  edges: Vector[SimEdge],
  nodePdfs: Map[Int, NodePdf]
):
  def outNeighbors(nodeId: Int): Vector[Int] =
    edges.collect { case SimEdge(`nodeId`, to, _) => to }

  def allowedTypes(from: Int, to: Int): Set[String] =
    edges.collectFirst { case SimEdge(`from`, `to`, allowed) => allowed }.getOrElse(Set.empty)

  def pdfFor(nodeId: Int): Map[String, Double] =
    nodePdfs.get(nodeId).map(_.distribution).getOrElse(Map.empty)
