#!/usr/bin/env python3
"""LPD-104270 — as tres configuracoes do rewrite de preview, intercaladas.

A: original    — chave Field.UID, contribuicao duplicada
B: dedup       — chave Field.UID, contribuicao unica
C: dedup+num   — chave numerica journalArticleVersionId, contribuicao unica

Intercalado (A B C A B C ...) porque medir em blocos ja provou, nesta mesma
investigacao, transformar deriva do ambiente em efeito aparente. Cada troca
deploya so os modulos que mudam, e e seguida de uma execucao descartada.

O campo journalArticleVersionId fica no documento nas tres configuracoes — ele
e aditivo e ja esta indexado, entao nenhuma troca exige reindex e o baseline e
o mesmo documento nos tres casos.
"""

import glob
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gogo import run as gogo_run

HERE = os.path.dirname(os.path.abspath(__file__))
PORTAL = "/home/rodrigo/development/search-2/liferay-portal"
VAR = "/tmp/claude-1000/variantes"
OUT = "/home/rodrigo/development/search-2/bundles/preview-benchmark"
LOGS = "/home/rodrigo/development/search-2/bundles/logs"
LEDGER = os.path.join(HERE, "fixes-ledger.json")

CONTRIB = os.path.join(
    PORTAL,
    "modules/apps/journal/journal-service/src/main/java/com/liferay/journal"
    "/internal/search/spi/model/query/contributor"
    "/JournalArticleModelPreFilterContributor.java")
HELPER = os.path.join(
    PORTAL,
    "modules/apps/portal-search/portal-search/src/main/java/com/liferay"
    "/portal/search/internal/indexer/helper/PreFilterContributorHelperImpl.java")

JOURNAL_DIR = os.path.join(PORTAL, "modules/apps/journal/journal-service")
SEARCH_DIR = os.path.join(PORTAL, "modules/apps/portal-search/portal-search")
GRADLEW = os.path.join(PORTAL, "gradlew")

# config -> (arquivo do contributor, arquivo do helper)
CONFIGS = {
    "A": ("contributor-uid.java", "helper-original.java"),
    "B": ("contributor-uid.java", "helper-dedup.java"),
    "C": ("contributor-numerico.java", "helper-dedup.java"),
}

CYCLES = 5
EXPECTED = 240  # 2 celulas x (20 warmup + 100 measure)


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def snapshot():
    snap = {}
    try:
        with io.open("/proc/loadavg", encoding="utf-8") as f:
            snap["load1"] = float(f.read().split()[0])
    except Exception:
        pass
    try:
        import urllib.request
        with urllib.request.urlopen(
                "http://localhost:9200/_nodes/stats/jvm", timeout=5) as r:
            jvm = list(json.load(r)["nodes"].values())[0]["jvm"]
            snap["es_heap_pct"] = jvm["mem"]["heap_used_percent"]
    except Exception:
        pass
    return snap


def deploy(module_dir, name):
    proc = subprocess.run(
        [GRADLEW, "deploy", "--console=plain"], cwd=module_dir,
        capture_output=True, text=True, timeout=2400)
    if proc.returncode != 0:
        raise RuntimeError(
            "deploy de %s falhou:\n%s" % (name, (proc.stdout + proc.stderr)[-2500:]))


def newest_log():
    return sorted(glob.glob(os.path.join(LOGS, "liferay.*.log")))[-1]


def wait_started(symbolic_name, since_size):
    """Espera o bundle reiniciar, lendo so o que foi escrito depois do deploy."""
    deadline = time.time() + 180
    path = newest_log()
    while time.time() < deadline:
        try:
            with io.open(path, encoding="utf-8", errors="replace") as f:
                f.seek(since_size)
                if "STARTED " + symbolic_name in f.read():
                    return True
        except OSError:
            pass
        time.sleep(3)
    return False


def switch(target, current):
    """Deploya apenas os modulos que mudam entre a config atual e a alvo."""
    contrib, helper = CONFIGS[target]
    changed = []

    if current is None or CONFIGS[current][0] != contrib:
        shutil.copy(os.path.join(VAR, contrib), CONTRIB)
        changed.append(("journal", JOURNAL_DIR, "com.liferay.journal.service"))

    if current is None or CONFIGS[current][1] != helper:
        shutil.copy(os.path.join(VAR, helper), HELPER)
        changed.append(("portal-search", SEARCH_DIR, "com.liferay.portal.search_"))

    for name, directory, symbolic in changed:
        size = os.path.getsize(newest_log())
        deploy(directory, name)
        wait_started(symbolic, size)

    if changed:
        # O registro do indexer chega bem depois do STARTED do bundle, e
        # enquanto ele nao chega a busca por modelIndexerClasses devolve zero
        # hits. Uma espera fixa nao serve: 45s ja se mostrou curto. Quem
        # espera de verdade e wait_ready(), logo abaixo.
        time.sleep(20)

    log("  config %s ativa (%s)" % (
        target, ", ".join(n for n, _, _ in changed) or "sem redeploy"))


def dirs():
    return set(glob.glob(os.path.join(OUT, "*")))


def count(path):
    try:
        with io.open(path, encoding="utf-8") as f:
            return sum(1 for line in f if line.strip())
    except OSError:
        return 0


def wait_ready(label, attempts=6, pause=60):
    """Repete a execucao descartada ate o indexer estar registrado.

    Depois de um redeploy do journal-service a busca devolve zero hits por um
    tempo variavel, e as sondas abortam com "Baseline returned 0 hits". Em vez
    de cravar uma espera, tenta ate passar.
    """
    for attempt in range(1, attempts + 1):
        try:
            return benchmark("%s (tentativa %d)" % (label, attempt))
        except RuntimeError as exception:
            if "0 hits" not in str(exception) and "nenhum diretorio" not in str(
                    exception):
                raise
            if attempt == attempts:
                raise
            log("  indexer ainda nao pronto, nova tentativa em %ds" % pause)
            time.sleep(pause)


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

    log("  %s: %s (%d linhas)" % (label, os.path.basename(run_dir), n))
    return run_dir


def main():
    ledger = {"order": [], "runs": {"A": [], "B": [], "C": []}, "env": []}
    current = None

    try:
        for cycle in range(1, CYCLES + 1):
            for config in ("A", "B", "C"):
                log("=== ciclo %d/%d, config %s ===" % (cycle, CYCLES, config))
                switch(config, current)
                current = config

                wait_ready("descarte %s%d" % (config, cycle))

                before = snapshot()
                run_dir = benchmark("MEDIDO  %s%d" % (config, cycle))
                after = snapshot()

                ledger["runs"][config].append(os.path.basename(run_dir))
                ledger["order"].append(config)
                ledger["env"].append(
                    {"run": "%s%d" % (config, cycle), "before": before,
                     "after": after})

                io.open(LEDGER, "w", encoding="utf-8").write(
                    json.dumps(ledger, indent=2))
    except Exception as exception:
        ledger["error"] = str(exception)
        io.open(LEDGER, "w", encoding="utf-8").write(json.dumps(ledger, indent=2))
        log("ABORTADO: %s" % exception)
        return 1

    log("=== concluido: %s ===" % "".join(ledger["order"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
