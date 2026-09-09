#!/usr/bin/env python3
"""Frames mais quentes (self time) de um dump de jdk.ExecutionSample.

Uso: python3 topframes.py A-samples.txt.gz [N]

O dump vem de `jfr print --events jdk.ExecutionSample <gravacao>.jfr`. Cada
amostra e a pilha de uma thread num instante; o metodo no topo e o que estava
executando. O script escolhe a thread com mais amostras cujas pilhas passam
pela busca do portal e, para os N metodos mais frequentes no topo, mostra a
fracao das amostras dessa thread e os chamadores mais comuns.

Isto reproduz a lista "frames mais quentes" da Pergunta 2 do dossie. A tabela
por subsistema (58,3% serializacao etc.) usou um classificador por "primeiro
dono reconhecido na pilha" cujas regras nao foram preservadas; os percentuais
dela devem ser lidos como a rodada registrada, nao como algo reproduzivel
daqui.
"""

import collections
import gzip
import io
import re
import sys

SEARCH_MARKERS = (
    "PreviewSearchBenchmark", "IndexSearcherHelper", "ElasticsearchIndexSearcher",
    "SearchSearchRequestExecutor",
)


def load(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rb") as f:
        text = f.read().decode("utf-8", errors="replace")
    # Os dumps deste diretorio listam cada evento duas vezes (mesmo instante,
    # mesma thread, mesma pilha); so a primeira ocorrencia conta.
    samples = []
    seen = set()
    for chunk in text.split("jdk.ExecutionSample {")[1:]:
        thread = re.search(r'sampledThread = "([^"]+)"', chunk)
        start = re.search(r"startTime = (\S+)", chunk)
        if "stackTrace = [" not in chunk:
            continue
        stack = chunk.split("stackTrace = [", 1)[1]
        key = (thread.group(1) if thread else "?",
               start.group(1) if start else "?", stack.strip())
        if key in seen:
            continue
        seen.add(key)
        frames = [
            line.strip().split(" line:")[0]
            for line in stack.splitlines()
            if line.strip() and not line.strip().startswith("]")
        ]
        samples.append((key[0], frames))
    return samples


def short(frame):
    method = frame.split("(")[0]
    parts = method.split(".")
    return ".".join(parts[-2:]) if len(parts) >= 2 else method


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 12

    samples = load(sys.argv[1])
    by_thread = collections.Counter(
        t for t, frames in samples
        if any(m in f for f in frames for m in SEARCH_MARKERS))
    if not by_thread:
        print("nenhuma amostra passa pela busca do portal")
        return 1
    thread = by_thread.most_common(1)[0][0]
    mine = [frames for t, frames in samples if t == thread]
    n = len(mine)

    print("amostras no total: %d | thread da busca: %s (%d amostras, 1 amostra = %.2f pp)"
          % (len(samples), thread, n, 100.0 / n))
    print()

    tops = collections.Counter(short(frames[0]) for frames in mine if frames)
    for frame, count in tops.most_common(top_n):
        callers = collections.Counter(
            " <- ".join(short(f) for f in frames[1:4])
            for frames in mine if frames and short(frames[0]) == frame)
        print("%5.1f%%  %3d  %s" % (100.0 * count / n, count, frame))
        for chain, k in callers.most_common(2):
            print("             %3d  %s" % (k, chain))
    return 0


if __name__ == "__main__":
    sys.exit(main())
