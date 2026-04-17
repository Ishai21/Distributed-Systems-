# CS553_2026 — Distributed graph simulation (Akka Classic)

Scala 3 project: load or synthesize a graph, **enrich** it (edge labels + per-node message PDFs), run an **Akka Classic** simulation where **each node is one actor** and **each directed edge is an outgoing channel**, then collect **logs**, **metrics**, and optional **JSON artifacts**.

---

## Prerequisites

| Requirement | Notes |
|-------------|--------|
| **JDK** | 11 or newer (17 is typical) |
| **SBT** | 1.9.x |
| **Scala** | Pulled by SBT (project uses **Scala 3.3.x**) |

Always run CLI commands from the directory that contains **`build.sbt`** (the `CS553_2026` folder), so paths like `conf/...` resolve.

---

## How the pipeline works (high level)

1. **Config** — `conf/*.conf` (Typesafe Config) sets seed, duration, algorithm, graph size, edge labels, PDFs, timer/input nodes, rollback interval.
2. **Graph** — Either **synthetic** (`nodeCount`, `edgeProbability`) or a **file** (`--graph` for JSON or NetGameSim `.dot`).
3. **Enrichment** — `GraphEnricher` attaches allowed message **kinds per edge** and a **PDF** per node (probabilities must sum to 1).
4. **Runtime** — One **`NodeActor`** per node; neighbors are **`ActorRef`**s. **Forbidden** message kinds on an edge are **dropped** and logged.
5. **Traffic** — Timer nodes tick and sample the PDF; input nodes can receive **injected** messages (file or interactive).
6. **Algorithms** — Selected by name (see below). **`both`** runs ring election and rollback **together** on every node (composed plugin).
7. **End of run** — Sleep for configured duration, then **ask** each actor for counts, print **`MetricsCollector`** summary, optionally write files under **`--out`**.

---

## Repository layout

| Path | Role |
|------|------|
| `build.sbt` | Multi-module build (`simCore`, `simAlgorithms`, `simRuntimeAkka`, **`simCli`**) |
| `conf/` | Experiment configs (`experiment1.conf` …) and `sim.conf` |
| `sim-core/` | Graph model, loader, enrichment, PDF sampling, `SimConfig` |
| `sim-algorithms/` | **Probabilistic anonymous ring election**, **Peterson–Kearns rollback** |
| `sim-runtime-akka/` | `NodeActor`, `SimRunner`, `MetricsCollector` |
| `sim-cli/` | Entry point: **`edu.uic.cs553.simMain`** |
| `docs/report.md` | Design notes and experiment narrative |
| `scripts/reproduce.sh` | Optional: compile, test, three experiments with `--out` |

**SBT project id:** use **`simCli`** (camelCase). The name `sim-cli` will not work as a project selector.

---

## Algorithms: one, the other, or both

| `algorithmName` / `--algorithm` | Meaning |
|---------------------------------|--------|
| **`ring-election`** | Anonymous probabilistic ring leader election only |
| **`rollback`** | Peterson–Kearns style rollback only |
| **`both`** | Both plugins on **every** node (combined `onMessage` / `onTick`) |

Override the config file with **`--algorithm ring-election`**, **`--algorithm rollback`**, or **`--algorithm both`**.

---

## Bundled experiments (what we actually configured)

These are **illustrative sizes**, not a single fixed “100-node” default. You can raise **`nodeCount`** (e.g. to **100**) in any `conf` file for a larger topology—generation cost and log volume grow with size.

| Config file | Nodes | Edge density (`edgeProbability`) | Algorithm | Seed | Default run length | PDF / traffic (short) |
|-------------|-------|-----------------------------------|-----------|------|--------------------|------------------------|
| `experiment1.conf` | **8** | 0.25 (sparse) | ring-election | 1001 | 20s | PING 0.7, GOSSIP 0.3 |
| `experiment2.conf` | **15** | 0.6 (dense) | rollback | 2002 | 45s | WORK / PING / GOSSIP (0.5 / 0.3 / 0.2) |
| `experiment3.conf` | **25** | 0.5 | **both** | 3003 | 60s | Five message types with fixed probs; **node 5** has a custom PDF |
| `sim.conf` | **10** | 0.4 | both | 42 | 30s | PING / GOSSIP / WORK |

**Timers** periodically drive PDF-sampled traffic; **inputs** accept injections where listed (see each file’s `initiators` block).

For **`ring-election`** and **`both`** on **synthetic** graphs, the loader **adds a directed cycle** `0→1→…→n-1→0` on top of the random edges. The election algorithm forwards along that logical ring using real `ActorRef` channels; without those edges, a sparse random graph often **never** completes an election, so you would see no **“became leader”** lines in the log.

---

## Command-line flags (main ones)

| Flag | Example | Purpose |
|------|---------|---------|
| `--config` | `--config conf/experiment1.conf` | Load simulation settings (required for predictable runs) |
| `--run` | `--run 5s` or `--run 30` | Override **`runDurationSeconds`** (suffix `s` optional) |
| `--algorithm` | `--algorithm rollback` | Override **`algorithmName`** in the file |
| `--graph` | `--graph path/to/graph.dot` | Load graph from file; turns off synthetic unless you rely on config |
| `--out` | `--out outputs/my-run` | Write **`graph.json`**, **`summary.json`**, **`metrics.json`** |
| `--inject` | `--inject injections.txt` | Scheduled injections: `<delayMs> <nodeId> <kind> <payload>` per line |
| `--interactive` | | Type `<nodeId> <kind> <payload>` lines while running |

---

## Commands you will actually run

**Build and tests**

```bash
cd CS553_2026
sbt compile
sbt test
```

**Single algorithm (from config)**

```bash
# Ring election — sparse 8-node graph
mkdir -p outputs/exp1-ring
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment1.conf --run 5s --out outputs/exp1-ring"

# Rollback — denser 15-node graph
mkdir -p outputs/exp2-rollback
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment2.conf --run 5s --out outputs/exp2-rollback"
```

**Both algorithms together**

```bash
mkdir -p outputs/exp3-both
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment3.conf --run 10s --out outputs/exp3-both"
```

**Override algorithm without editing the file**

```bash
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/sim.conf --algorithm ring-election --run 5s"
```

**Load a NetGameSim / exported graph** (path relative to your cwd)

```bash
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/sim.conf --graph ../NetGameSim/NetGraph_14-04-26-16-41-08.ngs.dot --algorithm both --run 30s"
```

**Injection file** (create `injections.txt` first)

```bash
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment2.conf --inject injections.txt --out outputs/with-inject"
```

**Interactive injection**

```bash
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/sim.conf --interactive"
```

**Larger synthetic graph (e.g. 100 nodes)** — edit `conf/experiment1.conf` (or a copy): set **`nodeCount = 100`** and tune **`edgeProbability`**; then run with `--config` pointing at that file.

---

## What you see when it works

- **Console (SLF4J):** graph load/generation, enrichment, node init, algorithm milestones (election, rollback, acks), optional DEBUG traffic lines.
- **Shutdown:** per-node sent/received counts, **`MetricsCollector`** totals and per–message-kind counts.
- **With `--out`:**
  - **`graph.json`** — enriched topology (nodes, edges, labels/PDF-related data as written by `GraphWriter`)
  - **`summary.json`** — duration and per-node message counts
  - **`metrics.json`** — aggregated counters from `MetricsCollector`

---

## Grading / reproducibility

- **No private Maven credentials** — dependencies resolve from **Maven Central** only. Cinnamon was omitted so a clean machine can **`sbt compile`** / **`sbt test`** without tokens; metrics come from **`MetricsCollector`** + logs (see rubric trade-off in `docs/report.md` if Cinnamon is required for points).
- **Forked run** uses the **build root** as working directory so **`conf/`** paths work from SBT.

---

## Quick grader smoke test

```bash
cd CS553_2026
sbt compile && sbt test
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/experiment1.conf --run 5s --out outputs/grader-smoke"
```

Artifacts appear under **`outputs/grader-smoke/`**.
