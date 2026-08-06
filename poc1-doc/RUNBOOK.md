# PoC 1 Runbook — preparing, running and collecting

Operational guide. For what the test does internally see `HOW-IT-WORKS.md`; for what the numbers mean see
`WHAT-IS-MEASURED.md`; for what they came out to see `FINDINGS.md`.

---

## 1. What this branch contains

One added file, no product code modified:

```
modules/apps/journal/journal-test/src/testIntegration/java/com/liferay/journal/search/test/
  PreviewSearchBenchmarkTest.java
```

Verify the additive property at any time:

```bash
git diff --stat 9060d61eb1337    # expect: 1 file changed, insertions only
```

If anything else shows up, the "no portal-code change" property is gone and the run no longer measures the
unmodified stack. The test works against the **unpatched** contributor because it sets the `preview.swap.map`
`SearchContext` attribute, which that contributor already consumes.

---

## 2. Environment requirements

| Requirement | Why |
| --- | --- |
| **`ant all`, then deploy and restart** | The base branch adds the `com.liferay.portal.kernel.preview` package, so a bundle built from an older branch cannot compile `journal-api`. A stale `portal-kernel.jar` also produces confusing failures in unrelated modules. |
| **Portal running** | The test is deployed into the running container by Arquillian. |
| **Search engine in remote mode** | ES 8.19 or OS 2.19. Not the sidecar — see §2.2. |
| **`python3` (stdlib only), `curl`, `bash`** | No virtualenv, no pip install. |
| **Gradle heap headroom** | See §2.3. |

### 2.1 Two build traps, both hit on a real machine

**Gradle daemon OOM.** `build.properties` ships `-Xms8g` for the daemon. On a machine also running the portal and
the engine that gets the daemon OOM-killed mid-build. Fix with a gitignored override:

```properties
# build.<username>.properties
org.gradle.jvmargs=-Xmx5g
org.gradle.workers.max=4
```

Then run `ant update-gradle-properties` — `.gradle/gradle.properties` is **generated**, so the override does
nothing until you regenerate it. This one is easy to lose an hour to.

**Tomcat version mismatch.** `ant deploy` can report `BUILD SUCCESSFUL` while writing into a *different* Tomcat
directory than the one you are running. Pin it:

```properties
# app.server.<username>.properties
app.server.tomcat.version=10.1.55
```

Check where your bundle actually is before believing a deploy.

### 2.2 Remote mode, not the sidecar

With `productionModeEnabled=false` the portal launches its own Elasticsearch on port **9201**. Benchmarking that
measures a JVM sharing CPU and page cache with the portal.

`run.sh` checks two ways via `common/preflight.sh`:

- `assert_remote_mode` greps the OSGi config for `productionModeEnabled=B"true"` — note the OSGi typed-config
  form; a plain `="true"` grep gives a false negative.
- `assert_engine_url_matches_portal` compares `cluster_uuid`. Same UUID means the same cluster even on a
  different port, which is how a multi-node cluster on 9200/9201/9202 stops being mistaken for a sidecar.

There is also a **race worth knowing about**: if the portal starts before the remote engine is reachable, it
falls back to launching the sidecar, and `ElasticsearchSearchEngine` calls `System.exit(1)` when a node fails the
minimum-version check. Start the engine first.

### 2.3 `LIFERAY_HOME` is a sibling, not a child

`app.server.properties` sets `app.server.parent.dir=${project.dir}/../bundles`, so the bundle sits **next to**
the checkout:

```
development/
├── liferay-portal/     ← the checkout, contains poc1-doc/
└── bundles/            ← LIFERAY_HOME
    └── tomcat-10.1.55/
```

`run.sh` defaults `LIFERAY_HOME` to `${PORTAL_DIR}/../bundles` for that reason. Getting it wrong makes
`assert_remote_mode` find no OSGi config and report "cannot confirm remote mode" for entirely the wrong reason.

---

## 3. The configuration trap — read this before the first run

**An integration test runs inside the portal container.** `System.getProperty(...)` in the test body therefore
reads the **portal JVM's** properties. Not this shell's. Not Gradle's test JVM's.

So `gradle -Dpreview.benchmark.n.values=...` alone is **not reliable**. There are two channels:

| Channel | How | Status |
| --- | --- | --- |
| **(a) `CATALINA_OPTS`** | export before starting Tomcat, or add to `$TOMCAT/bin/setenv.sh`, then restart | **authoritative** |
| (b) Gradle `testIntegration { systemProperties(...) }` | `build.gradle.snippet` in this directory | convenience / CI, **not applied** |

`run.sh` writes a ready-made `artifacts/<run-id>/catalina-opts.sh` for (a), and passes the properties Gradle-side
as a belt-and-braces measure.

**Verify on the first run.** The test echoes its effective configuration in phase 1 — read it in
`artifacts/<run-id>/run.log`. If it reports defaults instead of your values, source the emitted
`catalina-opts.sh` into `setenv.sh`, restart the portal, and run again. `run.sh` also warns if the test log had
to be salvaged from its fallback path, which is a strong hint the properties never arrived.

`build.gradle.snippet` is deliberately **not applied** to `journal-test/build.gradle`, which is what keeps this
branch to one added file. Apply it only if you want the CI-side channel and accept losing that property.

---

## 4. Running

### 4.1 Smoke run

```bash
./run.sh   # with a small matrix:
N_VALUES=1,100 QUERY_VARIANTS=match-all COLD_MODE_ENABLED=false \
WARMUP_ITERATIONS=50 MEASURE_ITERATIONS=100 \
  ./run.sh
```

Check, in order:

1. `Code: <branch> @ <sha>` and no uncommitted-changes warning, if you intend to cite the numbers.
2. Phase 1 in `run.log` echoes **your** configuration, not defaults (§3).
3. The two guards passed — no "attribute had NO EFFECT", no "drafts not indexed".
4. `report.md` exists and `hits` is non-zero.

### 4.2 Full run

```bash
./run.sh
```

Defaults: N = 1, 10, 100, 500, 1000 × three query variants × warm/cold × a baseline per N group = **33 cells**,
300 measured iterations each, roughly **30 minutes**.

Recommended portal JVM flags, already in the emitted `catalina-opts.sh`:

```
-Xms4g -Xmx4g -XX:+AlwaysPreTouch
-XX:StartFlightRecording=settings=profile,filename=<run>/portal.jfr,dumponexit=true
```

A fixed pre-touched heap removes heap-resize noise from the measurement. The JFR recording lets you *prove*
warm-up converged rather than asserting it.

### 4.3 Two rules that decide whether a run is citable

**Discard the first run after recreating the index.** Across four runs `query_cache_hits` came out 0 / 16,362 /
16,358 / 16,361 — the cache is bimodal, and the zero belongs to the first run on a fresh index. That run also
produced the only non-monotonic curve. Run it, throw it away, then measure.

**Repeat the run before quoting a margin.** Measured dispersion over three warm-regime runs is CV 3.1% min /
7.0% median / 13.2% max, so a p95 difference under ~15% is not distinguishable in a single run. The N=1000
headline is the exception at 1.3% CV, and it is the exception because it was repeated.

### 4.4 Knobs

| Variable | Default | Notes |
| --- | --- | --- |
| `PORTAL_DIR` | this directory's parent | the checkout; override only if you moved `poc1-doc/` |
| `LIFERAY_HOME` | `${PORTAL_DIR}/../bundles` | sibling, not child (§2.3) |
| `N_VALUES` | `1,10,100,500,1000` | comma-separated |
| `QUERY_VARIANTS` | `match-all,keyword,faceted` | quote the curve from `match-all` only — `WHAT-IS-MEASURED.md` §4 |
| `RESULT_SIZES` | `20` | |
| `CONCURRENCY` | `1` | 1 gives a clean sequential latency series |
| `WARMUP_ITERATIONS` / `MEASURE_ITERATIONS` | `100` / `300` | per cell |
| `MEASURE_DURATION_SECONDS` | `30` | ceiling on the window |
| `BACKGROUND_CORPUS_SIZE` | `0` | service-layer seeding; slow, see §5 |
| `COLD_MODE_ENABLED` / `BASELINE_ENABLED` | `true` / `true` | |
| `TEST_CLASS` / `GRADLE_PROJECT` | the benchmark / `:apps:journal:journal-test` | |
| `GRADLE_DIR` / `GRADLE_WRAPPER` | `${PORTAL_DIR}/modules` / `${PORTAL_DIR}/gradlew` | **`modules/` is the Gradle root** — there is no `settings.gradle` at the repo root |
| `ARTIFACTS_DIR` / `RUN_ID` | `./artifacts` / timestamp | |
| `ENGINE_URL` | — | must match the cluster the portal uses |

---

## 5. The corpus, honestly

The test seeds its own live/draft pairs, which is enough for the sweep. A **background corpus** is a different
matter, and it is an open limitation rather than a solved problem.

`BACKGROUND_CORPUS_SIZE` seeds extra articles through the service layer. That produces real database rows and
real indexed documents — but it is slow, and it **stalls above roughly 4,800 pairs** with the portal CPU-bound
and the engine idle. That is the measured reason N=10000 is not covered by this PoC.

Three routes to a larger corpus, and where each stands:

| Route | Status |
| --- | --- |
| Service-layer seeding (`BACKGROUND_CORPUS_SIZE`) | Works, faithful, **caps out around 4,800 pairs** |
| Bulk-indexing documents straight into the engine | **Rejected as unfaithful.** It creates no database rows, and thousands of clones of one article destroy exactly the term-dictionary cardinality the `terms` filter exists to exercise. Fast, but it measures a fiction. |
| `ant build-db` + sample-sql | **Failed to load** — `Duplicate entry '1--0' for key 'MBCategory.IX_9E671C6A'`, most likely because the reduced tier inherits base message-board counts for a single site. |
| Restore a dataset the team already uses | **Untried.** This is the route to try next. |

State the corpus size in any report. The numbers in `FINDINGS.md` come from ~1,000–2,000 documents, which is
enough to resolve the filter cost and not enough to settle the caching question (`WHAT-IS-MEASURED.md` §6).

---

## 6. ES 8.19 vs OS 2.19

Run twice against different engines, then compare what was recorded:

```bash
source common/engine-stats.sh
source common/preflight.sh
compare_engine_resources artifacts/<es-run>/engine-baseline.json \
                         artifacts/<os-run>/engine-baseline.json
```

`assert_jira_engine_version` warns if the engine under test is neither ES 8.19 nor OS 2.19, and
`record_engine_baseline` captures heap and CPU count so "similar CPU-heap allocation" is a checkable claim rather
than an assumption.

**The caveat to carry into any report:** this comparison runs through two *different connector stacks*
(`portal-search-elasticsearch8` vs `portal-search-opensearch2`), so a difference is not necessarily an engine
difference. The confound-free comparison replays the captured query JSON — which this PoC dumps to
`artifacts/<run>/queries/` — directly against both engines with esrally / opensearch-benchmark.

---

## 7. Getting the results

```
artifacts/<run-id>/
├── results.jsonl            one row per iteration — the source of truth
├── report.md                ← start here
├── summary.csv              one row per cell, for a spreadsheet
├── manifest.json            every knob, engine vendor/version/heap/cpus
├── run.log                  the test's own phase markers and config echo
├── gradle.log               the Gradle client side
├── portal-console.log       tail of catalina.out
├── engine-baseline.json     engine resources, for the ES-vs-OS comparison
├── engine-stats-{before,after,delta}.json
├── catalina-opts.sh         ready-made portal JVM options (§3)
├── jira-coverage.md         which Desired Outcomes this run covered
├── portal.jfr               if you enabled the JFR flag
└── queries/                 the actual engine query JSON per cell
```

**`run.log` is where the test's own output lives.** An in-container test's `System.out` reaches neither
`catalina.out` in a useful form nor the Gradle client log, so `_log()` writes to a file. `run.sh` points the test
at `<run-id>/run.log`; if the properties failed to reach the portal JVM it salvages the log from
`$TMPDIR/lpd98298-run.log` and warns, which is itself a signal worth acting on.

`report.md` and `summary.csv` are derived and can be regenerated at any time:

```bash
python3 common/analyze.py --format poc1 \
  --input artifacts/<run-id>/results.jsonl \
  --output-markdown artifacts/<run-id>/report.md \
  --output-csv artifacts/<run-id>/summary.csv
```

Now read `WHAT-IS-MEASURED.md`.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Test reports default configuration in phase 1 | Properties never reached the portal JVM | Source the emitted `catalina-opts.sh` into `setenv.sh` and restart (§3). |
| *attribute had NO EFFECT* | Contributor does not read `preview.swap.map` | You are not on this branch's base, or the deployed portal is stale. Rebuild and redeploy. |
| *drafts not indexed* | `indexAllArticleVersionsEnabled` did not take, or indexing lagged | Check the setting and raise `index.ready.timeout.seconds`. |
| Negative preview deltas | Global warm-up shortened or skipped, so C2 compilation biased the first cell | Restore `WARMUP_ITERATIONS`; see `HOW-IT-WORKS.md` §5. |
| Gradle daemon killed mid-build | `-Xms8g` default | `build.<username>.properties` + `ant update-gradle-properties` (§2.1). |
| Deploy "succeeds" but nothing changes | Wrong Tomcat directory | Pin `app.server.tomcat.version` (§2.1). |
| `Task ... not found` | Gradle invoked from the repo root | The Gradle root is `modules/`; `GRADLE_DIR` already defaults there. |
| Portal exits with status 1 at startup | Sidecar started because the remote engine was unreachable, then failed the version check | Start the engine first, then the portal. |
| "cannot confirm remote mode" | `LIFERAY_HOME` points at the wrong place | The bundle is a **sibling** of the checkout (§2.3). |
| `query_cache_hits` is 0 | First run on a freshly created index | Discard that run (§4.3). |
| No `portal-console.log` | No `catalina.out` under `${LIFERAY_HOME}/tomcat*/logs/` | Set `CATALINA_BASE`, or ignore it — `run.log` carries what matters. |

### Resetting between runs

The test tears down what it created. It deliberately does **not** delete the index or reindex, because index
state is what the cache rule in §4.3 depends on.

To make two runs comparable, keep the corpus and the engine fixed. Rebuilding the portal between them is fine;
reindexing or re-seeding between them is not — then the two runs measure different data.
