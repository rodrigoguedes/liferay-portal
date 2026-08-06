# How the test class works

A walkthrough of `PreviewSearchBenchmarkTest`. Read this if you need to change the test, debug a run, or
convince yourself the numbers mean what the report says.

Location:

```
modules/apps/journal/journal-test/src/testIntegration/java/com/liferay/journal/search/test/
  PreviewSearchBenchmarkTest.java
```

---

## 1. Why an integration test is the harness at all

The thing worth measuring is what `JournalArticleModelPreFilterContributor` produces — the real
`BooleanFilter`, the real `TermsFilter` serialization, the real engine query. Anything that rebuilds that query
by hand measures the reconstruction, not the code.

A Liferay integration test is the cheapest way to be inside the container with the real OSGi wiring:
`@Inject`ed services, the real indexer, the real `Searcher`. The cost is that everything else about it is
awkward, and most of what follows is dealing with that awkwardness honestly.

**The consequence that surprises everyone once:** Arquillian deploys the test class *into the portal's OSGi
container*, so `System.getProperty(...)` in the test body reads the **portal JVM's** properties — not the
shell's, not Gradle's test JVM's. `RUNBOOK.md` §3 covers what that means operationally.

---

## 2. One `@Test`, not one per cell

The whole matrix runs inside a single `@Test` method. That is deliberate:

- JUnit gives no ordering guarantee across methods, and this benchmark needs the global warm-up to happen
  before the first measured cell (§5).
- A per-method deploy/undeploy cycle would add container churn between cells.
- One method means one JVM state for the whole run, so cells are comparable.

The trade-off is that a failure anywhere aborts the sweep. Acceptable: a partial curve is not usable anyway.

**Note also what is deliberately absent: `SearchTestRule`.** That rule re-creates indexes between tests, which
is exactly the kind of state reset that would make cache behaviour incomparable across cells.

---

## 3. Phase 1 — preparation (`@Before`)

In order:

1. **Read configuration** from system properties, all prefixed `preview.benchmark.`, and **echo the effective
   values into the log.** Check that echo on the first run — it is how you find out whether your properties
   actually reached the portal JVM.
2. **`_setUpGroupAndFolder()`** — three modes: create a throwaway group (default), reuse a group by id
   (`preview.benchmark.group.id`), or keep the created group after the run
   (`preview.benchmark.keep.group=true`). The latter two exist so a slow-to-build corpus can survive a run.
3. **`_enableIndexAllArticleVersions()`** — sets `indexAllArticleVersionsEnabled`. Without it the draft versions
   are never indexed, the include-side `terms` filter matches nothing, and the benchmark reports a flat,
   reassuring, wrong curve.
4. **Seed N live/draft pairs** via `_addArticleWithDraft(int)`: `JournalTestUtil.addArticleWithWorkflow` for the
   approved article, then `JournalTestUtil.updateArticle` with `WorkflowConstants.ACTION_SAVE_DRAFT` for a real
   **draft version of the same article**. It asserts the two have distinct `getId()` values, because that is the
   whole premise — all versions share `resourcePrimKey`, so only the per-version key distinguishes them.
5. **`_awaitIndexReady()`** — polls until the expected document count is visible, instead of sleeping.
6. **Two guards that fail the run loudly**, §4.

---

## 4. The two guards, and why the obvious check does not work

### `_assertPreviewAttributeIsRead()`

The failure mode this defends against is the dangerous one: if the contributor does not read the attribute the
test sends, the search silently returns ordinary approved results and every latency number is baseline latency
labelled as preview latency. That looks like a successful run.

The obvious check — compare result counts with and without the map — **cannot work**, because the swap is 1:1.
Exchanging one live version for one draft leaves the count unchanged whether or not the attribute took effect.

So the guard sends a map whose **target does not exist** (`_NONEXISTENT_CLASS_PK = 999999999L`). The contributor
then excludes the live version and includes nothing, and the count must drop by **exactly one**. If it does not
move, the run fails with an explanation.

### `_assertDraftsAreIndexed()`

Confirms the draft documents are actually in the index. This is the trap the original test plan warned about:
with unindexed drafts the postings union on the include side is empty, so the query is cheap and the curve looks
harmless.

---

## 5. Phase 3 (global) — `_runGlobalWarmup()`

**300 iterations across four search shapes, unrecorded, before the matrix starts.**

This exists because of a real bug the first smoke run produced: the preview deltas came out **negative**
(−0.58 to −2.09 ms), i.e. preview appeared *faster* than baseline. The cause was C2 compilation. The baseline
cell ran first, absorbed the JIT compilation cost of the whole search path, and every later cell benefited.

Per-cell warm-up does not fix that — each cell warms its own path, but the *first* cell still pays for code
shared by all of them. Only a global pre-warm before any measurement removes the bias.

If you ever see negative deltas again, suspect that this warm-up was shortened or skipped.

---

## 6. Phases 2–4 per cell — `_runCell()`

For each cell in the matrix (query variant × result size × cache mode × N):

| Phase | Method | Notes |
| --- | --- | --- |
| 2 — state reset | `_resetState()` | settle gap, engine refresh; no index re-creation, deliberately |
| 3 — warm-up | `_runWarmup(cell)` | per-cell, unrecorded, warms that specific query shape |
| 4 — measurement | `_runMeasurement(cell)` | fixed iteration count, capped by a duration ceiling |

`_runMeasurement` runs **sequentially at concurrency 1** by default — one iteration after another, so a slow
iteration cannot overlap the next and the measurement is a clean latency series. Above concurrency 1 it uses an
`ExecutorService` with start/finish latches so all threads begin together.

The **baseline cell is re-measured immediately before every N group**, per query variant. That is what makes the
deltas trustworthy: they compare against a baseline taken minutes, not tens of minutes, earlier. See
`WHAT-IS-MEASURED.md` §5 for the evidence that this matters.

---

## 7. What each iteration records — `_recordSample()`

One JSONL row per iteration, never pre-aggregated, carrying every dimension:

```
run_id, timestamp, git_sha, engine vendor/version/heap/cpus,
query_type, result_size, n_preview_items, concurrency, terms_key_type,
cache_mode, phase, iteration,
roundtrip_ms, engine_took_ms,
request_bytes, response_bytes, hits_total
```

`roundtrip_ms` is measured around the `Searcher` call. `engine_took_ms` is the engine's own `took`, read from
`SearchResponse.getSearchTimeValue()`. **Both are mandatory** — their difference is the Liferay + network cost,
and a `took`-only measurement hides exactly the part this task turned out to care about.

`request_bytes` comes from the serialized engine query, which the test also dumps to
`artifacts/<run>/queries/` — those dumps are how the doubled-clause defect in `FINDINGS.md` §3 was found.

The inner `_Recorder` writes to a file, synchronized, flushing every 100 rows. It writes to a file rather than
`System.out` because the test runs in-container, so stdout goes to the app-server console where CI cannot
capture it. `_log()` mirrors phase markers into `run.log` for the same reason — see `RUNBOOK.md` §7.

---

## 8. `@After`

Tears down what phase 1 created, unless `preview.benchmark.keep.group=true`. It does **not** delete the index or
reindex — see `WHAT-IS-MEASURED.md` §6 for the cache rule that depends on index state persisting.

---

## 9. Property reference

All properties are prefixed `preview.benchmark.` and read from the **portal JVM**. `run.sh` sets them for you
and writes a ready-made `catalina-opts.sh`; this list is for when you drive the test directly.

| Property | Purpose |
| --- | --- |
| `n.values` | comma-separated N sweep, e.g. `1,10,100,500,1000` |
| `query.variants` | `match-all,keyword,faceted` |
| `result.sizes` | page sizes, comma-separated |
| `concurrency` | 1 means a clean sequential latency series |
| `warmup.iterations` / `measure.iterations` | per cell |
| `measure.duration.seconds` | ceiling on the measurement window |
| `background.corpus.size` | extra articles seeded through the service layer (slow) |
| `cold.mode.enabled` / `baseline.enabled` | which cells are generated |
| `results.file` / `manifest.file` / `query.dump.dir` / `run.log.file` | artefact paths |
| `engine.vendor` / `engine.version` | recorded on every row so cells are self-describing |
| `group.id` / `keep.group` | corpus reuse |
| `swap.map.attribute.name` | defaults to `preview.swap.map`; override to test another key |
| `index.ready.timeout.seconds` / `reset.settle.millis` | timing knobs |
