# What is measured, and how to read it

---

## 1. The thing under test

`JournalArticleModelPreFilterContributor` normally restricts a `JournalArticle` search to
`status=approved AND head`. When a **preview swap map** is present it replaces that with:

```
(status=approved AND head AND NOT terms(fromUIDs))  OR  terms(toUIDs)
```

Two `TermsFilter` clauses on `Field.UID`, each holding **N** terms, where N is the number of articles being
previewed. The swap map is `Map<String, Map<Long, Long>>` keyed by `JournalArticle.class.getName()`; each entry
maps the **live version's** per-version primary key to the **draft version's**.

It has to be the per-version key, not `resourcePrimKey` — every version of an article shares that — which is
why the terms are UID strings of the form
`com.liferay.journal.model.JournalArticle_PORTLET_<perVersionPK>`, 55 characters each.

**The question this PoC exists to answer:** how does search latency behave as N grows, and where does the cost
come from? Elasticsearch caps a terms query at 65,536 terms (`index.max_terms_count`), and the team wants to
know where to draw a sensible limit well before that.

---

## 2. The three request paths, and why they are never averaged

A preview search at size N pays cost in five places, and they scale differently:

| # | Cost | Scales with N | Visible in |
| --- | --- | --- | --- |
| 0 | HTTP transfer of the swap map, **client → portal** | O(N) bytes | wall-clock only |
| 1 | Building 2 × `TermsFilter`, then serializing to engine JSON | **O(N)**, byte-heavy | wall-clock only |
| 2 | HTTP transfer of the request body, **portal → engine** | O(N) bytes | wall-clock only |
| 3 | Engine-side parsing of 2N terms | O(N) | engine `took` |
| 4 | Lucene: term-dictionary seeks + postings union per segment | O(N) seeks, plus corpus-dependent | engine `took` |

`run.sh` supports two targets, which pay different subsets:

| `TARGETS` | Costs paid | What it tells you |
| --- | --- | --- |
| `portal` (default) | **0, 1, 2, 3, 4** | what a client actually waits for |
| `engine` (opt-in) | 2, 3, 4 | the engine-side floor, and the confound-free ES-vs-OS comparison |

The integration-test PoC measures the middle path (1, 2, 3, 4) in-process. Comparing the three localizes the
cost. Never average them — they are answers to different questions.

**Engine mode is opt-in on purpose.** Replaying a real query means someone captured one first. Building the
query inside the k6 script instead would measure *the script's approximation* of what the contributor produces,
not the contributor's actual output — which is the one thing worth never doing in this benchmark.

### Measured payload on both hops

At N=1000, both hops were measured. The result corrects an estimate this project carried for a while:

| hop | encoding | bytes/pair | at N=1000 | at N=10000 |
| --- | --- | ---: | ---: | ---: |
| client → portal | numeric per-version ids, `{"36337":36348}` | **14.1 B** | 14 KB | ~141 KB |
| portal → engine | UID strings, ×2 clauses, emitted twice | **232 B** | 234 KB | ~2.3 MB |

**A 16.5× expansion, and it happens inside the portal.** The client hop is trivial at every N in scope; only
the portal→engine hop is large. See `FINDINGS.md` §4 for what follows from that.

---

## 3. The measurement protocol

**Open load model.** k6 runs `constant-arrival-rate`: requests are issued on a fixed schedule whether or not
previous ones have returned. A closed model (fixed VUs, each waiting for its response) hides saturation —
latency stops rising because the offered load quietly drops. That is coordinated omission, and it is the most
common way a load test reports reassuring numbers about an overloaded system.

**Warm-up is unmeasured by construction, twice over.** The warm-up scenario emits no custom metrics at all, and
the analyzer additionally filters any sample whose `phase` tag is not `measure`. A script change cannot
accidentally let warm-up latency into a percentile.

**One row per iteration, never pre-aggregated.** Every sample keeps every dimension — target, engine vendor and
version, query variant, result size, N, cache mode, phase, arrival rate. Percentiles are computed in analysis,
so the data can be re-cut later. This is the same result schema the integration-test PoC writes, so curves from
the two can be overlaid.

**A baseline cell per (target, variant, size).** N=0, no swap map at all — 43 request bytes against 142 at
N=1. The Δ columns are computed against it. Read `FINDINGS.md` §2 before trusting a Δ from this PoC.

**Engine stats bracket every cell.** `_nodes/stats` before and after, with deltas for `query_cache` hits/misses,
`request_cache`, GC, and the search thread pool's `queue`/`rejected`.

---

## 4. Reading `report.md`

The table has one row per cell. Columns, in the order they matter:

### `hits` — read this first

**A validity gate, not decoration.** The swap map removes N approved documents and adds N drafts, so the result
count **cannot move**. If `hits` changes across N, the corpus or the map is wrong and every latency number in
that row is measuring something else. If `hits` is 0, the query matched nothing and the row is meaningless.

Measured with 1,000 seeded pairs: **1,003 at every N including N=1000**, where all 1,003 approved documents are
swapped for drafts. That is the invariant holding at full scale.

This column was silently broken for a while — the analyzer never read the `preview_hits_total` metric, so every
cell reported 0 while k6's own summary held the right number. Worth remembering as a pattern: a recorded-but-
unrendered field is not observability, it is a field that will be wrong without anyone noticing.

### `p50`, `p95`, `p99` — round-trip latency

Wall-clock, client side. For `TARGETS=portal` this includes everything: auth, Vulcan, contributor,
serialization, the engine, and the response coming back.

### `engine took p95` — `n/a` for `portal`, by design

The headless response carries no engine `took`. Engine-side attribution comes from the integration-test PoC,
which reads it in-process, or from `TARGETS=engine`.

### `liferay+net p95` — p95 round-trip minus p95 engine `took`

Everything that is *not* engine query execution: building the two `TermsFilter` objects, serializing them,
the HTTP hop, and translating the response back. Called *liferay+net* because from the engine's point of view
Liferay is the client.

**If this grows with N faster than `took` does, the curve is serialization/transport-bound rather than
Lucene-bound** — which promotes the numeric-terms-key idea from an optional lever to the headline optimization.
Measured in-process at N=1000: engine `took` accounts for +3.67 ms of the added cost and Liferay+network for
+8.39 ms, i.e. **70% outside the engine.** Empty for the `portal` target, since `took` is unavailable there.

### `preview Δ p95` — this cell minus the baseline cell

The cost the rewrite adds, which is the number the framework team actually needs. **On the `portal` target this
column is not usable** — see `FINDINGS.md` §2. It is meaningful on the `integration` and `engine` targets.

### `req bytes` — should scale ~linearly with N, and does

142 at N=1 → 14,128 at N=1000 on the client hop. Use it as a second sanity check: if the bytes do not scale,
the map is not reaching the portal regardless of what the latency says.

### Growth-shape table

p95 growth × against N growth × per step. Well below N's factor means sublinear. At or above means the cost
tracks N, and that is where a cap argument has teeth. On the `portal` target the growth comes out flat
(≈1.0× against 10× in N) because fixed cost dominates — that is a property of the measurement layer, not of
the feature.

---

## 5. Which query variant carries the curve: `match-all`

`QUERY_VARIANTS` offers `match-all`, `keyword` and `faceted`. Only `match-all` gives a clean curve, and the
`hits` column is what reveals why.

In the seeded corpus the approved article and its paired draft deliberately carry **different content tokens**,
so that a functional test can prove which version came back. Consequence: a keyword search matches only the
approved side, so swapping N pairs *removes N results*. At N=1000 the result set is **empty**.

| query variant | Δ p95 at N=1000 (3 in-process runs) | `hits` from N=1 → N=1000 |
| --- | ---: | --- |
| `match-all` | **12.32 / 12.11 / 12.00 ms** | 1000 → 1000 |
| `keyword` | 9.59 / 6.68 / 9.66 ms | 999 → **0** |
| `faceted` | 7.70 / 9.13 / 9.55 ms | 999 → **0** |

Two things follow, and the second is more interesting than the first:

1. **Quote `match-all`.** Constant result set, and a filter cost at N=1000 reproducible to 1.3% CV.
2. **The collapse is a free control.** The empty-result cells grow 7–10 ms while the full-result cells grow
   12 ms — so the growth is **filter cost, not collection or scoring cost.** That is exactly the claim the Jira
   needs, now evidenced from both sides.

Keep `keyword`/`faceted` as the control. Do not present them at high N as the curve; they under-report, because
they stop collecting.

---

## 6. Warm vs cold

`warm` reuses one preview map for every iteration (within-session amortization). `cold` varies the terms per
iteration (the per-preview floor).

**This is currently unresolved rather than measured.** The cold−warm gap came out under 2 ms on 12–19 ms
latencies, which is inside the run-to-run band. Corpus size gates the question but not the way one might
assume: two identical runs on the same ~2,000-document corpus reported `query_cache_hits` of 0 and 16,362 —
the cache is bimodal, and the zero belongs to the first run on a freshly created index.

Two operational rules follow:

- **Discard the first run after recreating the index.** It is a cold-index outlier and, in our data, produced
  the only non-monotonic curve.
- **Cross-check `query_cache_hits`** in each cell's `engine-stats-delta.json` before concluding anything about
  caching.

---

## 7. How much difference is real

Measured dispersion, so you know what counts as a signal:

| layer | dispersion | implication |
| --- | --- | --- |
| in-process (integration PoC), 3 runs | CV **3.1% min / 7.0% median / 13.2% max** | a p95 margin under ~15% is not distinguishable in a single run |
| in-process, the N=1000 headline | CV **1.3%** | the most stable number in the matrix |
| **this PoC, `portal` target** | **15–22 ms** on Δ, plus a ~700 ms fixed floor | cannot resolve the N range the Jira asks about |

The last row is not an estimate. It comes from a position-controlled comparison inside the data itself — see
`FINDINGS.md` §2.
