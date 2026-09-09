#!/usr/bin/env python3
"""LPD-104270 — interleaved A/B: A, B, A, B, ... five measured runs per side.

Each switch is: checkout the contributor for that side, deploy journal-service,
flip preview.rewrite, run one discarded run, then one measured run. The
discarded run doubles as the verification that the deployed build matches the
flag: preview:benchmark aborts before creating a run directory when the swap
probes disagree with preview.rewrite, so a stale hot deploy cannot be measured
under the wrong label.
"""

import glob
import io
import json
import os
import re
import socket
import subprocess
import sys
import time

PORTAL = "/home/rodrigo/development/search-2/liferay-portal"
JOURNAL = os.path.join(PORTAL, "modules/apps/journal/journal-service")
GRADLEW = os.path.join(PORTAL, "gradlew")
CONTRIB = (
    "modules/apps/journal/journal-service/src/main/java/com/liferay/journal"
    "/internal/search/spi/model/query/contributor"
    "/JournalArticleModelPreFilterContributor.java"
)
DEPLOYED_JAR = (
    "/home/rodrigo/development/search-2/bundles/osgi/portal"
    "/com.liferay.journal.service.jar"
)
CLASS_IN_JAR = (
    "com/liferay/journal/internal/search/spi/model/query/contributor"
    "/JournalArticleModelPreFilterContributor.class"
)
PROPS = "/home/rodrigo/development/search-2/bundles/preview-benchmark.properties"
OUT = "/home/rodrigo/development/search-2/bundles/preview-benchmark"
LEDGER = os.path.dirname(os.path.abspath(__file__)) + "/interleaved2-ledger.json"

CYCLES = 5
EXPECTED = 1440

# side -> (git ref for the contributor, preview.rewrite value)
SIDES = {"A": ("LPD-104270_baseline", "false"), "B": ("LPD-98298", "true")}


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def run(cmd, cwd=None, timeout=900):
    p = subprocess.run(
        cmd, cwd=cwd, timeout=timeout, capture_output=True, text=True
    )
    return p.returncode, p.stdout + p.stderr


def deployed_side():
    """Which build the bundle directory currently holds."""
    p = subprocess.run(
        ["unzip", "-p", DEPLOYED_JAR, CLASS_IN_JAR], capture_output=True
    )
    if p.returncode != 0:
        return "?"
    return b"PreviewableResolverUtil" in p.stdout and "B" or "A"


def snapshot():
    """Host load, Elasticsearch heap and Tomcat metaspace, right now.

    The first 5x5 hit a 30% step that turned out to be transient host load, and
    nothing in the dataset could show that. Recording the environment per run
    makes the next degradation attributable from the data alone.
    """
    snap = {}

    try:
        with io.open("/proc/loadavg", encoding="utf-8") as f:
            snap["load1"] = float(f.read().split()[0])
    except Exception:
        pass

    try:
        import urllib.request
        with urllib.request.urlopen(
            "http://localhost:9200/_nodes/stats/jvm", timeout=5
        ) as r:
            node = list(json.load(r)["nodes"].values())[0]["jvm"]
            snap["es_heap_pct"] = node["mem"]["heap_used_percent"]
            young = node["gc"]["collectors"].get("young", {})
            snap["es_ygc"] = young.get("collection_count")
            snap["es_ygct"] = round(
                young.get("collection_time_in_millis", 0) / 1000.0, 1
            )
    except Exception:
        pass

    try:
        pid = subprocess.run(
            ["pgrep", "-f", "org.apache.catalina.startup.Bootstrap"],
            capture_output=True, text=True
        ).stdout.split()[0]
        out = subprocess.run(
            ["jstat", "-gc", pid], capture_output=True, text=True
        ).stdout.split("\n")
        head, vals = out[0].split(), out[1].split()
        row = dict(zip(head, vals))
        snap["ms_used_mb"] = round(float(row["MU"]) / 1024)
        snap["ms_cap_mb"] = round(float(row["MC"]) / 1024)
        snap["tc_ygc"] = int(float(row["YGC"]))
        snap["tc_ygct"] = round(float(row["YGCT"]), 1)
    except Exception:
        pass

    return snap


def switch(side):
    ref, rewrite = SIDES[side]

    code, out = run(["git", "checkout", ref, "--", CONTRIB], cwd=PORTAL)
    if code != 0:
        raise RuntimeError("git checkout falhou para o lado %s:\n%s" % (side, out))

    code, out = run(
        [GRADLEW, "deploy", "--console=plain"], cwd=JOURNAL, timeout=1800
    )
    if code != 0:
        raise RuntimeError("deploy falhou para o lado %s:\n%s" % (side, out[-3000:]))

    # Give the OSGi directory watcher time to refresh the bundle.
    time.sleep(25)

    got = deployed_side()
    if got != side:
        raise RuntimeError(
            "jar deployado é do lado %s, esperado %s" % (got, side)
        )

    s = io.open(PROPS, encoding="utf-8").read()
    s2 = re.sub(r"(?m)^preview\.rewrite=.*$", "preview.rewrite=" + rewrite, s)
    if s2 == s and ("preview.rewrite=" + rewrite) not in s:
        raise RuntimeError("não consegui ajustar preview.rewrite em %s" % PROPS)
    io.open(PROPS, "w", encoding="utf-8").write(s2)

    log("lado %s: contributor de %s, jar conferido, preview.rewrite=%s"
        % (side, ref, rewrite))


def dirs():
    return set(glob.glob(os.path.join(OUT, "*")))


def count(path):
    try:
        with io.open(path, encoding="utf-8") as f:
            return sum(1 for l in f if l.strip())
    except OSError:
        return 0


def benchmark(label):
    """One preview:benchmark. Returns the run dir, or raises on probe failure."""
    before = dirs()

    s = socket.create_connection(("127.0.0.1", 11311), timeout=30)
    s.settimeout(5)
    time.sleep(1.5)
    try:
        s.recv(65536)
    except Exception:
        pass
    s.sendall(b"preview:benchmark\n")

    start = time.time()
    run_dir = None
    chatter = b""

    while time.time() - start < 120:
        new = dirs() - before
        if new:
            run_dir = new.pop()
            break
        try:
            chatter += s.recv(65536)
        except Exception:
            pass
        time.sleep(2)

    if run_dir is None:
        s.close()
        raise RuntimeError(
            "%s: nenhum diretório de run em 120s — as sondas provavelmente "
            "abortaram.\nSaída do Gogo:\n%s"
            % (label, chatter.decode("utf-8", "replace")[-2000:])
        )

    results = os.path.join(run_dir, "results.jsonl")
    stable, last = 0, -1
    while time.time() - start < 900:
        n = count(results)
        stable = stable + 1 if n == last else 0
        last = n
        if n >= EXPECTED and stable >= 3:
            break
        time.sleep(5)

    s.close()

    n = count(results)
    queries = len(glob.glob(os.path.join(run_dir, "queries", "*.json")))
    if n != EXPECTED or queries != 12:
        raise RuntimeError(
            "%s: incompleto — %d/%d linhas, %d/12 queries"
            % (label, n, EXPECTED, queries)
        )

    rows = [json.loads(l) for l in io.open(results, encoding="utf-8") if l.strip()]
    last_row = rows[-1]
    log("  %s: %s  %d linhas  %.0fs  rewrite=%s sweep=%s"
        % (label, os.path.basename(run_dir), n, time.time() - start,
           last_row["preview_rewrite"], last_row.get("sweep")))
    return run_dir, last_row


def main():
    ledger = {"order": [], "runs": {"A": [], "B": []}, "discarded": [], "env": []}
    log("estado inicial: jar deployado é do lado %s" % deployed_side())
    log("ambiente inicial: %s" % json.dumps(snapshot(), sort_keys=True))

    # The very first measured run of the last sequence was ~14% slow on the
    # keyword cells alone: _globalWarmup only exercises match_all at size 20, so
    # the keyword path is cold and one discarded run did not fully absorb it.
    # Warm it before the sequence starts rather than spending cycle 1 on it.
    log("=== aquecimento inicial (fora da sequência) ===")
    switch("A")
    benchmark("aquecimento 1")
    benchmark("aquecimento 2")

    try:
        for cycle in range(1, CYCLES + 1):
            for side in ("A", "B"):
                log("=== ciclo %d/%d, lado %s ===" % (cycle, CYCLES, side))
                switch(side)

                d, _ = benchmark("descarte %s%d" % (side, cycle))
                ledger["discarded"].append(os.path.basename(d))

                before = snapshot()
                d, row = benchmark("MEDIDO  %s%d" % (side, cycle))
                after = snapshot()
                if bool(row["preview_rewrite"]) != (side == "B"):
                    raise RuntimeError(
                        "rótulo inconsistente: lado %s gravou preview_rewrite=%s"
                        % (side, row["preview_rewrite"])
                    )
                ledger["runs"][side].append(os.path.basename(d))
                ledger["order"].append(side)
                ledger["env"].append({
                    "run": "%s%d" % (side, cycle),
                    "dir": os.path.basename(d),
                    "before": before,
                    "after": after,
                })
                log("    ambiente: load %s->%s  es_heap %s%%->%s%%  metaspace %s/%s MB"
                    % (before.get("load1"), after.get("load1"),
                       before.get("es_heap_pct"), after.get("es_heap_pct"),
                       after.get("ms_used_mb"), after.get("ms_cap_mb")))

                io.open(LEDGER, "w", encoding="utf-8").write(
                    json.dumps(ledger, indent=2)
                )
    except Exception as exception:
        ledger["error"] = str(exception)
        io.open(LEDGER, "w", encoding="utf-8").write(json.dumps(ledger, indent=2))
        log("ABORTADO: %s" % exception)
        log("progresso preservado em %s" % LEDGER)
        return 1

    log("=== concluído: %d medidos por lado, ordem %s ==="
        % (CYCLES, "".join(ledger["order"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
