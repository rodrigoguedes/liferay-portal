# Findings — what PoC 1 measured (LPD-98298)

Environment: single-node **Elasticsearch 8.19.11**, heap 2 GB, 16 CPUs, remote mode, local dev machine. Corpus
~1,000 indexed `JournalArticle` documents plus 1,000 live/draft pairs. Concurrency 1, 300 measured iterations per
cell, 33 cells per run. **Five runs**: one smoke run, one cold-index run discarded per the rule in
`WHAT-IS-MEASURED.md` §6, and three warm-regime runs that the numbers below come from.

Numbers are **directional, not a production threshold** — a single-node dev engine is not a production topology.

---

## 1. The curve

`match-all`, warm, averaged over the three warm-regime runs:

| N | p95 round-trip | preview Δ p95 | engine `took` p95 | liferay+net p95 | request bytes | bytes/pair |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 (baseline) | 6.62 ms | — | 3.00 | 3.62 | 2,031 | — |
| 1 | 6.53 ms | −0.09 | 3.00 | 3.53 | 2,467 | 436 |
| 10 | 6.89 ms | 0.27 | 3.33 | 3.55 | 4,555 | 252 |
| 100 | 7.16 ms | **0.54** | 3.33 | 3.83 | 25,435 | 234 |
| 500 | 12.42 ms | **5.81** | 5.00 | 7.42 | 118,235 | 232 |
| 1000 | 18.67 ms | **12.05** | 6.67 | 12.01 | 234,235 | 232 |

**Shape.** Free up to N≈100 — the delta there is below the noise floor. From N=500 on it is essentially linear at
**~12 µs per pair**. N=100→1000 is a 10× increase in N for a 22× increase in Δ, so the per-pair cost is still
rising slowly through this range.

**Reproducibility.** Δ at N=1000 came out **12.12 / 11.63 / 12.41 ms** across the three runs — **1.3% CV**, the
most stable number in the matrix. That is the figure to quote, and it survives the position control in
`WHAT-IS-MEASURED.md` §5.

**Read the curve from `match-all` only.** The `keyword` and `faceted` cells lose their result set as N grows and
reach zero hits at N=1000 — see `WHAT-IS-MEASURED.md` §4, which also explains why that turned out to be a useful
control rather than only a defect.

---

## 2. Where the cost is — this is the headline

Going from baseline to N=1000, the added 12.05 ms splits as:

| Component | Added | Share |
| --- | ---: | ---: |
| engine `took` (parse + Lucene) | +3.67 ms | 30% |
| Liferay + network (build 2 × `TermsFilter`, serialize, ship) | +8.39 ms | **70%** |

**70% of the added cost is not the engine.** The curve is **serialization/transport-bound, not Lucene-bound.**

That reframes the optimization list. A `Field.UID` term is 55 characters
(`com.liferay.journal.model.JournalArticle_PORTLET_315616`); a numeric per-version key would be 6–7. Same filter
semantics, roughly an eighth of the bytes. What makes it more than a micro-optimization is that it attacks the
70%, not the 30%.

The honest caveat: there is currently **no indexed per-version numeric field** — all versions share
`entryClassPK`, which is why the contributor chose UID in the first place. So this is an **index-time change**
(add a per-version id to the Journal indexer), not a contributor-only swap. Measuring the payoff before
committing to it is cheap: replay the captured query with numeric terms against the same engine.

---

## 3. Every preview clause is emitted twice

From the captured query JSON at N=1000: **4,000 UID occurrences for 1,000 pairs.** The two `terms(uid)` clauses
appear at both `must[2]` and `must[4]` of the same boolean branch:

```
must[0]  entryClassName term
must[1]  ctCollectionId guard
must[2]  preview clause   (NOT terms(1000 from UIDs)) OR terms(1000 to UIDs)
must[3]  ctCollectionId guard      ← again
must[4]  preview clause            ← again
```

**This is pre-existing portal behaviour, not something the rewrite introduced.** The N=0 baseline query shows the
same doubling — `head` twice, `status` twice, the `ctCollectionId` guard twice. The whole model pre-filter
contribution runs twice per search; `_contributePreviewSwapFilter` itself is called once.

Why it matters now: duplicating a single `term` clause wastes ~30 bytes. Duplicating a `terms` clause of N UIDs
wastes **N × 116 bytes** — 117 KB per request at N=1000, ~1.2 MB at N=10000 — and the engine parses and seeks all
of it twice. **Deduplicating halves the request body for free**, and it benefits every search, not just preview.

Root-causing it is a follow-up for the framework team; it is not in this branch's diff. It was found by reading
`artifacts/<run>/queries/`, which is a decent argument for dumping the query rather than trusting that it looks
the way you think.

---

## 4. The 65,536-term limit is not the binding constraint

The Jira's motivation was `index.max_terms_count` = 65,536. Each `terms` clause holds N terms, so that cap is only
reached at N = 65,536 — six times past the largest N the Jira asks about. **Bytes and latency bind first:**

| N | request body | added p95 | status |
| ---: | ---: | ---: | --- |
| 100 | 25 KB | ~0.5 ms | free |
| 1,000 | 234 KB | ~12 ms | measured, comfortable |
| 10,000 | ~2.3 MB (≈1.2 MB deduplicated) | ~120 ms **extrapolated** | not measured |
| 65,536 | ~15 MB | — | ES term cap; long past useful |

The N=10000 row is a **linear extrapolation over a 10× gap**, not a measurement (§6). If a limit has to be drawn
today, the evidence supports drawing it on **request size**, not on term count.

For context from the headless PoC, which measured both hops: the client sends **14.1 B/pair** (numeric ids) while
the portal sends **232 B/pair** to the engine (UID strings, doubled) — a **16.5× expansion inside the portal**. So
`previewId` indirection would save the cheap hop; the numeric terms key saves the expensive one.

---

## 5. Guards that earned their place

**The bogus-target probe.** `_assertPreviewAttributeIsRead` sends a map whose target does not exist, and requires
the count to drop by exactly one. A count comparison with and without the map cannot work — the swap is 1:1. This
guard is the difference between a failed run and a run that reports baseline latency labelled as preview latency.

**The global warm-up.** The first smoke run produced **negative** preview deltas (−0.58 to −2.09 ms) — preview
apparently faster than baseline. Cause: C2 compilation. The baseline cell ran first and absorbed the JIT cost of
the shared search path. A 300-iteration global pre-warm before the matrix fixed it. Per-cell warm-up alone does
not, because the first cell still pays for code every cell shares.

**The drafts-indexed assertion.** Without `indexAllArticleVersionsEnabled` the include-side `terms` filter
matches nothing, the postings union is empty, and the benchmark reports a flat, reassuring, wrong curve.

---

## 6. Limitations to state in any report

- **N=10000 is not measured.** Service-layer seeding stalls above ~4,800 pairs with the portal CPU-bound and the
  engine idle. 5 of the Jira's 6 N values are covered; the 10000 row in §4 is extrapolation.
- **OpenSearch 2.19 has not been run.** The harness takes an `ENGINE_URL` and records engine resources; it is a
  second run, not new code. Half of Desired Outcome 2 is therefore open.
- **The corpus is ~1,000–2,000 documents and not production-like.** Bulk-indexing straight into the engine was
  rejected as unfaithful — no database rows, and thousands of clones of one article destroy exactly the
  term-dictionary cardinality the `terms` filter exists to exercise. sample-sql failed to load. Restoring a
  dataset the team already uses is the untried route. See `RUNBOOK.md` §5.
- **Warm vs cold is unresolved**, not measured — the gap is under 2 ms on 12–19 ms latencies.
- **Concurrency 1.** Realistic for an admin-driven feature, but it is not a saturation test.
- **This PoC does not measure what an HTTP client pays** — it runs in-process. The headless PoC covers that, and
  found ~700 ms of fixed per-request cost there.
- **There is no agreed p95 budget**, so this produces a curve, not a recommended cap. The curve says the
  comfortable zone today ends somewhere between N=1,000 and N=10,000, and that the wall is request size rather
  than the term-count limit.

---

## 7. What we would do next, in order

1. **Deduplicate the model pre-filter contribution** (§3). A free 2× on request size, benefiting every search.
2. **Add an indexed per-version numeric id and switch the terms key** (§2). This is the lever on the 70%.
3. **Run OpenSearch 2.19** on the same corpus to close Desired Outcome 2.
4. **Reach N=10000** on a corpus the team trusts, then replace §4's extrapolated row with a measurement.
5. **Agree a p95 budget**, without which none of this becomes a recommended cap.
