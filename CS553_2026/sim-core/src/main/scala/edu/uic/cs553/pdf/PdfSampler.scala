package edu.uic.cs553.pdf

import scala.util.Random

object PdfSampler:
  def sample(pdf: Map[String, Double], rng: Random): String =
    val ordered = pdf.toVector.sortBy(_._1)
    val threshold = rng.nextDouble()
    ordered
      .scanLeft("" -> 0.0) { case ((_, acc), (kind, probability)) => kind -> (acc + probability) }
      .drop(1)
      .collectFirst { case (kind, cumulative) if threshold <= cumulative + 1e-12 => kind }
      .getOrElse(ordered.lastOption.map(_._1).getOrElse("CONTROL"))

  def uniform(types: List[String]): Map[String, Double] =
    if types.isEmpty then Map.empty
    else
      val p = 1.0 / types.size.toDouble
      types.map(_ -> p).toMap

  def zipf(types: List[String], s: Double = 1.0): Map[String, Double] =
    if types.isEmpty then Map.empty
    else
      val weights = types.zipWithIndex.map { case (kind, idx) => kind -> (1.0 / math.pow(idx + 1.0, s)) }
      val total = weights.map(_._2).sum
      weights.map { case (kind, weight) => kind -> (weight / total) }.toMap
