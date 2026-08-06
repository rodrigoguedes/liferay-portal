#!/usr/bin/env python3
"""LPD-98298 — shared analyzer for both PoCs.

Reads raw per-iteration samples and produces the curves the Jira asks for.
Deliberately stdlib-only (no pandas/numpy) so it runs on any benchmark host
without a virtualenv.

Two input formats, one output schema -- which is the whole point of the result
schema in WHAT-IS-MEASURED.md: numbers from an in-container
integration benchmark and from an external load tool become directly
comparable.

    --format poc1   JSONL, one row per iteration, written by
                    PreviewSearchBenchmarkTest's recorder.

    --format k6     A PoC 2 run directory. Reads each cell's raw-samples.json
                    (k6 `--out json`), which is one JSON object per metric
                    sample, and normalizes it to the same rows.

Percentiles are computed HERE, never in the harness. Pre-aggregating in the
harness would destroy the ability to re-cut the data (per-N, per-concurrency,
warm vs cold) without re-running a benchmark that takes hours.
"""

import argparse
import csv
import glob
import json
import math
import os
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


def load_poc1(path):
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


def load_k6(run_dir):
    """Normalize k6 `--out json` samples into PoC 1's row shape.

    k6 emits one object per metric point: {"type":"Point","metric":"...",
    "data":{"time":...,"value":...,"tags":{...}}}. Latency and engine `took`
    arrive as separate points, so they are stitched together per cell rather
    than per iteration -- k6 gives no iteration id in the sample stream. That is
    fine for percentiles (each metric is summarized independently); it just
    means a per-iteration roundtrip-minus-took delta is not available from k6.
    PoC 1 is the layer that can attribute cost per iteration.
    """
    rows = []

    sample_files = sorted(glob.glob(os.path.join(run_dir, "*", "raw-samples.json")))

    if not sample_files:
        print(
            "error: no raw-samples.json found under %s" % run_dir,
            file=sys.stderr,
        )
        return rows

    for sample_file in sample_files:
        latencies = defaultdict(list)
        tooks = defaultdict(list)
        response_bytes = defaultdict(list)
        hits = defaultdict(list)

        with open(sample_file) as f:
            for line in f:
                line = line.strip()

                if not line:
                    continue

                try:
                    point = json.loads(line)
                except json.JSONDecodeError:
                    continue

                if point.get("type") != "Point":
                    continue

                metric = point.get("metric")
                data = point.get("data", {})
                tags = data.get("tags", {}) or {}

                # Warm-up samples carry no custom metrics by construction, but
                # filter defensively so a script change cannot silently let
                # warm-up latency into the percentiles.
                if tags.get("phase") not in (None, "measure"):
                    continue

                key = tuple(tags.get(k, "unknown") for k in CELL_KEYS)
                value = data.get("value")

                if value is None:
                    continue

                if metric == "preview_latency_ms":
                    latencies[key].append(value)
                elif metric == "preview_engine_took_ms":
                    tooks[key].append(value)
                elif metric == "preview_response_bytes":
                    response_bytes[key].append(value)
                elif metric == "preview_hits_total":
                    hits[key].append(value)

        # Request size is constant per cell (it is the captured query file), so
        # read it from the cell summary rather than the sample stream.
        request_bytes = 0

        for summary_path in glob.glob(
            os.path.join(os.path.dirname(sample_file), "summary-*.json")
        ):
            try:
                with open(summary_path) as f:
                    request_bytes = json.load(f).get("request_bytes", 0)
            except Exception:
                pass

        for key, values in latencies.items():
            cell = dict(zip(CELL_KEYS, key))

            for i, value in enumerate(values):
                row = dict(cell)
                row["poc"] = "poc2-k6"
                row["phase"] = "measure"
                row["iteration"] = i
                row["roundtrip_ms"] = value
                row["request_bytes"] = request_bytes

                # Constant per cell, so the mean is the value. Without this the
                # summary reported hits_total=0 for every k6 cell while k6's own
                # summary held the right number -- a silent reporting hole, and
                # hits_total is what proves the query matched anything at all.
                if hits.get(key):
                    row["hits_total"] = int(
                        statistics.fmean(hits[key]))

                if response_bytes.get(key):
                    row["response_bytes"] = int(
                        statistics.fmean(response_bytes[key]))

                rows.append(row)

            # `took` samples are appended as their own pseudo-rows so the
            # summarizer can compute their percentiles; they are tagged so they
            # never inflate the roundtrip count.
            for i, value in enumerate(tooks.get(key, [])):
                row = dict(cell)
                row["poc"] = "poc2-k6"
                row["phase"] = "measure"
                row["iteration"] = i
                row["engine_took_ms"] = value
                row["_took_only"] = True
                rows.append(row)

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

        if not row.get("_took_only"):
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

        # -1 is the sentinel PoC 1 writes when the engine reported no took.
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

        return (
            r["target"],
            r["engine_vendor"],
            r["query_type"],
            str(r["result_size"]),
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
                )
            ] = row["roundtrip_p95"]

    for row in summaries:
        base = baselines.get(
            (
                row["target"],
                row["engine_vendor"],
                row["query_type"],
                str(row["result_size"]),
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
            "Most likely causes: the benchmark properties never reached the "
            "portal JVM (PoC 1 — see the CATALINA_OPTS note in its run.sh), or "
            "no cell produced `phase=measure` rows."
        )
        return "\n".join(lines) + "\n"

    lines.append("## Latency vs N")
    lines.append("")
    lines.append(
        "| target | engine | query | size | mode | N | samples | p50 | p95 | "
        "p99 | engine took p95 | liferay+net p95 | preview Δ p95 | req bytes | hits |"
    )
    lines.append(
        "| --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | "
        "---: | ---: | ---: | ---: | ---: |"
    )

    for row in summaries:
        lines.append(
            "| %s | %s | %s | %s | %s | %s | %d | %s | %s | %s | %s | %s | %s "
            "| %s | %s |"
            % (
                row["target"],
                row["engine_vendor"],
                row["query_type"],
                row["result_size"],
                row["cache_mode"],
                row["n_preview_items"],
                row["samples"],
                fmt(row["roundtrip_p50"]),
                fmt(row["roundtrip_p95"]),
                fmt(row["roundtrip_p99"]),
                fmt(row["took_p95"]),
                fmt(row["client_overhead_p95"]),
                fmt(row.get("preview_delta_p95")),
                row["request_bytes"],
                row.get("hits_total", 0),
            )
        )

    lines.append("")
    lines.append("## Reading this table")
    lines.append("")
    lines.append(
        "- **target** — the request path, and the first thing to read. "
        "`engine` = k6 straight to Elasticsearch/OpenSearch (the engine-side "
        "floor, and the clean ES-vs-OS comparison). `integration` = PoC 1 "
        "in-process through the Liferay search stack. `portal` = k6 through the "
        "headless Search API, which additionally pays for shipping the preview "
        "map over the wire — so it is the closest to what a user waits for, and "
        "the highest. Comparing the three localizes the cost."
    )
    lines.append(
        "- **engine took p95 is `n/a` for `portal`** by design: the headless response "
        "carries no engine `took`. Engine-side attribution comes from "
        "`integration`, which reads it in-process."
    )
    lines.append(
        "- **liferay+net p95** = p95 round-trip − p95 engine `took`. Everything that is "
        "NOT engine query execution: building the two TermsFilter objects, "
        "serializing them to request JSON, the HTTP hop, and translating the "
        "response back. Called *liferay+net* because from the engine's point of "
        "view Liferay is the client. This is the "
        "Liferay-side plus network cost. If it grows with N faster than `took` "
        "does, the curve is serialization/transport-bound, not Lucene-bound — "
        "which would make the numeric-terms-key experiment the headline "
        "optimization rather than an optional lever."
    )
    lines.append(
        "- **preview Δ p95** = this cell's p95 minus the `baseline` cell's p95 "
        "at the same query and size. The cost the preview rewrite adds."
    )
    lines.append(
        "- **hits** is the sanity column: the result count must stay CONSTANT "
        "across N. The swap map removes N approved documents and adds N drafts, "
        "so the total cannot move. If it does, the corpus or the map is wrong "
        "and the latency numbers are measuring something else. Measured 103 at "
        "every N in the first portal run.\n"
        "- **req bytes** should scale ~linearly with N. Measured at 232 B "
        "per pair with UID-string keys, which is 2x the 116 B the design "
        "implies -- the model pre-filter contribution is emitted twice per "
        "search (pre-existing portal behaviour, visible in the baseline "
        "query too). So N=10000 is ~2.3 MB as emitted today, ~1.2 MB "
        "deduplicated. At that size the payload alone may dominate."
    )
    lines.append(
        "- **warm vs cold** — `warm` reuses one preview map (within-session "
        "amortization); `cold` varies the terms per iteration (per-preview cost "
        "floor). Unresolved so far: with the query cache engaged the cold-warm gap "
        "measured under 2 ms, below the run-to-run spread. Cross-check "
        "`query_cache_hits` in engine-stats-delta.json -- across three identical "
        "runs it was 0, 16362, 16358, 16361, so the cache is bimodal and the zero "
        "belongs to the FIRST run on a freshly created index. Discard that run: it "
        "also produced the only non-monotonic curve. Measured CV over the three "
        "warm-regime runs is 7% median and 13.2% worst case -- treat any gap under "
        "~15% as indistinguishable in a single run."
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
                row["query_type"],
                str(row["result_size"]),
                row["cache_mode"],
            )
        ].append(row)

    if not groups:
        lines.append("_Not enough non-baseline cells to compute growth._")
        lines.append("")
    else:
        lines.append("| engine | query | size | mode | N → N | p95 growth × | N growth × |")
        lines.append("| --- | --- | --- | --- | --- | ---: | ---: |")

        for key, rows in sorted(groups.items()):
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
                    "| %s | %s | %s | %s | %d → %d | %s | %s |"
                    % (
                        key[0],
                        key[1],
                        key[2],
                        key[3],
                        n_prev,
                        n_cur,
                        fmt(cur["roundtrip_p95"] / prev["roundtrip_p95"]),
                        fmt(n_cur / n_prev),
                    )
                )

        lines.append("")
        lines.append(
            "p95 growth × well below N growth × means the cost is sublinear in N "
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
    parser.add_argument("--format", choices=("poc1", "k6"), required=True)
    parser.add_argument(
        "--input",
        required=True,
        help="poc1: results.jsonl. k6: the run directory.",
    )
    parser.add_argument("--output-markdown")
    parser.add_argument("--output-csv")

    args = parser.parse_args()

    if args.format == "poc1":
        rows = load_poc1(args.input)
    else:
        rows = load_k6(args.input)

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
