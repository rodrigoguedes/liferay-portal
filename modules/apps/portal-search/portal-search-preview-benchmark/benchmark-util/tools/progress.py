#!/usr/bin/env python3
"""LPD-98298 — live progress of a running preview:benchmark.

Usage:
    progress.py [run_dir]

Without an argument, picks the newest run under
$LIFERAY_BUNDLES_DIR/preview-benchmark. Expected totals are computed from
$LIFERAY_BUNDLES_DIR/preview-benchmark.properties (the same defaults the
module uses) — if the config file changed after the run started, the totals
and the ETA are off accordingly.

The ETA extrapolates the average rate measured so far (timestamps inside
results.jsonl). Cells are not equally expensive (large result sizes and high
concurrency are slower), so treat it as an estimate.

Tip: watch -n 30 python3 tools/progress.py
"""

import glob
import json
import os
import sys
from datetime import datetime, timedelta

DEFAULTS = {
    "ns": "1,10,100",
    "query.types": "match_all,keyword",
    "result.sizes": "20",
    "concurrency": "1",
    "cache.modes": "warm,cold",
    "warmup.iterations": "10",
    "measure.iterations": "50",
}


def parse_timestamp(value):
    # e.g. 2026-08-11T21:29:25.863498392Z — trim nanoseconds to microseconds
    value = value.rstrip("Z")

    if "." in value:
        head, frac = value.split(".", 1)
        value = head + "." + frac[:6]

    return datetime.fromisoformat(value)


def main():
    bundles = os.environ.get("LIFERAY_BUNDLES_DIR", "")

    if len(sys.argv) > 1:
        run_dir = sys.argv[1].rstrip("/")
    else:
        if not bundles:
            sys.exit("Set LIFERAY_BUNDLES_DIR or pass the run directory.")

        runs = sorted(
            glob.glob(os.path.join(bundles, "preview-benchmark", "*")),
            key=os.path.getmtime,
        )

        if not runs:
            sys.exit("No runs found under %s/preview-benchmark." % bundles)

        run_dir = runs[-1]

    results_path = os.path.join(run_dir, "results.jsonl")

    if not os.path.isfile(results_path):
        sys.exit("No results.jsonl in %s." % run_dir)

    # Expected total from the config file (module defaults when keys/file absent).
    props = dict(DEFAULTS)
    config_path = os.path.join(bundles, "preview-benchmark.properties")

    if bundles and os.path.isfile(config_path):
        with open(config_path) as f:
            for line in f:
                line = line.strip()

                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    props[key.strip()] = value.strip()

    count = lambda key: len(props[key].split(","))

    cells = (
        count("ns")
        * count("query.types")
        * count("result.sizes")
        * count("concurrency")
        * (1 + count("cache.modes"))
    )
    per_cell = int(props["warmup.iterations"]) + int(props["measure.iterations"])
    total = cells * per_cell

    # Progress from the JSONL (first line, last line, line count).
    done = 0
    first_row = last_row = None

    with open(results_path) as f:
        for line in f:
            line = line.strip()

            if not line:
                continue

            done += 1

            if first_row is None:
                first_row = line

            last_row = line

    print("run:      %s" % run_dir)

    if done == 0:
        print("progress: 0 / %d — waiting for the first sample" % total)
        return

    last = json.loads(last_row)

    print(
        "progress: %d / %d samples (%.1f%%)  [%d cells x %d iterations]"
        % (done, total, 100.0 * done / total, cells, per_cell)
    )
    print(
        "cell:     %s n=%s %s size=%s c=%s — iteration %s (%s)"
        % (
            last["query_type"],
            last["n_preview_items"],
            last["cache_mode"],
            last["result_size"],
            last["concurrency"],
            last["iteration"],
            last["phase"],
        )
    )

    started = parse_timestamp(json.loads(first_row)["timestamp"])
    latest = parse_timestamp(last["timestamp"])
    elapsed = (latest - started).total_seconds()

    if done >= total:
        print("elapsed:  %s — run complete" % timedelta(seconds=int(elapsed)))
        return

    if elapsed < 1:
        print("elapsed:  <1s — too early for an ETA")
        return

    rate = done / elapsed
    remaining = (total - done) / rate

    print(
        "elapsed:  %s at %.1f samples/s"
        % (timedelta(seconds=int(elapsed)), rate)
    )
    print(
        "ETA:      ~%s remaining (finishes around %s)"
        % (
            timedelta(seconds=int(remaining)),
            (datetime.now() + timedelta(seconds=remaining)).strftime("%H:%M"),
        )
    )


if __name__ == "__main__":
    main()
