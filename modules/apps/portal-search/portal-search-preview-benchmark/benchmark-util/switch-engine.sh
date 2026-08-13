#!/bin/bash
#
# LPD-98298 — switches the bundle's search engine (Elasticsearch 8 <-> OpenSearch 2).
# Usage: ./switch-engine.sh [es|os]
# Requires Tomcat to be STOPPED. After starting: full reindex before benchmarking.
#
# Bundle path: taken from $LIFERAY_BUNDLES_DIR, or prompted interactively.
# The engine config sets are expected as siblings of the bundle directory:
# <parent>/elasticsearch_configs/configs and <parent>/opensearch_configs/configs.

set -euo pipefail

BUNDLES="${LIFERAY_BUNDLES_DIR:-}"

if [ -z "$BUNDLES" ]; then
	read -r -p "Liferay bundle path (e.g. /home/user/dev/search-2/bundles): " BUNDLES
fi

BUNDLES="${BUNDLES%/}"

if [ ! -d "$BUNDLES/osgi" ]; then
	echo "ERROR: '$BUNDLES' does not look like a Liferay bundle (no osgi/ directory)." >&2
	exit 1
fi

BASE="$(dirname "$BUNDLES")"
CONFIGS_DIR="$BUNDLES/osgi/configs"

case "${1:-}" in
	es) SRC="$BASE/elasticsearch_configs/configs" ;;
	os) SRC="$BASE/opensearch_configs/configs" ;;
	*)
		echo "Usage: $0 [es|os]" >&2
		exit 1
		;;
esac

if [ ! -d "$SRC" ]; then
	echo "ERROR: engine config set not found: $SRC" >&2
	exit 1
fi

if ss -tln 2>/dev/null | grep -q ":8080 "; then
	echo "ERROR: Tomcat appears to be running (port 8080). Stop it before switching engines." >&2
	exit 1
fi

# The OpenSearch config set includes the BundleBlacklistConfiguration (which
# stops the ES8 connector); the ES set does not — hence the directory is
# cleared first.
rm -f "$CONFIGS_DIR"/*.config
cp "$SRC"/*.config "$CONFIGS_DIR/"

echo ">>> Configs copied from $SRC"
ls -1 "$CONFIGS_DIR"

cat <<EOF

Next steps:
  1. Start the target engine (identical heap on both engines, e.g. 512m/512m
     in the Docker containers).
  2. Start Tomcat.
  3. Full reindex (Control Panel > Search > Index Actions) — one reindex at a
     time; never overlap reindexes.
  4. Set target= in $BUNDLES/preview-benchmark.properties (es8-remote/os2-remote).
EOF
