package edu.uic.cs553.api

trait AlgorithmContext:
  def nodeId: Int
  def neighborIds: Set[Int]
  def send(to: Int, kind: String, payload: String): Unit
  def log: org.slf4j.Logger
