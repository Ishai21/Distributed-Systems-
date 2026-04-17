package edu.uic.cs553.metrics

import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

object MetricsCollector:
  private val logger = LoggerFactory.getLogger(getClass)
  private val totalMessagesSent = AtomicLong(0L)
  private val totalMessagesReceived = AtomicLong(0L)
  private val messagesByType = ConcurrentHashMap[String, AtomicLong]()

  def recordSent(kind: String): Unit =
    totalMessagesSent.incrementAndGet()
    messagesByType.computeIfAbsent(kind, _ => AtomicLong(0L)).incrementAndGet()

  def recordReceived(kind: String): Unit =
    totalMessagesReceived.incrementAndGet()
    messagesByType.computeIfAbsent(kind, _ => AtomicLong(0L)).incrementAndGet()

  def printSummary(): Unit =
    logger.info("Simulation summary: sent={}, received={}", Long.box(totalMessagesSent.get()), Long.box(totalMessagesReceived.get()))
    messagesByType.asScala.toSeq.sortBy(_._1).foreach { case (kind, count) =>
      logger.info("Message type {} count={}", kind, Long.box(count.get()))
    }

  def writeJson(path: java.nio.file.Path): Unit =
    import io.circe.Encoder
    import io.circe.syntax.*
    import java.nio.file.Files

    final case class Metrics(totalSent: Long, totalReceived: Long, byType: Map[String, Long])
    given Encoder[Metrics] = Encoder.forProduct3("totalSent", "totalReceived", "byType")(m => (m.totalSent, m.totalReceived, m.byType))

    val m = Metrics(
      totalSent = totalMessagesSent.get(),
      totalReceived = totalMessagesReceived.get(),
      byType = messagesByType.asScala.view.mapValues(_.get()).toMap
    )
    Files.writeString(path, m.asJson.spaces2)
