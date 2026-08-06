# What is measured, and how to read it

---

## 1. The thing under test

`JournalArticleModelPreFilterContributor` normally restricts a `JournalArticle` search to
`status=approved AND head`. When a **preview swap map** is present it replaces that with:

```
(status=approved AND head AND NOT terms(fromUIDs))  OR  terms(toUIDs)
```

Two `TermsFilter` clauses on `Field.UID`, each holding **N** terms, where N is the number of articles being
previewed. The map is `Map<String, Map<Long, Long>>` keyed by `JournalArticle.class.getName()`, mapping each live
version's **per-version** primary key to its draft version's.

It has to be the per-version key — every version of an article shares `resourcePrimKey` — which is why the terms
are UID strings of the form `com.liferay.journal.model.JournalArticle_PORTLET_<perVersionPK>`, 55 characters
each.

**The question:** how does latency behave as N grows, and where does the cost come from? Elasticsearch caps a
terms query at 65,536 terms (`index.max_terms_count`), and the team wants a sensible limit well before that.

---

## 2. Cost anatomy, and what this PoC sees

A preview search at size N pays cost in five places:

| # | Cost | Scales with N | Visible in | Seen by PoC 1 |
| --- | --- | --- | --- | :---: |
| 0 | HTTP transfer of the map, client → portal | O(N) bytes | wall-clock | **no** — in-process |
| 1 | Building 2 × `TermsFilter`, serializing to engine JSON | **O(N)**, byte-heavy | wall-clock | yes |
| 2 | HTTP transfer of the request body, portal → engine | O(N) bytes | wall-clock | yes |
| 3 | Engine-side parsing of 2N terms | O(N) | engine `took` | yes |
| 4 | Lucene: term-dictionary seeks + postings union | O(N) seeks, corpus-dependent | engine `took` | yes |

PoC 1 measures **1 + 2 + 3 + 4**, and — crucially — can *separate* them, because it records both wall-clock and
the engine's own `took` per iteration. Cost 0 belongs to the headless PoC.

**`took` alone hides costs 1 and 2, and wall-clock alone cannot separate them.** Recording both per iteration is
the whole reason this PoC produces an attribution rather than just a number.

---

## 3. The measurement protocol

**Sequential at concurrency 1 by default.** One iteration after another, so a slow iteration never overlaps the
next. That gives a clean latency series rather than a queueing measurement. Preview is admin-driven, so low
concurrency is also the realistic case.

**Warm-up is unmeasured, at two levels.** A global 300-iteration pre-warm across four search shapes runs before
the matrix (this removes a real JIT-compilation bias — `HOW-IT-WORKS.md` §5), and each cell additionally warms
its own query shape. Warm-up rows carry `phase=warmup`, and the analyzer filters on `phase=measure`, so a code
change cannot leak warm-up latency into a percentile.

**One row per iteration, never pre-aggregated.** Percentiles are computed in analysis, so the data can be re-cut
later. The schema is shared with the headless PoC, so curves from the two can be overlaid.

**A baseline cell before every N group, per query variant.** Not one baseline at the start — see §5, which is
where that choice earns its keep.

**Engine stats bracket the run**, with deltas for `query_cache` hits/misses, `request_cache`, GC, and the search
thread pool's `queue`/`rejected`.

---

## 4. Reading `report.md`

One row per cell. Columns in the order they matter:

### `hits` — read this first

**A validity gate, not decoration.** The swap removes N approved documents and adds N drafts, so the result count
**cannot move**. If it moves, the corpus or the map is wrong and the latencies measure something else. If it is
0, the query matched nothing.

It also decides **which query variant carries the curve**, and this is the single most useful thing on the page:

| query variant (warm, 3 runs) | p95 N=1 → N=1000 | Δ | `hits` |
| --- | --- | ---: | --- |
| `match-all` | 6.60→18.92 / 5.91→18.02 / 7.08→19.08 | **12.32 / 12.11 / 12.00 ms** | 1000 → 1000 |
| `keyword` | 13.44→23.03 / 11.30→17.98 / 12.93→22.59 | 9.59 / 6.68 / 9.66 ms | 999 → **0** |
| `faceted` | 14.63→22.33 / 12.44→21.56 / 13.64→23.19 | 7.70 / 9.13 / 9.55 ms | 999 → **0** |

Why the collapse: the seeded approved article and its draft deliberately carry **different content tokens**, so
a functional test can prove which version came back. A keyword search therefore matches only the approved side,
and swapping N pairs removes N results. At N=1000 the result set is **empty**.

Two conclusions, and the second is more valuable than the first:

1. **Quote `match-all`.** Constant result set, and a filter cost at N=1000 reproducible to **1.3% CV** — the most
   stable number in the matrix.
2. **The collapse is a free control.** The empty-result cells grow 7–10 ms while the full-result cells grow
   12 ms, which **proves the growth is filter cost, not collection or scoring cost.** That is exactly the claim
   the Jira needs, now evidenced from both sides.

Keep `keyword`/`faceted` as the control. Do not present them at high N as the curve; they under-report, because
they stop collecting.

### `p50` / `p95` / `p99` — round-trip

Wall-clock around the `Searcher` call, in-process. Costs 1–4.

### `engine took p95`

The engine's own `took`, from `SearchResponse.getSearchTimeValue()`. Costs 3–4 only.

### `liferay+net p95` — round-trip p95 minus engine `took` p95

Everything that is *not* engine query execution: building the two `TermsFilter` objects, serializing them to
JSON, the hop to the engine, and translating the response back. Named *liferay+net* because from the engine's
point of view Liferay is the client.

**If this grows faster than `took` does, the curve is serialization/transport-bound rather than Lucene-bound.**
It does — see `FINDINGS.md` §2. That single comparison is what promotes the numeric-terms-key idea from an
optional lever to the headline optimization.

### `preview Δ p95` — this cell minus its baseline

The cost the rewrite adds, which is the number the framework team needs. Trustworthy here, because of §5.

### `req bytes`

The serialized engine query size. Should scale ~linearly with N, and does: 2,031 at baseline → 234,235 at
N=1000. Use it as a second sanity check, and see `FINDINGS.md` §3 for the defect it exposed.

### Growth-shape table

p95 growth × against N growth × per step. Well below N's factor means sublinear; at or above means the cost
tracks N, and that is where a cap argument has teeth.

---

## 5. Why the deltas here are trustworthy — and why the headless PoC's are not

The test re-measures the baseline **immediately before every N group**, for every query variant: 15 baseline
measurements across a 33-cell run, not one at the start.

That is not ceremony. A benchmark that sweeps N upward and compares against a single baseline taken at the
beginning **cannot distinguish a growing effect from a slow drift** — both look like "latency rises with N".

The headless PoC demonstrated exactly that failure, on the same feature and the same engine. Sweeping N upward
made N=1000 the largest delta of the sweep, +45.20 ms, monotone and convincing. Sweeping *downward* gave N=1000
+16.76 ms and handed +33.13 ms to N=1. The largest delta followed whatever ran last.

PoC 1 was then checked rather than assumed:

- Its match-all baseline p95 across a ~30-minute run moved **−0.63 / +0.02 / −0.29 ms** in three runs. Flat.
- Recomputing every delta against the **adjacent** baseline instead of the pooled one changes N=1000 by at most
  **0.3 ms** (12.41 / 11.72 / 12.61 versus 12.12 / 11.63 / 12.41).

That is what licenses the 12 ms figure. The drift lives in the layer the headless PoC adds — auth, Vulcan,
response serialization — not in the in-process path.

**Two cheap controls worth building into any benchmark:** run the sweep in both directions, and repeat one cell at
the same position in both directions. If the shape survives, it is the effect. If it inverts, it was order. The
disagreement between the two repeats is your resolution, measured rather than assumed.

---

## 6. Warm vs cold, and the caching rule

`warm` reuses one preview map for every iteration (within-session amortization). `cold` varies the terms per
iteration (the per-preview floor).

**This is unresolved rather than measured.** The cold−warm gap came out under 2 ms on 12–19 ms latencies, inside
the run-to-run band.

Corpus size gates the question, but not the way one might assume — the claim "a small corpus prevents caching"
turned out to be wrong. Four runs on the same ~2,000-document corpus reported `query_cache_hits` of **0 / 16,362
/ 16,358 / 16,361**. The cache is bimodal, and the zero belongs to the first run on a freshly created index.

Two operational rules:

- **Discard the first run after recreating the index.** It is a cold-index outlier, and in our data it produced
  the only non-monotonic curve.
- **Cross-check `query_cache_hits`** in `engine-stats-delta.json` before concluding anything about caching.

---

## 7. How much difference is real

| Measurement | Dispersion | Implication |
| --- | --- | --- |
| Three warm-regime runs, 15 cells | CV **3.1% min / 7.0% median / 13.2% max** | a p95 margin under ~15% is not distinguishable in a single run |
| The N=1000 match-all headline | CV **1.3%** | citable; it is the exception because it was repeated |
| The headless PoC, `portal` target | **15–22 ms** on Δ, plus a ~700 ms fixed floor | cannot resolve this N range at all |

An earlier version of this project quoted "CV 3.5–9.6%" from two samples. With three runs the real spread is the
first row above. Two samples were not enough, which is worth remembering before quoting a dispersion figure.
