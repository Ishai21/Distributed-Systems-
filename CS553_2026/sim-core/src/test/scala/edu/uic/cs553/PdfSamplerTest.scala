package edu.uic.cs553

import edu.uic.cs553.pdf.PdfSampler
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

class PdfSamplerTest extends AnyFlatSpec with Matchers:
  "PdfSampler.sample" should "always return a configured type" in
    {
      val pdf1 = Map("PING" -> 0.5, "WORK" -> 0.5)
      val rng = new Random(99L)
      List.fill(20)(PdfSampler.sample(pdf1, rng)).forall(pdf1.contains) shouldBe true
    }

  "PdfSampler.uniform" should "produce equal probabilities" in
    {
      val pdf2 = PdfSampler.uniform(List("PING", "WORK", "ACK"))
      pdf2.values.toSet.size shouldBe 1
    }

  "PdfSampler.zipf" should "sum to one" in
    {
      val pdf3 = PdfSampler.zipf(List("PING", "WORK", "ACK"))
      math.abs(pdf3.values.sum - 1.0) should be < 1e-9
    }

  "PdfSampler.sample" should "be deterministic with a fixed seed" in
    {
      val pdf4 = Map("PING" -> 0.6, "WORK" -> 0.4)
      val rng1 = new Random(7L)
      val rng2 = new Random(7L)
      val seq1 = List.fill(10)(PdfSampler.sample(pdf4, rng1))
      val seq2 = List.fill(10)(PdfSampler.sample(pdf4, rng2))
      seq1 shouldBe seq2
    }
