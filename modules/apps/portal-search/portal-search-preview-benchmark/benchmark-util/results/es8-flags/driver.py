#!/usr/bin/env python3
"""LPD-104270 — variacoes atrás de flags de runtime, intercaladas sem redeploy.

C   : controle — chave numerica journalArticleVersionId (text), JSON string
CL  : C + filtro no campo long journalArticleVersionId_sortable   (C')
CE  : C + valores numericos no JSON do request                     (E)
CLE : C + ambos

As flags sao System properties preview.benchmark.* ligadas pelo comando
preview:flag do harness e gravadas em cada linha do results.jsonl. A ordem
dentro de cada ciclo rotaciona, para que nenhuma configuracao ocupe sempre a
mesma posicao relativa a deriva do host.
"""

import glob
import io
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gogo import run as gogo_run

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = "/home/rodrigo/development/search-2/bundles/preview-benchmark"
LEDGER = os.path.join(HERE, "flags-ledger.json")

# config -> (numeric.terms, sortable.field)
CONFIGS = {
    "C": ("off", "off"),
    "CL": ("off", "true"),
    "CE": ("true", "off"),
    "CLE": ("true", "true"),
}
ORDER = ["C", "CL", "CE", "CLE"]
LABELS = {
    "C": "controle: chave numerica (text), JSON string",
    "CL": "C + campo long _sortable",
    "CE": "C + JSON numerico",
    "CLE": "C + campo long + JSON numerico",
}

CYCLES = 3
EXPECTED = 240  # 2 celulas x (20 warmup + 100 measure)


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def snapshot():
    snap = {}
    try:
        with io.open("/proc/loadavg", encoding="utf-8") as f:
            snap["load1"] = float(f.read().split()[0])
        with io.open("/proc/meminfo", encoding="utf-8") as f:
            for line in f:
                if line.startswith(("MemAvailable", "SwapFree")):
                    k, v = line.split(":")
                    snap[k] = int(v.split()[0]) // 1024
    except Exception:
        pass
    return snap


def switch(target):
    numeric, sortable = CONFIGS[target]
    gogo_run("preview:flag numeric.terms %s" % numeric, settle=2, read_for=5)
    gogo_run("preview:flag sortable.field %s" % sortable, settle=2, read_for=5)
    gogo_run("preview:flag terms.key.type numeric", settle=2, read_for=5)
    log("  config %s ativa (numeric.terms=%s sortable.field=%s)" % (
        target, numeric, sortable))


def dirs():
    return set(glob.glob(os.path.join(OUT, "*")))


def count(path):
    try:
        with io.open(path, encoding="utf-8") as f:
            return sum(1 for line in f if line.strip())
    except OSError:
        return 0


def benchmark(label):
    before = dirs()
    text = gogo_run("preview:benchmark", read_for=420)

    new = dirs() - before
    if not new:
        raise RuntimeError(
            "%s: nenhum diretorio de run.\nSaida do Gogo:\n%s"
            % (label, text[-1200:]))

    run_dir = new.pop()
    results = os.path.join(run_dir, "results.jsonl")

    deadline = time.time() + 300
    stable, last = 0, -1
    while time.time() < deadline:
        n = count(results)
        stable = stable + 1 if n == last else 0
        last = n
        if n >= EXPECTED and stable >= 2:
            break
        time.sleep(4)

    n = count(results)
    if n != EXPECTED:
        raise RuntimeError("%s: incompleto — %d/%d linhas" % (label, n, EXPECTED))

    # As flags gravadas devem bater com a configuracao pedida.
    with io.open(results, encoding="utf-8") as f:
        flags = json.loads(f.readline())["flags"]
    log("  %s: %s (%d linhas, flags=%s)" % (
        label, os.path.basename(run_dir), n, flags))
    return run_dir, flags


def main():
    ledger = {
        "order": [], "runs": {c: [] for c in ORDER}, "labels": LABELS,
        "design": ("intercalado com rotacao por ciclo, flags de runtime, "
                   "sem redeploy; 1 descarte por troca; %d ciclos" % CYCLES),
        "env": [],
    }

    # Retoma um ledger interrompido: a ordem e deterministica, entao basta
    # pular as (config, ciclo) que ja tem medicao.
    if os.path.exists(LEDGER):
        previous = json.load(io.open(LEDGER, encoding="utf-8"))
        if not previous.get("error"):
            ledger.update(previous)
            ledger.pop("error", None)
            log("retomando: %s" % " ".join(ledger["order"]))

    try:
        for cycle in range(1, CYCLES + 1):
            rotated = ORDER[cycle - 1:] + ORDER[:cycle - 1]
            for config in rotated:
                if len(ledger["runs"][config]) >= cycle:
                    continue
                log("=== ciclo %d/%d, config %s ===" % (cycle, CYCLES, config))
                switch(config)

                benchmark("descarte %s%d" % (config, cycle))

                before = snapshot()
                run_dir, flags = benchmark("MEDIDO  %s%d" % (config, cycle))
                after = snapshot()

                ledger["runs"][config].append(os.path.basename(run_dir))
                ledger["order"].append(config)
                ledger["env"].append(
                    {"run": "%s%d" % (config, cycle), "flags": flags,
                     "before": before, "after": after})

                io.open(LEDGER, "w", encoding="utf-8").write(
                    json.dumps(ledger, indent=2))
    except Exception as exception:
        ledger["error"] = str(exception)
        io.open(LEDGER, "w", encoding="utf-8").write(json.dumps(ledger, indent=2))
        log("ABORTADO: %s" % exception)
        return 1
    finally:
        # Deixa o portal como o controle.
        try:
            switch("C")
        except Exception:
            pass

    log("=== concluido: %s ===" % " ".join(ledger["order"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
