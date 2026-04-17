#!/usr/bin/env bash
# Reproducible grader commands — run from the CS553_2026 directory (same as build.sbt).
set -euo pipefail
cd "$(dirname "$0")/.."
echo "== compile =="
sbt compile
echo "== test =="
sbt test
echo "== experiment 1 (ring election, synthetic) =="
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment1.conf --run 10s --out outputs/reproduce-exp1"
echo "== experiment 2 =="
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment2.conf --run 10s --out outputs/reproduce-exp2"
echo "== experiment 3 =="
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment3.conf --run 10s --out outputs/reproduce-exp3"
echo "Done. Artifacts under outputs/reproduce-*/"
