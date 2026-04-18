#!/usr/bin/env bash
# Reproducible grader commands — run from the CS553_2026 directory (same as build.sbt).
set -euo pipefail
cd "$(dirname "$0")/.."
echo "== compile =="
sbt compile
echo "== test =="
sbt test
echo "== NetGraph (NetGameSim DOT -> outputs/NetGraph, includes run.log) =="
mkdir -p outputs/NetGraph
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --run 25s --out outputs/NetGraph"
echo "Done. Artifacts under outputs/NetGraph/ (see run.log)."
