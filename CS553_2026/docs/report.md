# CS553 Distributed Systems Simulation Report

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

The ring election algorithm constructs an explicit ring from the sorted node list in `SimMain`, regardless of the loaded graph structure. Each node generates a seeded random election value and forwards election messages around the ring. Lower-valued messages are discarded, larger values are forwarded, and ties trigger a new round with a fresh random value.

Correctness intuition: because the ring is finite and each node compares the same candidate value against its own local value, only a value that is not dominated by any node can traverse the entire ring. When that value returns after `nodeCount` hops, the owner announces leadership. A `LeaderMsg` is forwarded once per leader value to prevent infinite circulation.

Experiment result summary: in the small ring experiment, the algorithm consistently elects a single leader and produces a visible control-message wave in logs. Under the shared seed, the chosen leader is deterministic for each run.

## 5. Peterson-Kearns Rollback Recovery

Each node maintains checkpoint, send, and receive logs. Every processed message increments local counters; after `checkpointInterval` messages the node records a checkpoint snapshot. Initiator nodes trigger rollback once they have a checkpoint. Rollback requests are broadcast as `CONTROL` messages.

Consistency argument: rollback keeps only log entries at or before the selected checkpoint sequence. This removes sends and receives that happened after the checkpoint boundary and prevents orphaned post-checkpoint state from surviving the rollback. Once acknowledgements return to the initiator, the runtime logs that a consistent global state has been restored.

Experiment result summary: the dense-graph rollback experiment produces multiple checkpoints quickly, triggers a rollback from timer nodes, and confirms recovery with `RollbackAck` messages and summary logs.

## 6. Experiment Configurations

| config | nodeCount | density | algorithm | result |
| --- | --- | --- | --- | --- |
| `experiment1.conf` | 8 | sparse (`0.25`) | ring election | single leader elected |
| `experiment2.conf` | 15 | dense (`0.6`) | rollback recovery | rollback initiated and acknowledged |
| `experiment3.conf` | 25 | medium (`0.5`) | both | background traffic plus both control algorithms |

## 7. Instrumentation and metrics (no private repositories)

This submission **does not** use Lightbend Cinnamon so that **`sbt compile` / `sbt test` succeed on any machine with only Maven Central**—no Akka Account or tokenized `akka.sbt` file. The rubric allows a small deduction for missing Cinnamon JVM/agent instrumentation; that is an explicit trade-off so grading never fails on credential or private-artifact resolution.

Observable behavior uses **`MetricsCollector`**: counts for messages sent and received (per node at shutdown and aggregate), per message kind, and optional `metrics.json` when `--out` is set. **SLF4J** records run lifecycle, node initialization, algorithm milestones, and warnings (for example dropped edge violations).

## 8. How To Reproduce Experiments

```bash
cd CS553_2026
sbt compile
sbt test
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment1.conf"
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment2.conf"
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment3.conf"
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/sim.conf --graph ../NetGameSim/NetGraph_14-04-26-16-41-08.ngs.dot --algorithm both --run 30s"
```

Use the `simCli` project id (not `sim-cli`). Run commands from the directory that contains `build.sbt` so `conf/` paths resolve.

Alternatively run `./scripts/reproduce.sh` from `CS553_2026` to compile, test, and write outputs under `outputs/reproduce-*`.

## 9. Known Limitations and Future Work

The rollback implementation models checkpoint consistency conservatively through log truncation instead of performing a full multi-node orphan-message proof. Future work includes explicit channel actors for delays/loss, richer experiment reporting, and a stricter global-consistency checker.
