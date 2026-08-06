#!/usr/bin/env bash
#
# LPD-98298 PoC 2 — discover live/draft version pairs straight from the search
# engine, so PoC 2 needs nothing from PoC 1.
#
# WHY THIS EXISTS
# ---------------
# PoC 2 used to read PoC 1's `swapmap-n<N>.json` dumps. That made it a second
# stage of a pipeline rather than an independent execution of the same scenarios,
# and it meant PoC 2 could not run on its own branch against its own portal
# build. This script removes that dependency.
#
# HOW IT WORKS
# ------------
# With indexAllArticleVersionsEnabled=true every JournalArticle version is its
# own document, and three indexed fields are enough to reconstruct the pairs:
#
#   uid           "<className>_PORTLET_<id_>"  -- the per-version PK is the suffix
#                 (UIDFactoryImpl: modelClassName + "_PORTLET_" + primaryKeyObj)
#   entryClassPK  resourcePrimKey -- SHARED by every version of one article
#   status/head   0 + head=true => the approved live version; 2 => a draft
#
# So: group documents by entryClassPK, keep groups holding both a live and a
# draft version, and emit {liveVersionPK: draftVersionPK} -- exactly the shape
# JournalArticleModelPreFilterContributor consumes.
#
# Works identically against Elasticsearch 8.x and OpenSearch 2.x.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMON="${HERE}/common"

# shellcheck source=common/engine-stats.sh
source "${COMMON}/engine-stats.sh"

: "${ENGINE_INDEX:=liferay-*}"
: "${ENTRY_CLASS_NAME:=com.liferay.journal.model.JournalArticle}"
: "${OUT_DIR:?Set OUT_DIR to where the swapmap files should be written}"
: "${N_VALUES:=1 10 100 500 1000}"

mkdir -p "${OUT_DIR}"

echo "Discovering live/draft pairs from ${ENGINE_URL} (index ${ENGINE_INDEX})"

_auth=()
if [ -n "${ENGINE_USER}" ]; then
	_auth=(-u "${ENGINE_USER}:${ENGINE_PASSWORD}")
fi

# Paginate with search_after: a single request caps at index.max_result_window
# (10k by default), and 10,000 pairs needs at least 20,000 documents.
python3 - "${ENGINE_URL}" "${ENGINE_INDEX}" "${ENTRY_CLASS_NAME}" "${OUT_DIR}" \
	"${N_VALUES}" "${ENGINE_USER:-}" "${ENGINE_PASSWORD:-}" <<'PY'
import json
import subprocess
import sys

engine_url, index, entry_class_name, out_dir, n_values_raw = sys.argv[1:6]
user, password = sys.argv[6], sys.argv[7]

n_values = sorted({int(v) for v in n_values_raw.split()})
max_n = max(n_values)

PAGE = 5000


def engine_post(path, body):
    cmd = ["curl", "-s", "--max-time", "120", "-H", "Content-Type: application/json",
           "-X", "POST", "%s/%s" % (engine_url.rstrip("/"), path.lstrip("/")),
           "-d", json.dumps(body)]

    if user:
        cmd[2:2] = ["-u", "%s:%s" % (user, password)]

    out = subprocess.run(cmd, capture_output=True, text=True).stdout

    try:
        return json.loads(out)
    except Exception:
        sys.exit("engine did not return JSON for %s:\n%s" % (path, out[:500]))


# One pass over the JournalArticle documents, grouped by resourcePrimKey.
groups = {}
search_after = None
scanned = 0

while True:
    body = {
        "_source": ["uid", "status", "head", "entryClassPK", "groupId"],
        "query": {
            "bool": {
                "filter": [{"term": {"entryClassName": entry_class_name}}]
            }
        },
        "size": PAGE,
        # A stable, unique tiebreaker; uid is a keyword so it sorts.
        "sort": [{"uid": "asc"}],
    }

    if search_after is not None:
        body["search_after"] = search_after

    response = engine_post("%s/_search" % index, body)

    if "error" in response:
        sys.exit("engine error: %s" % json.dumps(response["error"])[:500])

    hits = response.get("hits", {}).get("hits", [])

    if not hits:
        break

    for hit in hits:
        source = hit.get("_source", {})
        uid = source.get("uid")
        entry_class_pk = source.get("entryClassPK")

        if not uid or entry_class_pk is None:
            continue

        marker = "_PORTLET_"

        if marker not in uid:
            continue

        # Everything after the LAST _PORTLET_ is the per-version primary key.
        version_pk = uid.rsplit(marker, 1)[1]

        if not version_pk.isdigit():
            continue

        def scalar(value):
            # Liferay writes some keywords as single-element arrays.
            if isinstance(value, list):
                return value[0] if value else None
            return value

        status = scalar(source.get("status"))
        head = scalar(source.get("head"))
        group_id = scalar(source.get("groupId"))

        group = groups.setdefault(
            str(entry_class_pk), {"live": None, "draft": None, "groupId": group_id})

        is_head = str(head).lower() == "true"

        if str(status) == "0" and is_head:
            group["live"] = version_pk
        elif str(status) == "2":
            # Any draft will do; prefer the highest version pk for determinism.
            if group["draft"] is None or int(version_pk) > int(group["draft"]):
                group["draft"] = version_pk

    scanned += len(hits)
    search_after = hits[-1].get("sort")

    if search_after is None:
        break

    print("  scanned %d documents, %d candidate articles" % (scanned, len(groups)))

    # Stop early once there are comfortably enough complete pairs.
    complete = sum(
        1 for g in groups.values() if g["live"] and g["draft"])

    if complete >= max_n:
        break

pairs = [
    (g["live"], g["draft"], g["groupId"])
    for g in groups.values()
    if g["live"] and g["draft"]
]

# Deterministic order so repeated discoveries produce identical swap maps --
# otherwise two runs would benchmark different term sets and not be comparable.
pairs.sort(key=lambda p: int(p[0]))

print("Scanned %d documents; found %d complete live/draft pairs"
      % (scanned, len(pairs)))

if not pairs:
    sys.exit(
        "No live/draft pairs found.\n"
        "  - is indexAllArticleVersionsEnabled=true?\n"
        "  - does the corpus actually contain draft versions?\n"
        "  - is %s the right index pattern?" % index)

group_ids = {p[2] for p in pairs if p[2] is not None}

discovery = {
    "engine_url": engine_url,
    "engine_index": index,
    "entry_class_name": entry_class_name,
    "documents_scanned": scanned,
    "pairs_found": len(pairs),
    "group_ids": sorted(str(g) for g in group_ids),
    "portal_scope": str(sorted(group_ids)[0]) if group_ids else "",
}

with open("%s/discovery.json" % out_dir, "w") as f:
    json.dump(discovery, f, indent=2)

for n in n_values:
    if n > len(pairs):
        print("  SKIP n=%d: only %d pairs available" % (n, len(pairs)))
        continue

    swaps = {live: int(draft) for live, draft, _ in pairs[:n]}

    path = "%s/swapmap-n%d.json" % (out_dir, n)

    with open(path, "w") as f:
        json.dump({entry_class_name: swaps}, f)

    print("  wrote %s (%d entries)" % (path, n))

print("portal_scope (first groupId): %s" % discovery["portal_scope"])
PY

echo "Discovery complete: ${OUT_DIR}"
