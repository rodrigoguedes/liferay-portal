#!/usr/bin/env python3
"""Compara configuracoes de codigo medidas na fatia de perfilamento.

Le ledger.json neste diretorio: "runs" mapeia cada configuracao para a lista
de execucoes medidas, "order" e a sequencia em que rodaram e "labels" da o
nome de cada configuracao. Cada execucao tem <config><n>-summary.csv ao lado
(gerado por tools/analyze.py); se faltar, e regenerado a partir do diretorio
de run no bundle.

Cada execucao tem duas celulas: N=0 (baseline) e N=10000. As comparacoes sao
entre configuracoes consecutivas e entre a primeira e a ultima, com o p exato
bilateral do teste de postos (Mann-Whitney). Com 3 execucoes por lado o menor
p alcancavel e 0,10 — nenhuma comparacao chega a significancia convencional,
e o que sustenta uma conclusao e a separacao das faixas em metricas
independentes.
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
BENCH = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = "/home/rodrigo/development/search-2/bundles/preview-benchmark"
LEDGER = os.path.join(HERE, sys.argv[1] if len(sys.argv) > 1 else "ledger.json")


def summarise(run_dir, dest):
    subprocess.run(
        [sys.executable, os.path.join(BENCH, "tools/analyze.py"),
         "--input", os.path.join(run_dir, "results.jsonl"),
         "--output-csv", dest, "--output-markdown", os.devnull],
        check=True, capture_output=True)


def load(path):
    return {int(r["n_preview_items"]): r
            for r in csv.DictReader(io.open(path, encoding="utf-8"))}


def mw_exact(a, b):
    """p bilateral exato do teste de postos, amostras pequenas."""
    n, m = len(a), len(b)
    pooled = sorted(a + b)
    rank = {v: i + 1 for i, v in enumerate(pooled)}
    obs = sum(rank[v] for v in a)
    mean = n * (n + m + 1) / 2.0
    total = extreme = 0
    for combo in itertools.combinations(pooled, n):
        total += 1
        if abs(sum(rank[v] for v in combo) - mean) >= abs(obs - mean):
            extreme += 1
    return extreme / total


def fmt(v):
    return ("%.0f" if abs(v) > 5000 else "%.2f") % v


def main():
    ledger = json.load(io.open(LEDGER, encoding="utf-8"))
    if ledger.get("error"):
        print("ATENCAO: driver abortou — %s\n" % ledger["error"])

    configs = list(ledger["runs"].keys())
    labels = ledger.get("labels", {})

    data = {}
    for cfg in configs:
        data[cfg] = []
        for i, name in enumerate(ledger["runs"][cfg], 1):
            dest = os.path.join(HERE, "%s%d-summary.csv" % (cfg, i))
            if not os.path.exists(dest):
                summarise(os.path.join(OUT, name), dest)
            data[cfg].append(load(dest))

    print("ordem executada: %s" % " ".join(ledger["order"]))
    if ledger.get("design"):
        print("desenho: %s" % ledger["design"])
    print("repeticoes: " + ", ".join(
        "%s=%d" % (c, len(data[c])) for c in configs))
    n = min(len(data[c]) for c in configs)
    if n >= 2:
        print("p minimo alcancavel (%dx%d, separacao total): %.4f"
              % (n, n, 2 / math.comb(2 * n, n)))

    # Cada configuracao contra a primeira (o controle), e as intermediarias
    # contra a ultima (a combinacao).
    pairs = [(configs[0], c) for c in configs[1:]]
    pairs.extend((c, configs[-1]) for c in configs[1:-1])

    def series(cfg, fn):
        return [fn(d) for d in data[cfg]]

    metrics = [
        ("delta p95 do preview (ms) = p95(N=10000) - p95(N=0)",
         lambda d: float(d[10000]["roundtrip_p95"]) - float(d[0]["roundtrip_p95"])),
        ("p95 absoluto N=10000 (ms)", lambda d: float(d[10000]["roundtrip_p95"])),
        ("p50 absoluto N=10000 (ms)", lambda d: float(d[10000]["roundtrip_p50"])),
        ("p95 do baseline N=0 (ms)", lambda d: float(d[0]["roundtrip_p95"])),
        ("engine took p95 N=10000 (ms, inteiro)",
         lambda d: float(d[10000]["took_p95"] or 0)),
        ("request N=10000 (bytes)", lambda d: float(d[10000]["request_bytes"])),
    ]

    for title, fn in metrics:
        print()
        print("=" * 78)
        print(title)
        print("=" * 78)
        meds = {}
        for cfg in configs:
            vals = sorted(series(cfg, fn))
            meds[cfg] = statistics.median(vals)
            spread = ((max(vals) - min(vals)) / meds[cfg] * 100
                      if meds[cfg] else 0)
            print("  %-3s %-44s %s" % (
                cfg, labels.get(cfg, "")[:44], " ".join(fmt(v) for v in vals)))
            print("  %-3s %-44s mediana %s   amplitude %.1f%%"
                  % ("", "", fmt(meds[cfg]), spread))
        print("  " + "-" * 74)
        for x, y in pairs:
            d = (meds[y] - meds[x]) / meds[x] * 100 if meds[x] else 0
            p = (mw_exact(series(x, fn), series(y, fn))
                 if n >= 2 else float("nan"))
            verdict = ("SIGNIFICATIVO" if p < 0.05 else
                       "sugestivo" if p < 0.10 else "sem efeito detectavel")
            print("    %s -> %s: %+7.1f%%   p=%.4f  (%s)" % (x, y, d, p, verdict))

    print()
    print("=" * 78)
    print("split do delta p95: engine (took) vs Liferay + rede, por execucao")
    print("=" * 78)
    for cfg in configs:
        for i, d in enumerate(data[cfg], 1):
            delta = float(d[10000]["roundtrip_p95"]) - float(d[0]["roundtrip_p95"])
            engine = float(d[10000]["took_p95"] or 0) - float(d[0]["took_p95"] or 0)
            if delta <= 0:
                continue
            print("  %s%d  delta %6.2f  engine %+6.2f (%2.0f%%)  liferay+rede %+6.2f (%2.0f%%)"
                  % (cfg, i, delta, engine, 100 * engine / delta,
                     delta - engine, 100 * (delta - engine) / delta))

    print()
    print("=" * 78)
    print("sanidade")
    print("=" * 78)
    hits = set()
    for cfg in configs:
        for d in data[cfg]:
            hits.add((cfg, d[0]["hits_total"], d[10000]["hits_total"]))
    for h in sorted(hits):
        print("  config %s: hits baseline=%s  N=10000=%s" % h)
    return 0


if __name__ == "__main__":
    sys.exit(main())
