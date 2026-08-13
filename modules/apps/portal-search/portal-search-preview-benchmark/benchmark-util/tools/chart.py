#!/usr/bin/env python3
"""LPD-98298 — chart generator for the preview search benchmark results.

Reads the aggregated summary.csv files produced by analyze.py (never the raw
JSONL) and renders the five decision charts as PNG images.

Usage:
    python3 tools/chart.py \\
        --es results/es8-full/summary.csv \\
        --os results/os2-full/summary.csv \\
        --output-dir results/charts

Either engine flag may be omitted; charts then render single-engine.

Self-contained and deterministic: colors, scales and annotations are fixed in
this file. Only dependency: matplotlib (e.g. `python3 -m venv tools/.venv &&
tools/.venv/bin/pip install matplotlib`).

Canonical slice for the N-curve charts: query=match_all, result size=20,
concurrency=1 — the worst-case query at a realistic page size, with the terms
cost isolated. Other slices are called out on each chart.
"""

import argparse
import csv
import os

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

# Fixed identity colors (validated colorblind-safe pair; color follows the
# engine, never the series count).
ES_COLOR = "#2a78d6"
ES_LIGHT = "#a8c8ee"
OS_COLOR = "#eb6834"
OS_LIGHT = "#f6c0a9"
BASELINE_GRAY = "#a5a49b"
INK = "#1a1a19"
INK_SECONDARY = "#55544d"
GRID = "#e6e5e0"
SURFACE = "#fcfcfb"

NS = [1, 10, 100, 500, 1000, 10000]
BUDGET_MS = 15
CAP_N = 1000

plt.rcParams.update(
    {
        "figure.facecolor": SURFACE,
        "axes.facecolor": SURFACE,
        "savefig.facecolor": SURFACE,
        "text.color": INK,
        "axes.edgecolor": INK_SECONDARY,
        "axes.labelcolor": INK_SECONDARY,
        "xtick.color": INK_SECONDARY,
        "ytick.color": INK_SECONDARY,
        "axes.grid": True,
        "grid.color": GRID,
        "grid.linewidth": 0.8,
        "axes.axisbelow": True,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "font.size": 11,
        "axes.titlesize": 13,
        "axes.titleweight": "bold",
        "legend.frameon": False,
    }
)


def load(path):
    """summary.csv -> {(query, size, conc, mode, n): row-with-numbers}."""
    cells = {}

    with open(path) as f:
        for row in csv.DictReader(f):
            key = (
                row["query_type"],
                int(row["result_size"]),
                int(row["concurrency"]),
                row["cache_mode"],
                int(row["n_preview_items"]),
            )

            for field in (
                "roundtrip_p95",
                "took_p95",
                "client_overhead_p95",
                "preview_delta_p95",
                "request_bytes",
            ):
                row[field] = float(row[field]) if row[field] else None

            cells[key] = row

    return cells


def series(cells, mode, field, query="match_all", size=20, conc=1):
    values = []

    for n in NS:
        row = cells.get((query, size, conc, mode, n))
        values.append(row[field] if row else None)

    return values


def save(fig, output_dir, name):
    path = os.path.join(output_dir, name)

    fig.savefig(path, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print("wrote %s" % path)


def chart_delta_vs_n(engines, output_dir):
    """01 — the decision chart: added p95 vs N, budget line, cap marker."""
    fig, ax = plt.subplots(figsize=(8.6, 5.2))

    for name, cells, color in engines:
        for mode, style in (("warm", "-"), ("cold", "--")):
            ax.plot(
                NS,
                series(cells, mode, "preview_delta_p95"),
                style,
                color=color,
                linewidth=2,
                marker="o",
                markersize=6,
                label="%s %s" % (name, mode),
            )

    ax.set_xscale("log")
    ax.axhline(0, color=INK_SECONDARY, linewidth=0.8)
    ax.axhline(BUDGET_MS, color=INK_SECONDARY, linewidth=1.2, linestyle=":")
    ax.annotate(
        "proposed cap budget (+%d ms)" % BUDGET_MS,
        xy=(1.1, BUDGET_MS),
        xytext=(1.1, BUDGET_MS + 3),
        fontsize=9,
        color=INK_SECONDARY,
    )
    ax.axvline(CAP_N, color=INK_SECONDARY, linewidth=1.2, linestyle=":")
    ax.annotate(
        "recommended cap\nN = 1,000",
        xy=(CAP_N, ax.get_ylim()[1]),
        xytext=(CAP_N * 1.15, ax.get_ylim()[1] * 0.82),
        fontsize=9,
        color=INK_SECONDARY,
    )

    ax.set_title("Preview cost is flat to N≈100, linear after — budget crossed near N=1,000")
    ax.set_xlabel("N (preview pairs in the map, log scale)")
    ax.set_ylabel("added p95 vs baseline (ms)")
    ax.set_xticks(NS)
    ax.set_xticklabels(["1", "10", "100", "500", "1k", "10k"])
    ax.legend(loc="upper left")
    fig.text(
        0.99,
        0.01,
        "match_all, page size 20, concurrency 1 · deltas under ~5 ms are run noise",
        ha="right",
        fontsize=8,
        color=INK_SECONDARY,
    )

    fig.subplots_adjust(bottom=0.17)

    save(fig, output_dir, "01-delta-vs-n.png")


def chart_cost_breakdown(engines, output_dir):
    """02 — where the cost lives: engine took vs Liferay+network, stacked."""
    fig, axes = plt.subplots(
        1, len(engines), figsize=(4.6 * len(engines), 5.0), sharey=True
    )

    if len(engines) == 1:
        axes = [axes]

    x = range(len(NS))

    for ax, (name, cells, color) in zip(axes, engines):
        took = series(cells, "warm", "took_p95")
        overhead = series(cells, "warm", "client_overhead_p95")

        ax.bar(
            x,
            took,
            color=color,
            width=0.62,
            edgecolor=SURFACE,
            linewidth=2,
            label="engine execution (took)",
        )
        ax.bar(
            x,
            overhead,
            bottom=took,
            color={ES_COLOR: ES_LIGHT, OS_COLOR: OS_LIGHT}[color],
            width=0.62,
            edgecolor=SURFACE,
            linewidth=2,
            label="Liferay + network",
        )

        for i, (t, o) in enumerate(zip(took, overhead)):
            if t is None or o is None:
                continue

            total = t + o
            share = 100.0 * o / total if total else 0

            ax.annotate(
                "%.0f%%" % share,
                xy=(i, total),
                xytext=(0, 4),
                textcoords="offset points",
                ha="center",
                fontsize=9,
                color=INK_SECONDARY,
            )

        ax.set_title(name)
        ax.set_xticks(list(x))
        ax.set_xticklabels(["1", "10", "100", "500", "1k", "10k"])
        ax.set_xlabel("N (preview pairs)")
        ax.legend(loc="upper left", fontsize=9)

    axes[0].set_ylabel("p95 round-trip (ms)")
    fig.suptitle(
        "Most of the preview cost is Liferay-side serialization + transport, not the engine",
        fontweight="bold",
        fontsize=13,
    )
    fig.text(
        0.99,
        0.01,
        "match_all, page size 20, concurrency 1, warm · % = Liferay+network share of total p95",
        ha="right",
        fontsize=8,
        color=INK_SECONDARY,
    )
    fig.tight_layout(rect=(0, 0.03, 1, 0.95))

    save(fig, output_dir, "02-cost-breakdown.png")


def chart_concurrency(engines, output_dir):
    """03 — the ceiling is N x concurrency."""
    combos = [(1000, 1), (1000, 5), (10000, 1), (10000, 5)]
    labels = ["N=1k\nc=1", "N=1k\nc=5", "N=10k\nc=1", "N=10k\nc=5"]

    fig, ax = plt.subplots(figsize=(8.0, 5.0))

    width = 0.36

    for offset, (name, cells, color) in zip((-width / 2, width / 2), engines):
        values = []

        for n, conc in combos:
            row = cells.get(("match_all", 20, conc, "warm", n))
            values.append(row["preview_delta_p95"] if row else 0)

        positions = [i + offset for i in range(len(combos))]

        bars = ax.bar(
            positions,
            values,
            width=width,
            color=color,
            edgecolor=SURFACE,
            linewidth=2,
            label=name,
        )

        for bar, value in zip(bars, values):
            ax.annotate(
                "%.0f" % value,
                xy=(bar.get_x() + bar.get_width() / 2, value),
                xytext=(0, 3),
                textcoords="offset points",
                ha="center",
                fontsize=9,
                color=INK_SECONDARY,
            )

    if len(engines) == 1:
        # recentre single-engine bars
        for patch in ax.patches:
            patch.set_x(patch.get_x() + width / 2)

    ax.set_title("Concurrency compounds at high N — budget on N × concurrency")
    ax.set_xticks(range(len(combos)))
    ax.set_xticklabels(labels)
    ax.set_ylabel("added p95 vs baseline (ms)")
    ax.legend(loc="upper left")
    fig.text(
        0.99,
        0.01,
        "match_all, page size 20, warm · at N=10k five concurrent searchers nearly double the cost",
        ha="right",
        fontsize=8,
        color=INK_SECONDARY,
    )

    fig.subplots_adjust(bottom=0.17)

    save(fig, output_dir, "03-concurrency.png")


def chart_result_size(engines, output_dir):
    """04 — page size does not compound the preview cost."""
    sizes = [20, 1000, 10000]
    labels = ["20", "1,000", "10,000"]

    fig, axes = plt.subplots(
        1, len(engines), figsize=(4.6 * len(engines), 5.0), sharey=True
    )

    if len(engines) == 1:
        axes = [axes]

    width = 0.38

    for ax, (name, cells, color) in zip(axes, engines):
        baselines = []
        deltas = []

        for size in sizes:
            base = cells.get(("match_all", size, 1, "baseline", 0))
            preview = cells.get(("match_all", size, 1, "warm", 10000))
            baselines.append(base["roundtrip_p95"] if base else 0)
            deltas.append(preview["preview_delta_p95"] if preview else 0)

        x = range(len(sizes))

        for offset, values, bar_color, label in (
            (-width / 2, baselines, BASELINE_GRAY, "baseline p95 (no preview)"),
            (width / 2, deltas, color, "added by preview @ N=10k"),
        ):
            bars = ax.bar(
                [i + offset for i in x],
                values,
                width=width,
                color=bar_color,
                edgecolor=SURFACE,
                linewidth=2,
                label=label,
            )

            for bar, value in zip(bars, values):
                ax.annotate(
                    "%.0f" % value,
                    xy=(bar.get_x() + bar.get_width() / 2, max(value, 0)),
                    xytext=(0, 3),
                    textcoords="offset points",
                    ha="center",
                    fontsize=9,
                    color=INK_SECONDARY,
                )

        ax.set_title(name)
        ax.set_xticks(list(x))
        ax.set_xticklabels(labels)
        ax.set_xlabel("result page size")
        ax.legend(loc="upper left", fontsize=9)

    axes[0].set_ylabel("p95 (ms)")
    fig.suptitle(
        "Large pages are expensive on their own — preview does not multiply that cost",
        fontweight="bold",
        fontsize=13,
    )
    fig.text(
        0.99,
        0.01,
        "match_all, concurrency 1 · gray = baseline round-trip; colored = preview delta at N=10,000",
        ha="right",
        fontsize=8,
        color=INK_SECONDARY,
    )
    fig.tight_layout(rect=(0, 0.03, 1, 0.95))

    save(fig, output_dir, "04-result-size.png")


def chart_payload(engines, output_dir):
    """05 — request payload grows linearly with N (identical on both engines)."""
    name, cells, _ = engines[0]

    payload = series(cells, "warm", "request_bytes")

    fig, ax = plt.subplots(figsize=(8.0, 5.0))

    ax.plot(
        NS,
        payload,
        "-",
        color=INK,
        linewidth=2,
        marker="o",
        markersize=6,
    )

    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xticks(NS)
    ax.set_xticklabels(["1", "10", "100", "500", "1k", "10k"])
    ax.yaxis.set_major_formatter(
        FuncFormatter(
            lambda v, _: (
                "%.0f KB" % (v / 1000) if v < 1000000 else "%.1f MB" % (v / 1000000)
            )
        )
    )

    ax.annotate(
        "≈230 bytes per pair\n(UID keys, clauses emitted twice)",
        xy=(500, payload[3]),
        xytext=(30, payload[3] * 2.2),
        fontsize=9,
        color=INK_SECONDARY,
        arrowprops={"arrowstyle": "-", "color": INK_SECONDARY, "linewidth": 0.8},
    )
    ax.annotate(
        "2.31 MB @ N=10k",
        xy=(10000, payload[5]),
        xytext=(1500, payload[5] * 0.45),
        fontsize=9,
        color=INK_SECONDARY,
        arrowprops={"arrowstyle": "-", "color": INK_SECONDARY, "linewidth": 0.8},
    )

    ax.set_title("Request payload grows linearly with N — the lever is bytes per pair")
    ax.set_xlabel("N (preview pairs, log scale)")
    ax.set_ylabel("request body size (log scale)")
    fig.text(
        0.99,
        0.01,
        "match_all, page size 20 · payload is identical on both engines · "
        "dedupe + numeric keys cut it 2x and 4-8x",
        ha="right",
        fontsize=8,
        color=INK_SECONDARY,
    )

    fig.subplots_adjust(bottom=0.17)

    save(fig, output_dir, "05-payload-vs-n.png")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--es", help="Elasticsearch summary.csv (from analyze.py)")
    parser.add_argument("--os", dest="os_", help="OpenSearch summary.csv")
    parser.add_argument("--output-dir", default="results/charts")

    args = parser.parse_args()

    engines = []

    if args.es:
        engines.append(("Elasticsearch", load(args.es), ES_COLOR))

    if args.os_:
        engines.append(("OpenSearch", load(args.os_), OS_COLOR))

    if not engines:
        parser.error("pass --es and/or --os")

    os.makedirs(args.output_dir, exist_ok=True)

    chart_delta_vs_n(engines, args.output_dir)
    chart_cost_breakdown(engines, args.output_dir)
    chart_concurrency(engines, args.output_dir)
    chart_result_size(engines, args.output_dir)
    chart_payload(engines, args.output_dir)


if __name__ == "__main__":
    main()
