package edu.uic.cs553.runtime

import akka.actor.ActorRef
import edu.uic.cs553.api.AlgorithmContext

final case class NodeContext(
  nodeId: Int,
  neighbors: Map[Int, ActorRef],
  selfRef: ActorRef,
  private val send_ : (Int, String, String) => Unit,
  log: org.slf4j.Logger
 ) extends AlgorithmContext:
  def neighborIds: Set[Int] = neighbors.keySet
  def send(to: Int, kind: String, payload: String): Unit = send_(to, kind, payload)
