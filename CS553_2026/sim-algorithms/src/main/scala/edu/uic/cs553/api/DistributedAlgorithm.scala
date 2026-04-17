package edu.uic.cs553.api

trait DistributedAlgorithm:
  def name: String
  def onStart(ctx: AlgorithmContext): Unit
  def onMessage(ctx: AlgorithmContext, msg: Any): Unit
  def onTick(ctx: AlgorithmContext): Unit = ()
