#!/usr/bin/env python3
"""LPD-98298 — analyzer for the preview search benchmark.

Reads the raw per-iteration JSONL written by the preview:benchmark module
(com.liferay.portal.search.preview.benchmark) and produces the curves the Jira
asks for. Deliberately stdlib-only (no pandas/numpy) so it runs on any
benchmark host without a virtualenv.

Usage:

    analyze.py --input results.jsonl \\
        [--output-markdown report.md] [--output-csv summary.csv]

Percentiles are computed HERE, never in the harness. Pre-aggregating in the
harness would destroy the ability to re-cut the data (per-N, per-concurrency,
warm vs cold) without re-running a benchmark that takes hours.
"""

import argparse
import csv
import json
import math
import statistics
import sys
from collections import defaultdict

# Dimensions that identify a cell. Every row carries all of them, so grouping is
# a pure function of the data and no context lives in filenames.
CELL_KEYS = (
    "target",
    "engine_vendor",
    "engine_version",
    "query_type",
    "result_size",
    "n_preview_items",
    "concurrency",
    "cache_mode",
    "terms_key_type",
)


def percentile(values, p):
    """Nearest-rank percentile on a sorted list.

    Matches the convention used by Liferay's existing Statistics class
    (ceil(p/100 * n) - 1) so numbers from this analyzer and from that console
    summary do not disagree by an off-by-one.
    """
    if not values:
        return None

    index = math.ceil((p / 100.0) * len(values)) - 1

    return values[max(0, min(index, len(values) - 1))]


def load_rows(path):
    rows = []
    skipped = 0

    with open(path) as f:
        for line in f:
            line = line.strip()

            if not line:
                continue

            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                # A partial last line is expected if a run was interrupted
                # mid-flush; count it rather than dying.
                skipped += 1

    if skipped:
        print(
            "note: skipped %d malformed JSONL line(s)" % skipped,
            file=sys.stderr,
        )

    return rows


def summarize(rows):
    """Group by cell, compute percentiles over the measurement window only."""
    buckets = defaultdict(
        lambda: {"roundtrip": [], "took": [], "req_bytes": [], "hits": []}
    )

    for row in rows:
        if row.get("phase") != "measure":
            continue

        key = tuple(str(row.get(k, "unknown")) for k in CELL_KEYS)
        bucket = buckets[key]

        value = row.get("roundtrip_ms")

        if isinstance(value, (int, float)):
            bucket["roundtrip"].append(float(value))

        req = row.get("request_bytes")

        if isinstance(req, (int, float)) and req > 0:
            bucket["req_bytes"].append(float(req))

        hits = row.get("hits_total")

        if isinstance(hits, (int, float)):
            bucket["hits"].append(float(hits))

        took = row.get("engine_took_ms")

        # -1 is the sentinel the harness writes when the engine reported no
        # took.
        if isinstance(took, (int, float)) and took >= 0:
            bucket["took"].append(float(took))

    summaries = []

    for key, bucket in buckets.items():
        roundtrip = sorted(bucket["roundtrip"])
        took = sorted(bucket["took"])

        if not roundtrip:
            continue

        row = dict(zip(CELL_KEYS, key))
        row["samples"] = len(roundtrip)
        row["roundtrip_p50"] = percentile(roundtrip, 50)
        row["roundtrip_p95"] = percentile(roundtrip, 95)
        row["roundtrip_p99"] = percentile(roundtrip, 99)
        row["roundtrip_mean"] = statistics.fmean(roundtrip)
        row["roundtrip_stdev"] = (
            statistics.stdev(roundtrip) if len(roundtrip) > 1 else 0.0
        )
        row["took_p50"] = percentile(took, 50) if took else None
        row["took_p95"] = percentile(took, 95) if took else None

        # The headline attribution: round-trip minus engine took is the client
        # plus network cost. This is where terms serialization shows up, and it
        # is exactly what a took-only measurement hides.
        if row["roundtrip_p95"] is not None and row["took_p95"] is not None:
            row["client_overhead_p95"] = row["roundtrip_p95"] - row["took_p95"]
        else:
            row["client_overhead_p95"] = None

        row["request_bytes"] = (
            int(statistics.fmean(bucket["req_bytes"])) if bucket["req_bytes"] else 0
        )
        row["hits_total"] = (
            int(statistics.fmean(bucket["hits"])) if bucket["hits"] else 0
        )

        summaries.append(row)

    def sort_key(r):
        try:
            n = int(r["n_preview_items"])
        except (TypeError, ValueError):
            n = -1

        try:
            concurrency = int(r["concurrency"])
        except (TypeError, ValueError):
            concurrency = 0

        try:
            result_size = int(r["result_size"])
        except (TypeError, ValueError):
            result_size = 0

        return (
            r["target"],
            r["engine_vendor"],
            r["query_type"],
            result_size,
            concurrency,
            r["cache_mode"],
            n,
        )

    return sorted(summaries, key=sort_key)


def preview_delta(summaries):
    """Preview latency minus baseline latency, at the same query and size.

    The delta -- not the absolute number -- is the cost the preview rewrite
    adds, and it is the figure the other team needs.
    """
    baselines = {}

    for row in summaries:
        if row["cache_mode"] == "baseline":
            baselines[
                (
                    row["target"],
                    row["engine_vendor"],
                    row["query_type"],
                    str(row["result_size"]),
                    str(row["concurrency"]),
                )
            ] = row["roundtrip_p95"]

    for row in summaries:
        base = baselines.get(
            (
                row["target"],
                row["engine_vendor"],
                row["query_type"],
                str(row["result_size"]),
                str(row["concurrency"]),
            )
        )

        if base is not None and row["cache_mode"] != "baseline":
            row["preview_delta_p95"] = row["roundtrip_p95"] - base
        else:
            row["preview_delta_p95"] = None

    return summaries


def fmt(value, digits=2):
    if value is None:
        return "n/a"

    if isinstance(value, float):
        return ("%%.%df" % digits) % value

    return str(value)


def fmt_duration(ms):
    """Human-readable duration with the unit per row (ms/s/min)."""
    if ms is None:
        return "n/a"

    magnitude = abs(ms)

    if magnitude >= 60000:
        return "%.1f min" % (ms / 60000.0)

    if magnitude >= 1000:
        return "%.2f s" % (ms / 1000.0)

    return "%.2f ms" % ms


def fmt_bytes(value):
    """Human-readable size with the unit per row (decimal KB/MB)."""
    if not value:
        return "0 B"

    if value >= 1000000:
        return "%.2f MB" % (value / 1000000.0)

    if value >= 1000:
        kb = value / 1000.0

        return ("%.1f KB" if kb < 10 else "%.0f KB") % kb

    return "%d B" % value


def render_markdown(summaries, source):
    lines = []
    lines.append("# LPD-98298 — preview search benchmark report")
    lines.append("")
    lines.append("Source: `%s`" % source)
    lines.append("")

    if not summaries:
        lines.append("**No measurement samples found.**")
        lines.append("")
        lines.append(
            "Most likely cause: no cell produced `phase=measure` rows — the "
            "run was interrupted before the first measurement window, or the "
            "input file is not a results.jsonl written by preview:benchmark."
        )
        return "\n".join(lines) + "\n"

    lines.append("## Latency vs N")
    lines.append("")
    lines.append(
        "| target | engine | query | size | conc | mode | N | samples (count) | "
        "p50 | p95 | p99 | engine took p95 | "
        "liferay+net p95 | preview Δ p95 | req | hits (count) |"
    )
    lines.append(
        "| --- | --- | --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | "
        "---: | ---: | ---: | ---: | ---: |"
    )

    for row in summaries:
        lines.append(
            "| %s | %s | %s | %s | %s | %s | %s | %d | %s | %s | %s | %s | %s | %s "
            "| %s | %s |"
            % (
                row["target"],
                row["engine_vendor"],
                row["query_type"],
                row["result_size"],
                row["concurrency"],
                row["cache_mode"],
                row["n_preview_items"],
                row["samples"],
                fmt_duration(row["roundtrip_p50"]),
                fmt_duration(row["roundtrip_p95"]),
                fmt_duration(row["roundtrip_p99"]),
                fmt_duration(row["took_p95"]),
                fmt_duration(row["client_overhead_p95"]),
                fmt_duration(row.get("preview_delta_p95")),
                fmt_bytes(row["request_bytes"]),
                row.get("hits_total", 0),
            )
        )

    lines.append("")
    lines.append("## Reading this table")
    lines.append("")
    lines.append(
        "- **target** — run identifier from the benchmark config "
        "(`target=` in preview-benchmark.properties); names the run folder."
    )
    lines.append(
        "- **engine** — search engine vendor and version, reported by the "
        "portal's `SearchEngineInformation` at run time."
    )
    lines.append(
        "- **query** — the query variant: `match_all` (empty search, worst "
        "case — filters evaluated over the whole corpus), `keyword` (scoped "
        "text query) or `faceted` (keyword + status terms aggregation)."
    )
    lines.append(
        "- **size** — result page size requested per search (`result.sizes`)."
    )
    lines.append(
        "- **conc** — simultaneous searcher threads in the cell "
        "(`concurrency`); each thread issues the same query."
    )
    lines.append(
        "- **mode / N** — `baseline` rows are the no-preview reference "
        "(N=0); `warm`/`cold` rows carry a preview map of N live→draft "
        "pairs (warm reuses one map, cold rotates it per iteration)."
    )
    lines.append(
        "- **samples (count)** — measured iterations aggregated into the "
        "cell (warmup iterations are excluded; baselines pool across N "
        "groups, so they show more)."
    )
    lines.append(
        "- **p50 / p95 / p99** — round-trip latency percentiles "
        "(nearest-rank) of `Searcher.search` as seen by the portal; each "
        "value carries its unit (ms/s/min)."
    )
    lines.append(
        "- **engine took p95** — p95 of the execution time the engine itself "
        "reports for the query (the ES/OS `took`)."
    )
    lines.append(
        "- **liferay+net p95** = p95 round-trip − p95 engine `took`. Everything "
        "that is NOT engine query execution: building the two TermsFilter "
        "objects, serializing them to request JSON, the HTTP hop, and "
        "translating the response back. Called *liferay+net* because from the "
        "engine's point of view Liferay is the client. If it grows with N "
        "faster than `took` does, the curve is serialization/transport-bound, "
        "not Lucene-bound."
    )
    lines.append(
        "- **preview Δ p95** = this cell's p95 minus the `baseline` cell's p95 "
        "at the same query and size. The cost the preview rewrite adds."
    )
    lines.append(
        "- **hits** is the sanity column: the result count must stay CONSTANT "
        "across N (the swap map removes N approved documents and adds N "
        "drafts, so the total cannot move; keyword cells may vary by ±1 when "
        "a swapped draft matches differently). If it moves, the corpus or the "
        "map is wrong and the latency numbers are measuring something else."
    )
    lines.append(
        "- **req** — request body size (KB/MB per row); should scale "
        "~linearly with N (~230 B per pair with "
        "UID-string keys; the model pre-filter contribution is emitted twice "
        "per search — pre-existing portal behaviour, visible in the baseline "
        "query too)."
    )
    lines.append(
        "- **warm vs cold** — `warm` reuses one preview map (within-session "
        "amortization); `cold` rotates the terms per iteration (per-preview "
        "cost floor, no engine query-cache reuse). Gaps within run-to-run "
        "noise (~10-15%) are indistinguishable in a single run."
    )
    lines.append("")

    # Growth factor is what answers "linear or super-linear", which is the
    # question the test plan asks and a raw table does not answer at a glance.
    lines.append("## Growth shape")
    lines.append("")

    groups = defaultdict(list)

    for row in summaries:
        if row["cache_mode"] == "baseline":
            continue

        groups[
            (
                row["target"],
                row["engine_vendor"],
                row["query_type"],
                str(row["result_size"]),
                str(row["concurrency"]),
                row["cache_mode"],
            )
        ].append(row)

    if not groups:
        lines.append("_Not enough non-baseline cells to compute growth._")
        lines.append("")
    else:
        lines.append(
            "| engine | query | size | conc | mode | N → N | p95 growth | N growth |"
        )
        lines.append("| --- | --- | --- | ---: | --- | --- | ---: | ---: |")

        def group_sort_key(item):
            (
                target,
                engine_vendor,
                query_type,
                result_size,
                concurrency,
                cache_mode,
            ) = item[0]

            try:
                result_size = int(result_size)
            except (TypeError, ValueError):
                result_size = 0

            try:
                concurrency = int(concurrency)
            except (TypeError, ValueError):
                concurrency = 0

            return (
                target,
                engine_vendor,
                query_type,
                result_size,
                concurrency,
                cache_mode,
            )

        for key, rows in sorted(groups.items(), key=group_sort_key):
            def n_of(r):
                try:
                    return int(r["n_preview_items"])
                except (TypeError, ValueError):
                    return 0

            rows = sorted(rows, key=n_of)

            for prev, cur in zip(rows, rows[1:]):
                n_prev, n_cur = n_of(prev), n_of(cur)

                if n_prev <= 0 or prev["roundtrip_p95"] in (None, 0):
                    continue

                lines.append(
                    "| %s | %s | %s | %s | %s | %d → %d | %s× | %s× |"
                    % (
                        key[1],
                        key[2],
                        key[3],
                        key[4],
                        key[5],
                        n_prev,
                        n_cur,
                        fmt(cur["roundtrip_p95"] / prev["roundtrip_p95"]),
                        fmt(n_cur / n_prev, 0),
                    )
                )

        lines.append("")
        lines.append("### Reading this table")
        lines.append("")
        lines.append(
            "- **engine / query / size / conc / mode** — same meaning as in "
            "the Latency vs N table; each row belongs to one such group "
            "(baseline cells are excluded — growth is about the preview "
            "cells)."
        )
        lines.append(
            "- **N → N** — a consecutive step of the N sweep inside the "
            "group (e.g. 100 → 500)."
        )
        lines.append(
            "- **p95 growth** — how many times the round-trip p95 grew "
            "across that step (p95 at the larger N ÷ p95 at the smaller N)."
        )
        lines.append(
            "- **N growth** — how many times N itself grew across the step; "
            "the yardstick the p95 growth is compared against."
        )
        lines.append("")
        lines.append(
            "A p95 growth factor well below the N growth factor means the cost is sublinear in N "
            "(the filter is amortizing or the fixed overhead dominates). At or "
            "above it means linear-to-super-linear — that is where the cap "
            "argument lives."
        )
        lines.append("")

    return "\n".join(lines) + "\n"


def write_csv(summaries, path):
    if not summaries:
        return

    fields = list(CELL_KEYS) + [
        "samples",
        "roundtrip_p50",
        "roundtrip_p95",
        "roundtrip_p99",
        "roundtrip_mean",
        "roundtrip_stdev",
        "took_p50",
        "took_p95",
        "client_overhead_p95",
        "preview_delta_p95",
        "request_bytes",
        "hits_total",
    ]

    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()

        for row in summaries:
            writer.writerow(row)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input",
        required=True,
        help="results.jsonl written by the preview:benchmark run",
    )
    parser.add_argument("--output-markdown")
    parser.add_argument("--output-csv")

    args = parser.parse_args()

    rows = load_rows(args.input)

    summaries = preview_delta(summarize(rows))

    report = render_markdown(summaries, args.input)

    if args.output_markdown:
        with open(args.output_markdown, "w") as f:
            f.write(report)
    else:
        sys.stdout.write(report)

    if args.output_csv:
        write_csv(summaries, args.output_csv)

    print(
        "analyzed %d raw rows into %d cells" % (len(rows), len(summaries)),
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
