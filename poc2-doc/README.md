# PoC 2 — preview-search benchmark over the headless API (LPD-98298)

Everything in this directory is what you need to benchmark the preview-search rewrite **through the portal's
headless Search API**, using [k6](https://k6.io) as the load generator. It is self-contained: it creates its
own corpus, discovers its own inputs, runs the sweep, and analyses the output. Nothing outside this directory
is required, and nothing from the integration-test PoC is required.

The one thing it depends on is **already committed on this branch**: `JournalArticleModelPreFilterContributor`
reads the preview swap map from a headless-reachable `SearchContext` attribute. See `RUNBOOK.md` §1.

## Read these in order

| Document | What it answers |
| --- | --- |
| **`RUNBOOK.md`** | How do I prepare the environment and run this? |
| **`WHAT-IS-MEASURED.md`** | What exactly is being measured, and how do I read the numbers? |
| **`FINDINGS.md`** | What did it measure when we ran it, and what can I quote? |

If you only have five minutes, read `FINDINGS.md` §1 — it says which numbers are citable and which are not.

## Files

```
poc2-doc/
├── README.md                       this file
├── RUNBOOK.md                      prepare → run → collect
├── WHAT-IS-MEASURED.md             the measurement model and how to read results
├── FINDINGS.md                     measured results, with their caveats
├── run.sh                          the orchestrator: 5-phase sweep over the matrix
├── preview-search-benchmark.js     the k6 script (one cell per invocation)
├── seed-corpus-headless.sh         creates the corpus through the headless API
├── discover-pairs.sh               reconstructs live/draft ID pairs from the index
└── common/
    ├── analyze.py                  JSONL/k6 samples → report.md + summary.csv
    ├── engine-stats.sh             engine version, resources, cache/thread-pool deltas
    └── preflight.sh                remote-mode, engine-identity and coverage guards
```

`inputs/` and `artifacts/` are created at run time and are gitignored — the seeder writes swap maps into the
first, `run.sh` writes results into the second.

`common/analyze.py` also accepts `--format poc1`, which is for the integration-test PoC's JSONL. It is kept
intact so both PoCs stay on one result schema; you will only ever use `--format k6` here, and `run.sh` calls it
for you.

## The 30-second version

```bash
# 1. portal up, engine reachable, this branch built and deployed
# 2. create a corpus (1000 pairs ≈ 22 min over HTTP)
SITE_ID=<your-site-id> PAIRS=1000 N_VALUES="1 10 100 500 1000" \
PORTAL_USER=test@liferay.com PORTAL_PASSWORD=test123 \
  ./seed-corpus-headless.sh

# 3. sweep
ENGINE_URL=http://localhost:9200 \
TARGETS=portal N_VALUES="1 10 100 500 1000" QUERY_VARIANTS="match-all" \
CACHE_MODES="warm" RATES="5" MEASURE_DURATION=60s P95_THRESHOLD_MS=1200 \
PORTAL_USER=test@liferay.com PORTAL_PASSWORD=test123 PORTAL_SCOPE=<your-site-id> \
SKIP_DISCOVERY=true \
  ./run.sh

# 4. read artifacts/<run-id>/report.md
```

Read `RUNBOOK.md` before doing this for real — there are two guards that will abort the run for good reasons,
and one threshold default that is a placeholder rather than a budget.

## What this PoC is good for, and what it is not

**Good for:** absolute, user-perceived latency of a preview search over HTTP. Concurrency behaviour under an
open load model. Proving end to end that a client can drive preview search through the public API.

**Not good for:** the shape of the latency curve as N grows. Measured, the HTTP path carries roughly 700 ms of
fixed per-request cost and 15–22 ms of run-to-run dispersion, against a preview effect of 0.5–12 ms over the
N range the Jira asks about. It cannot resolve that. `FINDINGS.md` §2 shows exactly how a single run of this
PoC produces a convincing-looking N curve that a control run destroys.

Use the integration-test PoC for the curve. Use this one for what a client actually waits for.
