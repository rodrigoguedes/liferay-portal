# PoC 1 — preview-search benchmark as an integration test (LPD-98298)

This directory benchmarks the preview-search rewrite **in-process, inside the portal container**, using a
Liferay Arquillian integration test as the harness. It measures the Liferay search stack directly: building the
two `TermsFilter` clauses, serializing them to an engine query, the hop to Elasticsearch/OpenSearch, and the
engine's own `took`.

**This is the PoC that produces the N curve.** Its dispersion is small enough to resolve the effect the Jira
asks about — a 12 ms cost at N=1000, reproducible to 1.3% CV. The headless PoC measures what a client waits for,
which is a different and much noisier question.

The test class is **already committed on this branch**:

```
modules/apps/journal/journal-test/src/testIntegration/java/com/liferay/journal/search/test/
  PreviewSearchBenchmarkTest.java
```

It is **purely additive** — one new file, zero modifications to product code. That is the point of PoC 1: the
benchmark perturbs the code under test as little as possible. The test reads the `preview.swap.map`
`SearchContext` attribute, which the unpatched contributor already consumes.

## Read these in order

| Document | What it answers |
| --- | --- |
| **`RUNBOOK.md`** | How do I prepare the environment and run this? |
| **`HOW-IT-WORKS.md`** | What does the test class actually do, phase by phase? |
| **`WHAT-IS-MEASURED.md`** | What is being measured, and how do I read the numbers? |
| **`FINDINGS.md`** | What did it measure, and what can I quote? |

Short on time: `FINDINGS.md` §1 is the citable curve, and `RUNBOOK.md` §3 is the one configuration trap that
will otherwise cost you a run.

## Files

```
poc1-doc/
├── README.md                   this file
├── RUNBOOK.md                  prepare → run → collect → troubleshoot
├── HOW-IT-WORKS.md             walkthrough of the test class
├── WHAT-IS-MEASURED.md         the measurement model and how to read results
├── FINDINGS.md                 measured results, with their caveats
├── run.sh                      the orchestrator: 5-phase run around the test
├── build.gradle.snippet        optional CI-side property forwarding (not applied)
└── common/
    ├── analyze.py              JSONL → report.md + summary.csv
    ├── engine-stats.sh         engine version, resources, cache/thread-pool deltas
    └── preflight.sh            remote-mode, engine-identity and coverage guards
```

`artifacts/` is created at run time and is gitignored.

`common/analyze.py` also accepts `--format k6`, which is for the headless PoC's sample stream. It is kept
intact so both PoCs stay on one result schema; here you only ever use `--format poc1`, and `run.sh` calls it for
you.

`build.gradle.snippet` is **not applied** to any module. It forwards benchmark properties to the Gradle test
JVM — useful for a CI lane, but the authoritative channel is the **portal** JVM, because the test runs
in-container. `RUNBOOK.md` §3 explains why that distinction matters more than it sounds like it should.

## The 30-second version

```bash
# portal up, engine in remote mode, this branch built and deployed
./run.sh                                  # 33 cells, ~30 min

# then read
cat artifacts/<run-id>/report.md
```

`run.sh` needs no arguments — `PORTAL_DIR` defaults to this directory's parent, which is the checkout. Read
`RUNBOOK.md` before a run you intend to cite: there is one configuration channel that silently does nothing,
and one rule about discarding the first run after recreating the index.

## What this PoC is good for, and what it is not

**Good for:** the shape of the latency curve as N grows. Cost attribution — how much of the cost is the engine
versus Liferay's own serialization. Per-iteration data, so percentiles can be re-cut later. Capturing the exact
engine query the contributor produces, for inspection or for replay elsewhere.

**Not good for:** what an HTTP client pays. It runs in-process, so it never pays the cost of shipping the swap
map from a client to the portal, nor the auth and serialization overhead of the headless API. Use the headless
PoC for that. Also not good for high concurrency — it drives requests from within the container.
