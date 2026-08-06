# Findings — what PoC 2 measured (LPD-98298)

Environment: single-node **Elasticsearch 8.19.11**, heap 2 GB, 16 CPUs, remote mode, local dev machine.
Corpus 2,006 indexed `JournalArticle` documents (1,003 approved + 1,003 drafts), seeded over the headless API.
Arrival rate 5/s, 60 s measurement window, 301 samples per cell. Numbers are **directional, not a production
threshold.**

---

## 1. What is citable from this PoC, and what is not

| Claim | Status |
| --- | --- |
| The preview map reaches the query over HTTP with one renamed attribute | **Proven** (§3) |
| The mechanism works correctly at N=1000 | **Proven** — `hits` invariant, `req bytes` linear (§3) |
| Absolute user-perceived latency of a preview search, ~700–810 ms p95 here | **Citable**, with the environment caveat |
| The portal expands the payload 16.5× on its way to the engine | **Measured** (§4) |
| Any `preview Δ` value from this PoC, including the +45 ms at N=1000 | **Not citable** (§2) |
| The shape of the latency curve as N grows | **Not measurable here** — use the integration-test PoC (§2) |

---

## 2. The delta column measures execution position, not N

This is the most important result in this document, because a single run of this PoC produces exactly the
answer the Jira is looking for — and it is an artefact.

Two runs, identical in every respect except the direction the sweep walks N:

| execution position | forward run | Δ p95 | reverse run | Δ p95 |
| ---: | --- | ---: | --- | ---: |
| 0 | baseline | — | baseline | — |
| 1 | N=1 | −2.72 | N=1000 | +16.76 |
| 2 | N=10 | +20.02 | N=500 | +15.73 |
| 3 | N=100 | +7.24 | N=100 | +22.80 |
| 4 | N=500 | +19.66 | N=10 | +29.97 |
| 5 | **N=1000** | **+45.20** | **N=1** | **+33.13** |

In the forward run, N=1000 produces the largest delta of the whole sweep — **+45.20 ms**, monotone-looking,
exactly the shape a scaling story wants. In the reverse run, **N=1000 produces +16.76 ms and N=1 produces
+33.13 ms.** The largest delta went to whatever ran last, both times. Across all five runs of this PoC that had
a baseline cell, that held **5 out of 5**.

**The self-contained proof is position 3.** N=100 ran third in *both* sweeps — same N, same position, same
corpus, same configuration — and the two runs disagree by **+15.55 ms on Δ p95 and −22.22 ms on Δ p50.** That
difference cannot be N and cannot be position, so it bounds this measurement's resolution at roughly 15–22 ms
with nothing to do with preview at all.

For scale: the in-process PoC puts the real effect at **0.5 ms at N=100** and **12.05 ms at N=1000** (CV 1.3%).
Both are below this measurement's noise.

### Three earlier runs at 100 pairs said the same thing more weakly

| | A — order `1, 10, 100` | B — order `100, 10, 1` | C — order of A, repeated |
| --- | --- | --- | --- |
| Δ p50 at N = 1 / 10 / 100 | +11.20 / +12.95 / +17.32 | +23.12 / +21.58 / +10.04 | +8.68 / +4.75 / +11.33 |
| Δ p95 at N = 1 / 10 / 100 | −3.48 / −10.15 / −1.57 | +18.51 / +16.15 / +13.64 | +1.69 / −2.04 / +15.29 |

Run A alone looks like a clean monotone signal in Δ p50. Run B — reversed — inverts the relationship. Run C —
identical configuration and identical order to A — does not reproduce A. Averaged across the three, Δ p50 is
**flat in N** (+14.33 / +13.09 / +12.90), which is the correct shape.

### What to do about it

1. **Always run the sweep in both directions.** If the shape survives, it is the effect. If it inverts, it was
   order. It costs one extra run.
2. **Repeat one cell at the same position in both directions.** The disagreement is your resolution, for free,
   with no assumptions.
3. **Bracket the baseline** — measure it before *and* after the sweep, or interleave it between cells. `run.sh`
   currently measures one baseline at the start, which is not enough on a path that moves this much. The
   integration-test PoC re-measures its baseline immediately before every N group, and its baselines are flat
   across a ~30-minute run (−0.63 / +0.02 / −0.29 ms), which is what licenses its numbers.

---

## 3. What did hold up

**Option A works over HTTP.** The bogus-target probe, on every run:

```
baseline totalCount : 1003
probe totalCount    : 1002   ← dropped by exactly 1
```

So `POST /o/search/v1.0/search` carries the `attributes` map, the allowlist admits the `search.experiences.`
prefix, and the contributor applies the terms filter — with no product-code change beyond renaming one
attribute. **A client can drive preview search over the public API today.**

**The mechanism is correct at full scale.** At every N from 1 to 1000:

| N | p95 | req bytes | hits |
| ---: | ---: | ---: | ---: |
| 0 (baseline) | 763.98 ms | 43 | 1003 |
| 1 | 761.26 ms | 142 | 1003 |
| 10 | 784.00 ms | 268 | 1003 |
| 100 | 771.22 ms | 1,528 | 1003 |
| 500 | 783.63 ms | 7,128 | 1003 |
| 1000 | 809.17 ms | 14,128 | 1003 |

`req bytes` scale linearly, and `hits` stays at 1,003 even at N=1000 where **all** approved documents are
swapped for drafts. The invariant holds exactly as designed.

**The fixed floor is ~700 ms.** Against 6–19 ms for the same cells in-process — roughly 40×. It comes from
per-request work unrelated to preview: Basic auth revalidating credentials on every call, the Vulcan layer,
`Page<SearchResult>` serialization. That is a real number about the API path and worth citing as such; it is
simply not a number about preview.

**Seeding is not a blocker here.** 1.45 s/pair over HTTP — 1,000 pairs in ~22 min. N=10000 would be ~4 hours,
slow but achievable. (The integration-test PoC, seeding in-container through the service layer, stalls above
~4,800 pairs, so N=10000 is genuinely blocked there.)

---

## 4. The portal expands the payload 16.5×

Measured on both hops at N=1000:

| hop | encoding | bytes/pair | at N=1000 | at N=10000 |
| --- | --- | ---: | ---: | ---: |
| client → portal | numeric per-version ids, `{"36337":36348}` | **14.1 B** | 14 KB | ~141 KB |
| portal → engine | UID strings, ×2 clauses, emitted twice | **232 B** | 234 KB | ~2.3 MB |

This corrects an estimate the project carried for a while — that the map is "paid twice, ~1.2 MB on each hop".
It is not. The client hop is trivial at every N in scope. Only the portal→engine hop is large, and the expansion
happens inside the portal.

Two consequences for the framework team:

1. **`previewId` indirection saves the cheap hop, not the expensive one.** It removes the client's 14 KB and
   leaves the portal's 234 KB untouched. Still worth doing for API hygiene; it is not the payload fix.
2. **The numeric-terms-key change is the payload fix, and the client already proves it works.** The client
   addresses versions with 6–7-digit numbers today; expanding them into 55-character UID strings is the portal's
   own choice, forced by there being no indexed per-version numeric field. Adding one would make it a
   contributor-level change instead of a redesign.

### And the clauses are emitted twice

The captured engine query at N=1000 contains **4,000 UID occurrences for 1,000 pairs** — the two `terms(uid)`
clauses appear at both `must[2]` and `must[4]` of the same boolean branch.

This is **pre-existing portal behaviour, not something the rewrite introduced**: the N=0 baseline query doubles
`head`, `status` and the `ctCollectionId` guard too. The whole model pre-filter contribution runs twice per
search. It is harmless while the clauses are single `term`s and costs **N × 116 bytes** the moment one holds N
UIDs — 117 KB per request at N=1000. Deduplicating halves the request body for free, and it benefits every
search, not just preview.

---

## 5. Where the cost actually is (from the in-process PoC)

Included here because it is the answer to the Jira's question, and this PoC cannot provide it. In-process, at
N=1000, of the 12.05 ms the preview adds:

| Component | Added | Share |
| --- | ---: | ---: |
| engine `took` (parse + Lucene) | +3.67 ms | 30% |
| Liferay + network (build 2 × `TermsFilter`, serialize, ship) | +8.39 ms | **70%** |

**The curve is serialization/transport-bound, not Lucene-bound.** Combined with §4, that makes the numeric
terms key the headline optimization rather than an optional lever.

And the term cap turns out not to be the binding constraint at all. Each `terms` clause holds N terms, so
`index.max_terms_count` = 65,536 is only reached at N = 65,536 — six times past the largest N the Jira asks
about. **Bytes and latency bind first.** If a limit has to be drawn today, draw it on request size.

---

## 6. Limitations to state in any report

- **The seeded pairs are not semantically real preview swaps.** No headless endpoint creates a draft *version of
  an existing article*, so the seeder pairs N approved articles with N independent drafts. Equivalent for
  latency — the contributor's filter is mechanical over UIDs — but not evidence about preview behaviour or
  correctness.
- **OpenSearch 2.19 has not been run.** The harness takes an `ENGINE_URL`; it is a second run, not new code. Half
  of the Jira's Desired Outcome 2 is therefore open.
- **N=10000 has not been measured** on either PoC. Any figure for it is extrapolation.
- **The corpus is ~2,000 documents and not production-like.** Bulk-seeding straight to the engine was rejected
  as unfaithful — it creates no database rows, and thousands of clones of one article destroy exactly the
  term-dictionary cardinality the `terms` filter exists to exercise.
- **Warm vs cold is unresolved**, not measured — see `WHAT-IS-MEASURED.md` §6.
- **Single-node dev engine, arrival rate 5/s.** Preview is admin-driven, so low concurrency is realistic, but a
  cap derived from this topology is directional only.
- **There is no agreed p95 budget**, so this produces a curve, not a recommended cap. `P95_THRESHOLD_MS` is a
  placeholder.
