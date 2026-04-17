package edu.uic.cs553

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import edu.uic.cs553.runtime.NodeActor
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration.*

class NodeActorTest
    extends TestKit(ActorSystem("node-actor-test"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll:

  private given Timeout = Timeout(5.seconds)

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "NodeActor" should "respond to GetState after Init" in
    {
      val node1 = system.actorOf(NodeActor.props(0, 1L))
      node1 ! NodeActor.Init(Map.empty, Map.empty, Map("PING" -> 1.0), isTimer = false, 100L, isInput = false, algorithmPlugin = None)
      val reply = Await.result((node1 ? NodeActor.GetState).mapTo[NodeActor.StateReply], 5.seconds)
      reply.nodeId shouldBe 0
    }

  it should "drop envelopes whose kind is not allowed" in
    {
      val node2 = system.actorOf(NodeActor.props(1, 1L))
      val probe = TestProbe()
      node2 ! NodeActor.Init(Map(0 -> probe.ref), Map(0 -> Set("CONTROL")), Map.empty, isTimer = false, 100L, isInput = false, algorithmPlugin = None)
      node2 ! NodeActor.Envelope(0, "PING", "payload")
      val reply = Await.result((node2 ? NodeActor.GetState).mapTo[NodeActor.StateReply], 5.seconds)
      reply.messagesReceived shouldBe 0L
    }
