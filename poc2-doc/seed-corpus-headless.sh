#!/usr/bin/env bash
#
# LPD-98298 PoC 2 — create a corpus through the headless API, so PoC 2 needs
# nothing from PoC 1 and nothing from the database.
#
# ############################################################################
# THE PAIRS THIS CREATES ARE NOT SEMANTICALLY REAL PREVIEW SWAPS.
# Read the next paragraph before citing any number produced with them.
# ############################################################################
#
# A real preview swap is an approved/head version and a DRAFT VERSION OF THE SAME
# ARTICLE -- distinct per-version primary keys sharing one resourcePrimKey. The
# headless API cannot produce that:
#
#   * headless-delivery POST .../structured-contents        -> approved article
#   * headless-admin-content POST .../structured-contents/draft
#         -> calls _journalArticleService.addArticle, i.e. a NEW article in draft
#            state, not a draft version of an existing one
#   * no endpoint performs the second step the functional PoC does
#         (updateArticle with WorkflowConstants.ACTION_SAVE_DRAFT)
#
# Verified additionally: headless-delivery's StructuredContent DTO exposes
# resourcePrimKey as `id` (StructuredContentDTOConverter:164), not the per-version
# key the swap map needs -- which is exactly why the contributor keys on Field.UID.
#
# WHY IT IS STILL VALID FOR *THIS* MEASUREMENT
# --------------------------------------------
# The contributor never checks that from and to belong to the same article. Its
# filter is mechanical over UIDs:
#
#   (status=approved AND head AND NOT terms(fromUIDs)) OR terms(toUIDs)
#
# So pairing N approved articles with N independently created drafts yields the
# same query shape, the same filter sizes, and -- critically -- both sides match
# real indexed documents, so the postings union is not empty. For measuring HTTP
# latency and concurrency, which is PoC 2's job, that is equivalent.
#
# What it is NOT valid for: any claim about preview *behaviour*, correctness, or
# which document a user would see. PoC 1 covers that, against real pairs.
#
# State this caveat in the report. An undocumented shortcut becomes a wrong claim.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "${PORTAL_URL:=http://localhost:8080}"
: "${PORTAL_USER:=test@liferay.com}"
: "${PORTAL_PASSWORD:=test123}"
: "${SITE_ID:?Set SITE_ID to the site that will hold the corpus}"
: "${CONTENT_STRUCTURE_ID:=}"
: "${ENGINE_URL:=http://localhost:9200}"
: "${PAIRS:=100}"
: "${N_VALUES:=1 10 100}"
: "${OUT_DIR:=${HERE}/inputs}"
: "${MARKER:=lpd98298seed}"

mkdir -p "${OUT_DIR}"

_api() {
	curl -s -u "${PORTAL_USER}:${PORTAL_PASSWORD}" --max-time 60 \
		-H 'Content-Type: application/json' "$@"
}

# Resolve a content structure if one was not given. Any structure works; the
# benchmark never reads the content.
if [ -z "${CONTENT_STRUCTURE_ID}" ]; then
	CONTENT_STRUCTURE_ID="$(
		_api "${PORTAL_URL}/o/headless-delivery/v1.0/sites/${SITE_ID}/content-structures?pageSize=1" \
		| python3 -c 'import json,sys
items = json.load(sys.stdin).get("items", [])
print(items[0]["id"] if items else "")'
	)"
fi

if [ -z "${CONTENT_STRUCTURE_ID}" ]; then
	echo "ERROR: no content structure found in site ${SITE_ID}." >&2
	echo "Create one (e.g. Basic Web Content) or pass CONTENT_STRUCTURE_ID." >&2
	exit 1
fi

echo "portal    : ${PORTAL_URL}"
echo "site      : ${SITE_ID}"
echo "structure : ${CONTENT_STRUCTURE_ID}"
echo "pairs     : ${PAIRS}   marker: ${MARKER}"
echo

# ---------------------------------------------------------------------------
# 1. Create PAIRS approved articles and PAIRS draft articles
# ---------------------------------------------------------------------------

created_approved=0
created_draft=0

for i in $(seq 0 $((PAIRS - 1))); do
	body="$(python3 - "$CONTENT_STRUCTURE_ID" "$MARKER" "$i" <<-'PY'
		import json, sys
		structure_id, marker, i = sys.argv[1], sys.argv[2], sys.argv[3]
		print(json.dumps({
		    "contentStructureId": int(structure_id),
		    "title": "%s live %s" % (marker, i),
		    "contentFields": [],
		}))
	PY
	)"

	if _api -X POST \
		"${PORTAL_URL}/o/headless-delivery/v1.0/sites/${SITE_ID}/structured-contents" \
		-d "${body}" | grep -q '"id"'; then
		created_approved=$((created_approved + 1))
	fi

	body="${body/live/draft}"

	if _api -X POST \
		"${PORTAL_URL}/o/headless-admin-content/v1.0/sites/${SITE_ID}/structured-contents/draft" \
		-d "${body}" | grep -q '"id"'; then
		created_draft=$((created_draft + 1))
	fi

	if (( (i + 1) % 25 == 0 )); then
		echo "  created ${created_approved} approved / ${created_draft} drafts of ${PAIRS}"
	fi
done

echo "total: ${created_approved} approved, ${created_draft} drafts"

if [ "${created_approved}" -eq 0 ] || [ "${created_draft}" -eq 0 ]; then
	echo "ERROR: creation failed on one side. With one side empty the terms filter" >&2
	echo "matches nothing there and the measurement comes out understated." >&2
	exit 1
fi

# ---------------------------------------------------------------------------
# 2. Read the per-version UIDs back from the index
#
# The API does not return them (its `id` is resourcePrimKey), but the index does:
# the uid suffix IS the per-version primary key. Same extraction discover-pairs.sh
# uses.
# ---------------------------------------------------------------------------

echo
echo "waiting for indexing..."
sleep 10
curl -s --max-time 60 -X POST "${ENGINE_URL}/_refresh" > /dev/null

python3 - "$ENGINE_URL" "$SITE_ID" "$MARKER" "$OUT_DIR" "$N_VALUES" <<'PY'
import json
import subprocess
import sys

engine_url, site_id, marker, out_dir, n_values_raw = sys.argv[1:6]
n_values = sorted({int(v) for v in n_values_raw.split()})


def search(status):
    body = {
        "_source": ["uid", "status", "head", "title_en_US"],
        "query": {"bool": {"filter": [
            {"term": {"entryClassName":
                      "com.liferay.journal.model.JournalArticle"}},
            {"term": {"groupId": str(site_id)}},
            {"term": {"status": str(status)}},
        ]}},
        "size": 10000,
        "sort": [{"uid": "asc"}],
    }
    out = subprocess.run(
        ["curl", "-s", "--max-time", "120", "-H",
         "Content-Type: application/json", "-X", "POST",
         "%s/liferay-*/_search" % engine_url.rstrip("/"),
         "-d", json.dumps(body)],
        capture_output=True, text=True).stdout
    try:
        hits = json.loads(out)["hits"]["hits"]
    except Exception:
        sys.exit("engine did not return JSON:\n%s" % out[:400])

    pks = []
    for hit in hits:
        uid = hit.get("_source", {}).get("uid", "")
        if "_PORTLET_" not in uid:
            continue
        pk = uid.rsplit("_PORTLET_", 1)[1]
        if pk.isdigit():
            pks.append(pk)
    return sorted(pks, key=int)


live = search(0)     # WorkflowConstants.STATUS_APPROVED
draft = search(2)    # WorkflowConstants.STATUS_DRAFT

print("indexed: %d approved, %d drafts" % (len(live), len(draft)))

usable = min(len(live), len(draft))

if usable == 0:
    sys.exit(
        "ERROR: one side is empty in the index. Check that the articles were "
        "indexed and that groupId %s is correct." % site_id)

discovery = {
    "engine_url": engine_url,
    "portal_scope": str(site_id),
    "approved_indexed": len(live),
    "drafts_indexed": len(draft),
    "pairs_usable": usable,
    "pairing": "ARBITRARY -- approved[i] paired with draft[i]; NOT versions of "
               "the same article. Valid for latency measurement only.",
}

with open("%s/discovery.json" % out_dir, "w") as f:
    json.dump(discovery, f, indent=2)

entry_class = "com.liferay.journal.model.JournalArticle"

for n in n_values:
    if n > usable:
        print("  SKIP n=%d: only %d usable pairs" % (n, usable))
        continue
    swaps = {live[i]: int(draft[i]) for i in range(n)}
    path = "%s/swapmap-n%d.json" % (out_dir, n)
    with open(path, "w") as f:
        json.dump({entry_class: swaps}, f)
    print("  wrote %s (%d entries)" % (path, n))

print("portal_scope: %s" % site_id)
PY

cat <<EOF

To REMOVE this corpus:

  curl -s -u ${PORTAL_USER}:*** -X DELETE \\
    "${PORTAL_URL}/o/headless-delivery/v1.0/structured-contents/<id>"   # one article

or, faster, drop the index documents and reindex from the Control Panel.
The articles titled "${MARKER} live N" / "${MARKER} draft N" are the ones this script created.
EOF
