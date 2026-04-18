# CS553 Distributed Systems Simulation Report

Runnable commands, CLI flags, and troubleshooting: **`README.md`** in the **`CS553_2026/`** directory (this folder’s parent). The repo root **`readme.md`** / **`README.md`** only point here.

## 1. Architecture Overview

```text
NetGameSim graph (.json or .dot)
            |
            v
      GraphLoader
            |
            v
     GraphEnricher
   edge labels + node PDFs
            |
            v
   SimRunner / ActorSystem
            |
            +--> NodeActor per graph node
            +--> edge labels enforced per channel
            +--> PDF sampled background traffic
            |
            +--> ProbAnonymousRingElection
            +--> PetersonKearnsRollback
            |
            v
   SLF4J logs + MetricsCollector + summary files
```

## 2. Graph Model and Enrichment Design Decisions

The graph is represented with immutable case classes: `SimNode`, `SimEdge`, `NodePdf`, and `EnrichedGraph`. Raw graphs are loaded with empty edge labels and no PDFs, then enriched in a second phase. This keeps graph ingestion independent from experiment policy.

Edge labels are config-driven. Every edge receives the default allowed types unless an override exists, and `CONTROL` is always inserted so algorithm traffic is never blocked by accident. Node PDFs also start from config defaults and can be overridden on a per-node basis. The enricher validates that every PMF sums to `1.0 +/- 0.001` and fails fast on invalid configurations.

## 3. Actor Runtime Design

Akka Classic was used instead of Akka Typed because the project specification explicitly requires classic actors and the runtime pattern maps naturally to `Actor`, `Props`, `Timers`, and `ActorRef`. Each graph node becomes one `NodeActor`, and directed edges become outgoing `ActorRef` channels stored in the actor state.

The runtime enforces edge labels before dispatching algorithm payloads. Messages whose `kind` is not allowed on a channel are dropped and logged at `WARN`. Timer nodes use `Timers.startTimerAtFixedRate`, while input nodes accept injected traffic through `ExternalInput`. Background traffic uses per-node PDFs sampled by a seeded RNG, making runs reproducible under fixed config seeds.

## 4. Probabilistic Anonymous Ring Election

The ring election algorithm constructs an explicit ring from the sorted node list in `SimMain`, regardless of the loaded graph structure. `GraphLoader` adds matching **directed ring edges** (sorted id order) whenever `algorithmName` is `ring-election` or `both`, for **both** synthetic graphs and **file-loaded** NetGameSim DOT graphs, so `CONTROL` election traffic has real `ActorRef` channels. Each node generates a seeded random election value and forwards election messages around the ring. Lower-valued messages are discarded, larger values are forwarded, and ties trigger a new round with a fresh random value.

Correctness intuition: because the ring is finite and each node compares the same candidate value against its own local value, only a value that is not dominated by any node can traverse the entire ring. When that value returns after `nodeCount` hops, the owner announces leadership. A `LeaderMsg` is forwarded once per leader value to prevent infinite circulation.

Experiment result summary: on the NetGraph topology with the ring overlay, the algorithm elects a single leader and produces a visible control-message wave in logs. Under the shared seed, the chosen leader is deterministic for each run.

## 5. Peterson-Kearns Rollback Recovery

Each node maintains checkpoint, send, and receive logs. Every processed message increments local counters; after `checkpointInterval` messages the node records a checkpoint snapshot. Initiator nodes trigger rollback once they have a checkpoint. Rollback requests are broadcast as `CONTROL` messages.

Consistency argument: rollback keeps only log entries at or before the selected checkpoint sequence. This removes sends and receives that happened after the checkpoint boundary and prevents orphaned post-checkpoint state from surviving the rollback. Once acknowledgements return to the initiator, the runtime logs that a consistent global state has been restored.

Experiment result summary: with `--algorithm rollback` (or `both`), timer nodes drive checkpoints and rollback completes with `RollbackAck` and summary logs.

## 6. Experiment Configurations

| config | topology | algorithm | result |
| --- | --- | --- | --- |
| `NetGraph.conf` | NetGameSim-style DOT, **`graph/NetGraph_14-04-26-16-41-08.ngs.dot`** (101 nodes) | `both` by default; override with `--algorithm` | leader lines in `run.log` when election runs; rollback + traffic |

## 7. Instrumentation and metrics (no private repositories)

This submission **does not** use Lightbend Cinnamon so that **`sbt compile` / `sbt test` succeed on any machine with only Maven Central**—no Akka Account or tokenized `akka.sbt` file. The rubric allows a small deduction for missing Cinnamon JVM/agent instrumentation; that is an explicit trade-off so grading never fails on credential or private-artifact resolution.

Observable behavior uses **`MetricsCollector`**: counts for messages sent and received (per node at shutdown and aggregate), per message kind, and optional `metrics.json` when `--out` is set. **SLF4J** records run lifecycle, node initialization, algorithm milestones, and warnings (for example dropped edge violations). With **`--out`**, the CLI also attaches a **`run.log`** file appender so the same lines (including **“became leader”** / **“Leader elected”**) are available under that directory without scraping the console.

## 8. How To Reproduce Experiments

```bash
cd CS553_2026
sbt compile
sbt test
mkdir -p outputs/NetGraph
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --run 30s --out outputs/NetGraph"
```

Use the **`simCli`** project id (not **`sim-cli`**). Run from the directory that contains **`build.sbt`** so **`conf/`** and **`graph/…`** paths resolve.

**Algorithm variants** (same **`NetGraph.conf`**, separate output dirs):

```bash
mkdir -p outputs/netgraph-ring-election outputs/netgraph-rollback outputs/netgraph-both
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --algorithm ring-election --run 30s --out outputs/netgraph-ring-election"
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --algorithm rollback --run 30s --out outputs/netgraph-rollback"
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --algorithm both --run 30s --out outputs/netgraph-both"
```

Alternatively **`./scripts/reproduce.sh`** from **`CS553_2026`** runs compile, test, and one **`NetGraph.conf`** run into **`outputs/NetGraph/`** (including **`run.log`**).

## 9. Known Limitations and Future Work

The rollback implementation models checkpoint consistency conservatively through log truncation instead of performing a full multi-node orphan-message proof. Future work includes explicit channel actors for delays/loss, richer experiment reporting, and a stricter global-consistency checker.
