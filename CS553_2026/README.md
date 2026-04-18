# CS553_2026 — Distributed graph simulation (Akka Classic)

Course project: load a **NetGameSim-exported** topology (DOT), **enrich** it with Typesafe Config (allowed message kinds per edge, per-node traffic PDFs), run an **Akka Classic** simulation (**one actor per node**, **one logical channel per directed edge**), and collect **SLF4J logs**, **`MetricsCollector`** counters, and optional **JSON artifacts**.

**Single config:** `conf/NetGraph.conf` points at `../NetGameSim/NetGraph_14-04-26-16-41-08.ngs.dot` (101 nodes). Algorithms are selected in that file or overridden on the CLI (`ring-election`, `rollback`, `both`).

**Logs on disk:** if you pass **`--out <dir>`**, the CLI writes **`run.log`** in that directory (same content as console SLF4J), so you can find the leader with `grep -i leader <dir>/run.log`.

Design notes and rubric context: **`docs/report.md`**.

---

## Prerequisites

| Requirement | Notes |
|-------------|--------|
| **JDK** | 11+ (17 typical) |
| **SBT** | 1.9.x |
| **Scala** | Resolved by SBT (**Scala 3.3.x**) |

**Working directory:** run SBT and the CLI from **`CS553_2026/`** (the directory that contains **`build.sbt`**). Forked `runMain` uses that folder as the JVM working directory so **`conf/NetGraph.conf`** and **`../NetGameSim/...`** resolve correctly.

---

## Repository layout

| Path | Role |
|------|------|
| **`build.sbt`** | Multi-module build: `simCore`, `simAlgorithms`, `simRuntimeAkka`, **`simCli`** |
| **`conf/NetGraph.conf`** | Only HOCON config: graph path, seed, duration, algorithms, traffic, initiators, rollback |
| **`sim-core/`** | `SimConfig`, `GraphLoader`, `GraphEnricher`, graph model, PDF sampling |
| **`sim-algorithms/`** | `ProbAnonymousRingElection`, `PetersonKearnsRollback` |
| **`sim-runtime-akka/`** | `NodeActor`, `SimRunner`, `MetricsCollector` |
| **`sim-cli/`** | Entry point **`edu.uic.cs553.simMain`**, **`RunLog`** (file appender for `--out`) |
| **`docs/report.md`** | Architecture, design decisions, reproduction, limitations |
| **`scripts/reproduce.sh`** | `sbt compile`, `sbt test`, one NetGraph run → **`outputs/NetGraph/`** |
| **`../NetGameSim/`** (repo root) | NetGameSim assets; **`.ngs`** is native there; this sim reads **`.ngs.dot`** (or `.json`) |

**SBT selector:** use **`simCli`** (camelCase). **`sim-cli`** is not a valid project id.

---

## Pipeline (end to end)

1. **`SimConfig.load`** — parse **`conf/NetGraph.conf`** (or `--config` path).
2. **`GraphLoader.load`** — if `syntheticGraph = false`, read **`graphFilePath`** (DOT/JSON). Parser supports Graphviz-style lines with trailing `[...]` attributes. For **`ring-election`** or **`both`**, a **directed ring** over **sorted node ids** is merged into the edge set so election `CONTROL` messages have real neighbor `ActorRef`s.
3. **`GraphEnricher.enrich`** — attach default or overridden allowed kinds per edge; attach per-node PDFs; ensure `CONTROL` is allowed where needed.
4. **`SimRunner`** — spawn **`ActorSystem`**, one **`NodeActor`** per node, wire neighbors from enriched edges, start timers / input handling.
5. **Algorithms** — plugins on each node (`ring-election`, `rollback`, or composed **`both`**).
6. **Shutdown** — after **`runDurationSeconds`**, collect per-actor stats, print metrics, write **`--out`** files if requested.

---

## Algorithms

| `algorithmName` in conf / `--algorithm` | Behavior |
|----------------------------------------|----------|
| **`ring-election`** | Probabilistic anonymous ring leader election only |
| **`rollback`** | Peterson–Kearns-style checkpoint / rollback only |
| **`both`** | Both plugins on every node (same `onMessage` / `onTick` pipeline) |

Default in **`NetGraph.conf`**: **`both`**. Override without editing the file:

```text
--algorithm ring-election | --algorithm rollback | --algorithm both
```

---

## `conf/NetGraph.conf` (reference)

| Key area | Purpose |
|----------|---------|
| **`sim.graphFilePath`** | Relative DOT path: **`../NetGameSim/NetGraph_14-04-26-16-41-08.ngs.dot`** |
| **`sim.syntheticGraph`** | **`false`** for file-backed topology |
| **`sim.algorithmName`** | Default **`both`**; CLI **`--algorithm`** overrides |
| **`sim.seed`**, **`sim.runDurationSeconds`** | Reproducibility and wall-clock sim length; CLI **`--run 30s`** overrides duration |
| **`messages.types`** | Declared message kind names |
| **`edgeLabeling`** | Default allowed kinds per edge; optional per-edge overrides |
| **`traffic`** | Tick interval, **`defaultPdf`**, optional **`perNodePdf`** (PMFs must sum to 1) |
| **`initiators`** | Timer nodes (PDF-driven sends) and input nodes (injections) |
| **`rollback.checkpointInterval`** | Messages between checkpoints for rollback |

To use a **synthetic** graph instead, copy **`NetGraph.conf`** to a new file, set **`syntheticGraph = true`**, **`nodeCount`**, **`edgeProbability`**, clear or ignore **`graphFilePath`**, and pass **`--config`** to that file.

---

## Command-line flags

| Flag | Example | Purpose |
|------|---------|---------|
| **`--config`** | **`--config conf/NetGraph.conf`** | HOCON file (required for normal runs) |
| **`--run`** | **`--run 30s`** or **`--run 45`** | Override **`runDurationSeconds`** (`s` suffix optional) |
| **`--algorithm`** | **`--algorithm rollback`** | Override **`algorithmName`** |
| **`--graph`** | **`--graph ../NetGameSim/other.ngs.dot`** | Topology file; forces **`syntheticGraph`** off for that run |
| **`--out`** | **`--out outputs/NetGraph`** | Writes **`graph.json`**, **`summary.json`**, **`metrics.json`**, **`run.log`** |
| **`--inject`** | **`--inject injections.txt`** | Lines: **`<delayMs> <nodeId> <kind> <payload>`** |
| **`--interactive`** | | stdin injections while the sim runs |

Unknown arguments cause **`simMain`** to fail fast with an error.

---

## Commands

### Build and unit tests

```bash
cd CS553_2026
sbt compile
sbt test
```

### Default NetGraph run (`both`, artifacts + `run.log`)

```bash
mkdir -p outputs/NetGraph
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --run 30s --out outputs/NetGraph"
grep -i leader outputs/NetGraph/run.log
```

### One output directory per algorithm (same topology)

```bash
mkdir -p outputs/netgraph-ring-election outputs/netgraph-rollback outputs/netgraph-both
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --algorithm ring-election --run 30s --out outputs/netgraph-ring-election"
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --algorithm rollback --run 30s --out outputs/netgraph-rollback"
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --algorithm both --run 30s --out outputs/netgraph-both"
```

### Reproduce script (CI / grader style)

From **`CS553_2026`**:

```bash
./scripts/reproduce.sh
```

This runs **`compile`**, **`test`**, then **`NetGraph.conf`** for 25s into **`outputs/NetGraph/`** (includes **`run.log`**).

### Optional: explicit graph path (same file as in config)

```bash
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --graph ../NetGameSim/NetGraph_14-04-26-16-41-08.ngs.dot --algorithm both --run 30s --out outputs/NetGraph"
```

### Injections

```bash
# File-based (create injections.txt first)
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --inject injections.txt --out outputs/with-inject"

# Interactive stdin
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --interactive"
```

---

## Output artifacts (`--out <dir>`)

| File | Contents |
|------|----------|
| **`run.log`** | Full SLF4J mirror (leader election, rollback, warnings, node init) |
| **`graph.json`** | Enriched topology: nodes, edges with allowed kinds, PDF-related data |
| **`summary.json`** | Run duration and per-node sent/received counts |
| **`metrics.json`** | Aggregated **`MetricsCollector`** counters |

The **`outputs/`** tree is listed in **`.gitignore`**; regenerate locally as needed.

---

## Troubleshooting

| Issue | What to check |
|-------|----------------|
| **`Graph file does not exist`** | Run from **`CS553_2026`**; confirm **`../NetGameSim/NetGraph_14-04-26-16-41-08.ngs.dot`** exists |
| **`No nodes found in DOT file`** | Use a **`.dot`** (or **`.json`**) export; raw **`.ngs`** is not read by **`GraphLoader`** |
| **No leader lines in `run.log`** | Use **`ring-election`** or **`both`**; ring overlay is applied automatically for those modes on loaded graphs |
| **SBT “project not found”** | Selector must be **`simCli`**, not **`sim-cli`** |

---

## Grading and reproducibility

- Dependencies resolve from **Maven Central** only (no private Akka / Cinnamon Maven credentials). In-process metrics use **`MetricsCollector`** and logs; see **`docs/report.md`** for the Cinnamon trade-off if the rubric mentions it.
- **`Compile / run`** for **`simCli`** forks with working directory **`CS553_2026`**, so relative **`conf/`** and **`../NetGameSim/`** paths match README examples.

### Minimal smoke (compile + test + short sim)

```bash
cd CS553_2026
sbt compile && sbt test
mkdir -p outputs/grader-smoke
sbt "simCli/runMain edu.uic.cs553.simMain --config conf/NetGraph.conf --run 10s --out outputs/grader-smoke"
```

Artifacts (including **`run.log`**) appear under **`outputs/grader-smoke/`**.
