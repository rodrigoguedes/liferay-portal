# PoC 2 Runbook — preparing, running and collecting

Operational guide. For *what* the numbers mean, see `WHAT-IS-MEASURED.md`; for what they came out to, see
`FINDINGS.md`.

---

## 1. What this branch already contains

The one product change PoC 2 needs is committed here:

```
modules/apps/journal/journal-service/src/main/java/com/liferay/journal/internal/search/spi/
  model/query/contributor/JournalArticleModelPreFilterContributor.java
```

The contributor now reads the preview swap map from the `SearchContext` attribute
**`search.experiences.preview.swap.map`**, keeping the original `preview.swap.map` as a legacy fallback.

Why that specific name matters: `SearchResultResourceImpl._populateSearchContext` already copies a request-body
`attributes` map onto the `SearchContext`, and `_isAllowedSearchContextAttribute` admits **any** key prefixed
`search.experiences.`. Renaming to that prefix makes the map reachable over HTTP with **no allowlist edit, no
new endpoint, and no thread-local propagation** — one method in one file.

Do not "fix" the name back. If the attribute the script sends is not the attribute the contributor reads, the
attribute is silently **dropped**, the search returns ordinary approved results, and the run reports baseline
latency labelled as preview latency. The script guards against exactly this (§4).

---

## 2. Environment requirements

| Requirement | Why |
| --- | --- |
| **`ant all`, then deploy and restart** | The base branch adds the `com.liferay.portal.kernel.preview` package. A bundle built from an older branch cannot compile `journal-api`, and a stale `portal-kernel.jar` produces confusing unrelated failures. |
| **Portal running and reachable** | Default `http://localhost:8080`. |
| **Search engine in remote mode** | Elasticsearch 8.19 or OpenSearch 2.19. Remote, not the sidecar — see §3. |
| **k6 ≥ 0.4x** | `k6 version`. Install: `brew install k6`, or the Debian/Fedora package, or the static binary. |
| **`curl`, `python3` (stdlib only), `bash`** | No virtualenv, no pip install. |
| **A site with at least one content structure** | The seeder needs somewhere to create articles. Any structure works; the benchmark never reads content. |

### Remote mode, not the sidecar

With `productionModeEnabled=false` the portal launches its **own** Elasticsearch on port **9201**. Benchmarking
against a sidecar measures a JVM that shares CPU and page cache with the portal, and the numbers are not
comparable to anything.

`common/preflight.sh` checks this two ways, and `run.sh` calls both:

- `assert_remote_mode` greps the OSGi config for `productionModeEnabled=B"true"` (note the OSGi typed-config
  form — a plain `="true"` grep gives a false negative).
- `assert_engine_url_matches_portal` compares the `cluster_uuid` the portal talks to against the one at your
  `ENGINE_URL`. Same UUID means you are pointed at the same cluster, even if the ports differ — which is how a
  multi-node cluster on 9200/9201/9202 stops being mistaken for a sidecar.

---

## 3. Preparing the data

### 3.1 Seed a corpus — this PoC creates its own

`discover-pairs.sh` *finds* live/draft pairs; it does not create them. On a fresh database there is nothing to
find, so use the seeder:

```bash
SITE_ID=<your-site-id> \
PAIRS=1000 \
N_VALUES="1 10 100 500 1000" \
PORTAL_USER=test@liferay.com PORTAL_PASSWORD=test123 \
  ./seed-corpus-headless.sh
```

Measured throughput: **1.45 s per pair** — 100 pairs in 2m25s, 900 pairs in ~22 min. Two HTTP requests per
pair, no database access.

- **`PAIRS` is the ceiling on N.** With 100 pairs the sweep silently stops at N=100 (cells for larger N are
  skipped for missing input). Seed 1,000 to reach the Jira's N=1000. N=10000 would be ~4 hours of seeding —
  slow, but not blocked.
- **Running it again *adds* pairs** and rewrites every `swapmap-*.json` from the whole index, so previously
  generated inputs change. Back up `inputs/` first if existing runs must stay reproducible.
- It writes the swap maps itself, reading the **per-version** primary keys back from the index. The API cannot
  give you those: `StructuredContent`'s `id` is `resourcePrimKey`, which every version of an article shares.
  That is precisely why the contributor keys on `Field.UID`.

> **The pairs are not semantically real preview swaps.** No headless endpoint creates a draft *version of an
> existing approved article* — `headless-admin-content`'s draft endpoint calls `addArticle`, i.e. a brand-new
> article in draft state. So the script pairs N approved articles with N independently created drafts.
>
> That is valid for **latency**, because the contributor never checks the relationship: its filter is mechanical
> over UIDs, so the query shape, the filter sizes, and the fact that both sides match real indexed documents are
> all identical. It is **not** valid for any claim about preview behaviour or correctness. State this caveat in
> any report.

### 3.2 Or discover pairs in an existing corpus

If the corpus already has real live/draft pairs (for example one produced by the integration-test PoC, or a
restored dataset):

```bash
ENGINE_URL=http://localhost:9200 ./discover-pairs.sh
```

It groups indexed `JournalArticle` documents by `entryClassPK`, pairs each `status=0 head=true` document with a
`status=2` sibling, extracts the per-version key from the `uid` suffix, and writes `inputs/swapmap-n<N>.json`
plus `inputs/discovery.json`.

Pass `SKIP_DISCOVERY=true` to `run.sh` when `inputs/` is already the way you want it — that is also what keeps
two runs comparable, since re-discovering can produce different pairs.

---

## 4. The guard that must pass before any measurement

`setup()` in the k6 script proves the preview attribute reaches the contributor, and **aborts the cell if it
does not**. Watch for this line:

```
[setup] preview-attribute probe: baseline=1003 probe=1002 (expected 1002)
```

The naive check — compare `totalCount` with and without the map — cannot work: the swap is 1:1, so exchanging
one live version for one draft leaves the count unchanged whether or not the attribute took effect. So the probe
sends a map whose **target id does not exist**. The contributor then excludes the live version and includes
nothing, and the count must drop by exactly one.

If the count does not move, the run aborts with an explanation. Do not work around it — an unrecognized
attribute is dropped in silence, which is worse than a crash because it looks like a successful run.

---

## 5. Running

### 5.1 Smoke run

```bash
ENGINE_URL=http://localhost:9200 \
TARGETS=portal \
N_VALUES="1 100" \
QUERY_VARIANTS="match-all" \
CACHE_MODES="warm" \
RATES="5" \
WARMUP_DURATION=10s \
MEASURE_DURATION=20s \
P95_THRESHOLD_MS=1200 \
PORTAL_USER=test@liferay.com PORTAL_PASSWORD=test123 \
PORTAL_SCOPE=<your-site-id> \
SKIP_DISCOVERY=true \
  ./run.sh
```

Check three things: the probe printed `baseline → baseline-1`; `Cells: 3 run, 0 skipped`; and `hits` is
non-zero and identical across cells. A skipped cell means the corpus lacks pairs for that N — a hole in the
curve, not a warning to ignore.

### 5.2 Full sweep, both directions

Run the sweep **twice, in opposite N order.** This is not optional rigour, it is the difference between a real
result and an artefact — `FINDINGS.md` §2 shows a +45 ms "N=1000 effect" that exists only in the forward
direction.

```bash
COMMON_ARGS='ENGINE_URL=http://localhost:9200 TARGETS=portal QUERY_VARIANTS=match-all
CACHE_MODES=warm RATES=5 WARMUP_DURATION=20s MEASURE_DURATION=60s
P95_THRESHOLD_MS=1200 PORTAL_USER=test@liferay.com PORTAL_PASSWORD=test123
PORTAL_SCOPE=<your-site-id> SKIP_DISCOVERY=true BASELINE_ENABLED=true'

env $COMMON_ARGS RUN_ID=fwd N_VALUES="1 10 100 500 1000" ./run.sh
env $COMMON_ARGS RUN_ID=rev N_VALUES="1000 500 100 10 1" ./run.sh
```

Each is 6 cells (5 N values + 1 baseline) at ~90 s per cell ≈ 10 minutes.

### 5.3 The threshold default is a placeholder

`P95_THRESHOLD_MS` defaults to **500**, chosen before anything had been measured. The HTTP path's fixed floor is
~700 ms, so every cell trips it and the run reports `failed/threshold-missed`. **The data is still complete and
valid** — k6 exits 99 on a threshold miss and `run.sh` deliberately treats that as a result, not an error.

Set it above the measured floor (1200 works) or drop it. Do not read it as an agreed latency budget; there is
no agreed budget yet.

### 5.4 Degradation curve

Vary the **arrival rate**, not the VU count. The script uses k6's `constant-arrival-rate` executor, an open
model: requests are issued on a schedule regardless of whether previous ones finished, so a saturated portal
shows up as rising latency instead of as a quietly reduced request rate (coordinated omission).

```bash
env $COMMON_ARGS RUN_ID=rates N_VALUES="1000" RATES="5 10 20 40" ./run.sh
```

Cross-check `search_pool_rejected` in each cell's `engine-stats-delta.json`. Non-zero means the engine was
saturated and the latencies are queueing time, not query time.

### 5.5 ES 8.19 vs OS 2.19

Run twice with different `ENGINE_URL`, then compare the recorded resources:

```bash
source common/engine-stats.sh
source common/preflight.sh
compare_engine_resources artifacts/<es-run>/engine-baseline.json \
                         artifacts/<os-run>/engine-baseline.json
```

**One caveat to state in any report:** on the `portal` target this comparison includes two *different connector
stacks* (`portal-search-elasticsearch8` vs `portal-search-opensearch2`), so a difference is not necessarily an
engine difference. The confound-free comparison replays a captured engine query with `TARGETS=engine`, which
needs someone to capture a query first — that is why engine mode is opt-in rather than part of the default path.

### 5.6 Full knob list

| Variable | Default | Notes |
| --- | --- | --- |
| `TARGETS` | `portal` | space-separated; `engine` additionally needs captured queries |
| `N_VALUES` | `1 10 100 500 1000` | order matters — see §5.2 |
| `QUERY_VARIANTS` | `match-all keyword faceted` | quote the curve from `match-all` only; see `WHAT-IS-MEASURED.md` |
| `RESULT_SIZES` | `20` | page size |
| `CACHE_MODES` | `warm cold` | `warm` reuses one map; `cold` varies terms per iteration |
| `RATES` | `10` | arrival rate per second (open model) |
| `WARMUP_DURATION` / `WARMUP_RATE` | `30s` / `5` | unmeasured by construction — warm-up samples carry no custom metrics and the analyzer filters them again |
| `MEASURE_DURATION` | `60s` | 60 s at rate 5 ≈ 300 samples/cell |
| `P95_THRESHOLD_MS` | `500` | **placeholder, below the ~700 ms floor** — raise or unset |
| `BASELINE_ENABLED` | `true` | one N=0 cell per (target, variant, size); needed for the Δ column |
| `RESET_SETTLE_SECONDS` | `5` | quiet gap between cells |
| `PORTAL_URL` / `PORTAL_USER` / `PORTAL_PASSWORD` | `localhost:8080`, `test@liferay.com`, `test` | |
| `PORTAL_SCOPE` | *(empty)* | site id; strongly recommended, otherwise the search is instance-wide |
| `PORTAL_ENTRY_CLASS_NAMES` | `com.liferay.journal.model.JournalArticle` | |
| `PORTAL_KEYWORDS` | `lpd98298corpus` | only used by the `keyword`/`faceted` variants |
| `SWAP_MAP_ATTRIBUTE_NAME` | `search.experiences.preview.swap.map` | must match the contributor |
| `ENGINE_URL` / `ENGINE_INDEX` | — / `liferay-*` | |
| `INPUT_DIR` / `ARTIFACTS_DIR` / `RUN_ID` | `./inputs`, `./artifacts`, timestamp | |
| `SKIP_DISCOVERY` | `false` | `true` uses `inputs/` as-is |

---

## 6. The five phases, and where each lands

`run.sh` implements the standard performance-test flow. Per cell:

| Phase | What happens | Artefact |
| --- | --- | --- |
| 1 — preparation | engine identity, version, heap/CPU recorded once per run; inputs resolved | `manifest.json`, `engine-baseline.json` |
| 2 — state reset | engine stats snapshot, then a quiet settle gap | `engine-stats-before.json` |
| 3 — warm-up | `WARMUP_DURATION` at `WARMUP_RATE`, emitting **no** custom metrics | `k6.log` |
| 4 — measurement | `MEASURE_DURATION` at constant arrival rate | `raw-samples.json`, `k6-summary.json` |
| 5 — collection | engine stats snapshot and delta; k6 summary moved into the cell dir | `engine-stats-after.json`, `engine-stats-delta.json` |

Then once per run: the analyzer aggregates every cell into `report.md` + `summary.csv`, and `jira-coverage.md`
records which Desired Outcomes the run actually covered.

---

## 7. Getting the results

```
artifacts/<run-id>/
├── manifest.json                       every knob, engine vendor/version/heap/cpus, doc count
├── engine-baseline.json                engine resources, for the ES-vs-OS comparison
├── report.md                           ← start here
├── summary.csv                         same data, one row per cell, for a spreadsheet
├── jira-coverage.md                    which Desired Outcomes this run covered
└── <target>-n<N>-<variant>-size<S>-<mode>-rate<R>/
    ├── raw-samples.json                every k6 sample — the source of truth
    ├── k6-summary.json                 k6's own aggregate
    ├── k6.log                          console output, including the probe line
    ├── engine-stats-{before,after,delta}.json
    └── summary-*.json                  per-cell context (request bytes, tags)
```

`raw-samples.json` is the source of truth. `report.md` and `summary.csv` are derived and can be regenerated at
any time:

```bash
python3 common/analyze.py --format k6 \
  --input artifacts/<run-id> \
  --output-markdown artifacts/<run-id>/report.md \
  --output-csv artifacts/<run-id>/summary.csv
```

Now read `WHAT-IS-MEASURED.md` to interpret the table.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Probe aborts: *attribute had NO EFFECT* | The contributor on the deployed portal does not read `SWAP_MAP_ATTRIBUTE_NAME` | Confirm this branch is the one built **and deployed**; restart the portal. A rebuilt module that was never deployed is the usual cause. |
| `SKIP … missing input` | No `swapmap-n<N>.json` for that N | Seed more pairs (`PAIRS` ≥ N) or drop that N from `N_VALUES`. |
| Every cell `failed/threshold-missed` | `P95_THRESHOLD_MS=500` placeholder vs a ~700 ms floor | Raise it or unset it (§5.3). Data is valid. |
| `assert_remote_mode` fails | Portal is on its own sidecar engine | Configure remote mode and restart; benchmarking a sidecar is not meaningful. |
| Engine URL rejected as the portal's own node | `cluster_uuid` mismatch | Point `ENGINE_URL` at the cluster the portal actually uses. |
| `hits` is 0 | Query matched nothing — wrong `PORTAL_SCOPE`, empty corpus, or wrong keywords | Fix before trusting a single latency number; see `WHAT-IS-MEASURED.md` §4. |
| 401/403 from headless | Wrong credentials | `PORTAL_USER` / `PORTAL_PASSWORD`; the account needs to read and create content in `SITE_ID`. |
| `search_pool_rejected` > 0 | Engine saturated at that rate | Lower `RATES`; latencies at that cell are queueing time. |
| k6 exits 99 | A threshold was crossed | Not an error. The sweep continues and the data is complete. |

### Resetting between runs

Nothing to undo on the portal side — the benchmark only reads. To remove the seeded corpus, delete the articles
titled `lpd98298seed live <n>` / `lpd98298seed draft <n>` (`DELETE
/o/headless-delivery/v1.0/structured-contents/<id>`), or drop the index and reindex from the Control Panel.

To make two runs comparable, keep the corpus and the engine fixed. Rebuilding the portal between them is fine;
re-seeding or reindexing between them is not, because then the two runs measure different data.
