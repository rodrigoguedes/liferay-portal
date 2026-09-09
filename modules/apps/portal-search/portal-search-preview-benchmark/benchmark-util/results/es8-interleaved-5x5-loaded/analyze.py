#!/usr/bin/env python3
"""LPD-104270 — analysis of the interleaved 5x5 A/B.

Reads the ledger the interleaved driver wrote, turns each measured run into a
summary CSV, and reports:

  * per-run index per metric, normalised to the median of all ten runs so
    neither arm is privileged;
  * an exact Mann-Whitney rank-sum test, which 5-vs-5 makes worth running --
    complete separation reaches p = 0.008, so a tail claim can stand on its own;
  * the per-cell median table;
  * the same numbers from the earlier blocked 3x3, for comparison. If the p95
    separation the blocked design showed was drift loading onto one arm, it
    should shrink or vanish once the arms are interleaved.
"""

import csv
import io
import itertools
import json
import math
import os
import statistics
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LEDGER = os.path.join(
    HERE, sys.argv[1] if len(sys.argv) > 1 else "ledger.json"
)
OUT = "/home/rodrigo/development/search-2/bundles/preview-benchmark"
BENCH = os.path.abspath(os.path.join(HERE, "..", ".."))
RESULTS = os.path.join(BENCH, "results")
WORK = os.path.join(HERE, "csv-" + os.path.basename(LEDGER).replace(".json", ""))

METRICS = [
    ("roundtrip_p50", "Round-trip p50"),
    ("roundtrip_p95", "Round-trip p95"),
    ("took_p50", "Engine took p50"),
    ("took_p95", "Engine took p95"),
]


def summarise(run_dir, dest):
    """analyze.py -> summary CSV for one run."""
    subprocess.run(
        [
            sys.executable,
            os.path.join(BENCH, "tools/analyze.py"),
            "--input", os.path.join(run_dir, "results.jsonl"),
            "--output-csv", dest,
            "--output-markdown", os.devnull,
        ],
        check=True,
        capture_output=True,
    )


def load(path, field):
    out = {}
    for r in csv.DictReader(io.open(path, encoding="utf-8")):
        key = (r["query_type"], int(r["result_size"]), int(r["concurrency"]))
        out[key] = float(r[field] or 0)
    return out


def mannwhitney_exact(a, b):
    """Exact two-sided rank-sum p-value for small samples, no ties assumed."""
    n, m = len(a), len(b)
    pooled = sorted(a + b)
    rank = {v: i + 1 for i, v in enumerate(pooled)}
    observed = sum(rank[v] for v in a)

    total = 0
    extreme = 0
    mean = n * (n + m + 1) / 2.0
    for combo in itertools.combinations(pooled, n):
        total += 1
        s = sum(rank[v] for v in combo)
        if abs(s - mean) >= abs(observed - mean):
            extreme += 1
    return extreme / total


def index_per_run(csvs, field, keys):
    """Each run's median ratio against the median of every run, over keys."""
    tables = [load(p, field) for p in csvs]
    ref = {k: statistics.median([t[k] for t in tables]) for k in keys}
    return [statistics.median([t[k] / ref[k] * 100 for k in keys]) for t in tables]


def block(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def main():
    ledger = json.load(io.open(LEDGER, encoding="utf-8"))
    if ledger.get("error"):
        print("ATENÇÃO: o driver abortou — %s" % ledger["error"])
        print()

    os.makedirs(WORK, exist_ok=True)

    csvs = {"A": [], "B": []}
    for side in ("A", "B"):
        for i, name in enumerate(ledger["runs"][side], 1):
            dest = os.path.join(HERE, "%s%d-summary.csv" % (side, i))
            if not os.path.exists(dest):
                dest = os.path.join(WORK, "%s%d.csv" % (side, i))
                summarise(os.path.join(OUT, name), dest)
            csvs[side].append(dest)

    n_a, n_b = len(csvs["A"]), len(csvs["B"])
    print("ordem executada: %s" % " ".join(ledger["order"]))
    print("medições: lado A %d, lado B %d" % (n_a, n_b))

    keys_all = sorted(load(csvs["A"][0], "roundtrip_p50"))
    keys = [k for k in keys_all if k[1] >= 1000]
    print("células usadas na agregação: %d (result size >= 1000)" % len(keys))

    if n_a >= 2 and n_b >= 2:
        print("p mínimo alcançável com %dx%d (separação total): %.4f"
              % (n_a, n_b, 2 / math.comb(n_a + n_b, n_a)))

    block("Índice por execução — 100 = mediana das %d execuções" % (n_a + n_b))
    print("%-18s %-34s %9s %9s %9s" % ("métrica", "execuções", "med A", "med B", "p"))
    print("-" * 78)

    verdicts = {}
    for field, label in METRICS:
        idx = index_per_run(csvs["A"] + csvs["B"], field, keys)
        ia, ib = idx[:n_a], idx[n_a:]
        p = mannwhitney_exact(ia, ib) if (n_a >= 2 and n_b >= 2) else float("nan")
        verdicts[field] = (statistics.median(ia), statistics.median(ib), p)
        shown = "A " + "/".join("%.1f" % v for v in sorted(ia))
        print("%-18s %-34s %9.1f %9.1f %9.4f"
              % (label, shown, statistics.median(ia), statistics.median(ib), p))
        print("%-18s %-34s" % ("", "B " + "/".join("%.1f" % v for v in sorted(ib))))
        print("-" * 78)

    block("Veredito por métrica")
    for field, label in METRICS:
        ma, mb, p = verdicts[field]
        d = (mb - ma) / ma * 100
        if p < 0.05:
            v = "SIGNIFICATIVO (p=%.4f)" % p
        elif p < 0.10:
            v = "sugestivo (p=%.4f)" % p
        else:
            v = "sem efeito detectável (p=%.4f)" % p
        print("  %-18s delta %+6.2f%%   %s" % (label, d, v))

    block("Medianas por célula (ms)")
    print("%-24s %9s %9s %8s   %9s %9s %8s"
          % ("célula", "A p50", "B p50", "Δ p50", "A p95", "B p95", "Δ p95"))
    print("-" * 78)
    t50a = [load(p, "roundtrip_p50") for p in csvs["A"]]
    t50b = [load(p, "roundtrip_p50") for p in csvs["B"]]
    t95a = [load(p, "roundtrip_p95") for p in csvs["A"]]
    t95b = [load(p, "roundtrip_p95") for p in csvs["B"]]
    for k in sorted(keys_all, key=lambda k: (-k[1], k[0], k[2])):
        a50 = statistics.median([t[k] for t in t50a])
        b50 = statistics.median([t[k] for t in t50b])
        a95 = statistics.median([t[k] for t in t95a])
        b95 = statistics.median([t[k] for t in t95b])
        print("%-24s %9.1f %9.1f %+7.1f%%   %9.1f %9.1f %+7.1f%%"
              % ("%s/s%d/c%d" % k, a50, b50, (b50 - a50) / a50 * 100,
                 a95, b95, (b95 - a95) / a95 * 100))

    # The blocked 3x3 for contrast.
    old_a = [os.path.join(RESULTS, "es8-nopreview/take%d-summary.csv" % i)
             for i in (1, 2, 3)]
    old_b = [os.path.join(RESULTS, "es8-preview/take%d-summary.csv" % i)
             for i in (1, 2, 3)]
    if all(os.path.isfile(p) for p in old_a + old_b):
        block("Contraste: 3x3 em blocos (manhã) vs %dx%d intercalado" % (n_a, n_b))
        print("%-18s %14s %14s" % ("métrica", "delta blocos", "delta interc."))
        print("-" * 78)
        for field, label in METRICS:
            oi = index_per_run(old_a + old_b, field, keys)
            oa, ob = statistics.median(oi[:3]), statistics.median(oi[3:])
            ma, mb, _ = verdicts[field]
            print("%-18s %13.2f%% %13.2f%%"
                  % (label, (ob - oa) / oa * 100, (mb - ma) / ma * 100))

    return 0


if __name__ == "__main__":
    sys.exit(main())
