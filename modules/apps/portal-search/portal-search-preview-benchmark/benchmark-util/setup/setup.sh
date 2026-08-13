#!/bin/bash
#
# LPD-98298 — one-command environment setup for the preview search benchmark.
#
# Usage:
#   ./setup.sh es          # set everything up for Elasticsearch 8.19 (localhost:9200)
#   ./setup.sh os          # set everything up for OpenSearch 2.19 (localhost:9202)
#   ./setup.sh check es    # read-only verification of the environment (exit 0/1)
#   ./setup.sh check os
#
# What a setup run does, in order (every step is idempotent):
#   1. MySQL      — reuses any MySQL already on 3306, else starts setup/mysql/
#   2. Engine     — docker compose up of setup/<engine>/ (analysis plugins baked in)
#   3. Bundle     — engine OSGi configs (../switch-engine.sh), jdbc.default.url →
#                   lportal_bench (with a one-time backup), framework properties,
#                   OpenSearch connector build when missing, benchmark module jar,
#                   preview-benchmark.properties (template + target=)
#
# Paths (prompted interactively when the variable is not set):
#   LIFERAY_BUNDLES_DIR      Liferay bundle (contains tomcat-*/, osgi/, ...)
#   LIFERAY_PORTAL_SRC_DIR   portal source (branch LPD-98298), needed to build
#                            the OpenSearch connector (default: sibling
#                            liferay-portal of the bundle's parent)
#
# Setup requires Tomcat to be STOPPED (check mode does not).
# After setup: seed the corpus (../seed/seed.sh), start Tomcat, run ONE full
# reindex, then run the benchmark — see runbook.md.

set -euo pipefail

SETUP_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCH_SCHEMA="lportal_bench"

# ---------------------------------------------------------------- arguments

MODE="setup"
ENGINE="${1:-}"

if [ "${1:-}" = "check" ]; then
	MODE="check"
	ENGINE="${2:-}"
fi

case "$ENGINE" in
	es)
		ENGINE_DIR="$SETUP_DIR/elasticsearch"
		CONTAINER="elasticsearch-lpd98298"
		PORT=9200
		TARGET="es8-remote"
		;;
	os)
		ENGINE_DIR="$SETUP_DIR/opensearch"
		CONTAINER="opensearch-lpd98298"
		PORT=9202
		TARGET="os2-remote"
		;;
	*)
		echo "Usage: $0 [es|os]  |  $0 check [es|os]" >&2
		exit 1
		;;
esac

# -------------------------------------------------------------------- paths

BUNDLES="${LIFERAY_BUNDLES_DIR:-}"

if [ -z "$BUNDLES" ]; then
	read -r -p "Liferay bundle path (e.g. /home/user/dev/search-2/bundles): " BUNDLES
fi

BUNDLES="${BUNDLES%/}"

if [ ! -d "$BUNDLES/osgi" ]; then
	echo "ERROR: '$BUNDLES' does not look like a Liferay bundle (no osgi/ directory)." >&2
	exit 1
fi

export LIFERAY_BUNDLES_DIR="$BUNDLES"

BASE="$(dirname "$BUNDLES")"
PORTAL="${LIFERAY_PORTAL_SRC_DIR:-$BASE/liferay-portal}"
PROPS="$BUNDLES/portal-setup-wizard.properties"
BENCH_PROPS="$BUNDLES/preview-benchmark.properties"

# MySQL container publishing 3306 (used by check and exported for the seed).
mysql_container() {
	docker ps --format '{{.Names}} {{.Ports}}' 2>/dev/null |
		awk '/:3306->/ {print $1; exit}'
}

# ---------------------------------------------------------------- check mode

if [ "$MODE" = "check" ]; then
	OK=0
	FAIL=0

	pass() { echo "  [OK]   $1"; OK=$((OK + 1)); }
	fail() { echo "  [FAIL] $1"; FAIL=$((FAIL + 1)); }
	info() { echo "  [--]   $1"; }

	echo "== MySQL =="

	MYSQL_NAME="$(mysql_container || true)"

	if [ -n "$MYSQL_NAME" ]; then
		pass "MySQL on 3306 (container: $MYSQL_NAME)"

		PAIRS=$(docker exec -i "$MYSQL_NAME" mysql -uroot -proot -N \
			"$BENCH_SCHEMA" -e "
			SELECT COUNT(*)
			FROM JournalArticle approved
			JOIN JournalArticle draft
				ON draft.resourcePrimKey = approved.resourcePrimKey
				AND draft.version = 2 AND draft.status = 2
			WHERE approved.version = 1 AND approved.status = 0" \
			2>/dev/null || true)

		if [ -n "$PAIRS" ] && [ "$PAIRS" -gt 0 ]; then
			pass "schema $BENCH_SCHEMA seeded ($PAIRS live/draft pairs)"
		else
			fail "schema $BENCH_SCHEMA missing or not seeded — run ../seed/seed.sh"
		fi
	else
		fail "no MySQL container publishing 3306 — run ./setup.sh $ENGINE"
	fi

	echo "== Engine ($ENGINE, localhost:$PORT) =="

	ENGINE_INFO=$(curl -s --max-time 3 "localhost:$PORT" 2>/dev/null || true)

	if echo "$ENGINE_INFO" | grep -q cluster_name; then
		VERSION=$(echo "$ENGINE_INFO" | grep -o '"number" *: *"[^"]*"' | head -1)
		pass "engine responding ($VERSION)"

		PLUGINS=$(curl -s --max-time 3 "localhost:$PORT/_cat/plugins" 2>/dev/null || true)

		for p in analysis-icu analysis-kuromoji analysis-smartcn analysis-stempel; do
			if echo "$PLUGINS" | grep -q "$p"; then
				pass "plugin $p"
			else
				fail "plugin $p missing (index creation will fail)"
			fi
		done
	else
		fail "engine not responding on localhost:$PORT — run ./setup.sh $ENGINE"
	fi

	echo "== Bundle ($BUNDLES) =="

	if [ "$ENGINE" = "os" ]; then
		if [ -f "$BUNDLES/osgi/configs/com.liferay.portal.bundle.blacklist.internal.configuration.BundleBlacklistConfiguration.config" ]; then
			pass "OSGi configs: OpenSearch set active (ES connectors blacklisted)"
		else
			fail "OSGi configs are not the OpenSearch set — run ./setup.sh os"
		fi

		if [ -f "$BUNDLES/osgi/portal/com.liferay.portal.search.opensearch2.impl.jar" ]; then
			pass "opensearch2 connector deployed"
		else
			fail "opensearch2 connector missing — run ./setup.sh os"
		fi
	else
		if [ ! -f "$BUNDLES/osgi/configs/com.liferay.portal.bundle.blacklist.internal.configuration.BundleBlacklistConfiguration.config" ]; then
			pass "OSGi configs: Elasticsearch set active"
		else
			fail "OSGi configs are not the Elasticsearch set — run ./setup.sh es"
		fi
	fi

	if grep -q "localhost/$BENCH_SCHEMA?" "$PROPS" 2>/dev/null; then
		pass "jdbc.default.url points at $BENCH_SCHEMA"
	else
		fail "jdbc.default.url does not point at $BENCH_SCHEMA — run ./setup.sh $ENGINE"
	fi

	for key in "org.osgi.framework.system.packages.extra" "osgi.console"; do
		if grep -q "$key" "$PROPS" 2>/dev/null; then
			pass "portal property: $key"
		else
			fail "portal property missing: $key — run ./setup.sh $ENGINE"
		fi
	done

	if [ -f "$BUNDLES/osgi/modules/com.liferay.portal.search.preview.benchmark.jar" ] ||
		[ -f "$BUNDLES/osgi/portal/com.liferay.portal.search.preview.benchmark.jar" ]; then
		pass "benchmark module jar installed"
	else
		fail "benchmark module jar missing — run ./setup.sh $ENGINE"
	fi

	if [ -f "$BENCH_PROPS" ] && grep -q "^target=$TARGET" "$BENCH_PROPS"; then
		pass "preview-benchmark.properties present (target=$TARGET)"
	else
		fail "preview-benchmark.properties missing or target != $TARGET"
	fi

	echo "== Runtime (informational) =="

	if ss -tln 2>/dev/null | grep -q ":8080 "; then
		info "Tomcat: running"

		if ss -tln 2>/dev/null | grep -q ":11311 "; then
			info "Gogo console: listening on 11311"
		else
			info "Gogo console: NOT listening (restart Tomcat after setup)"
		fi

		if echo "$ENGINE_INFO" | grep -q cluster_name && [ -n "${PAIRS:-}" ] && [ "${PAIRS:-0}" -gt 0 ]; then
			DOCS=$(curl -s --max-time 5 "localhost:$PORT/liferay-*/_count" \
				-H 'Content-Type: application/json' \
				-d '{"query":{"term":{"entryClassName":"com.liferay.journal.model.JournalArticle"}}}' \
				2>/dev/null | grep -o '"count":[0-9]*' | cut -d: -f2 || true)

			if [ -n "$DOCS" ] && [ "$DOCS" -ge $((PAIRS * 2)) ]; then
				info "index: $DOCS JournalArticle docs (>= 2 x $PAIRS pairs) — ready"
			else
				info "index: ${DOCS:-0} JournalArticle docs for $PAIRS pairs — run a full reindex"
			fi
		fi
	else
		info "Tomcat: stopped (start it after setup + seed, then reindex)"
	fi

	echo
	echo "$OK check(s) passed, $FAIL failed."

	[ "$FAIL" -eq 0 ]
	exit $?
fi

# ---------------------------------------------------------------- setup mode

# 1. Tomcat must be stopped: we are about to swap osgi/configs.
if ss -tln 2>/dev/null | grep -q ":8080 "; then
	echo "ERROR: Tomcat appears to be running (port 8080). Stop it first." >&2
	exit 1
fi

# 2. MySQL: reuse anything already on 3306, else start ours.
MYSQL_NAME="$(mysql_container || true)"

if [ -n "$MYSQL_NAME" ]; then
	echo ">>> MySQL already on 3306 (container: $MYSQL_NAME) — reusing"
else
	echo ">>> No MySQL on 3306 — starting setup/mysql"
	docker compose -f "$SETUP_DIR/mysql/docker-compose.yml" up -d

	echo -n ">>> Waiting for MySQL "
	until docker exec mysql mysqladmin ping -uroot -proot --silent 2>/dev/null; do
		echo -n "."
		sleep 3
	done
	echo " up"

	MYSQL_NAME="mysql"
fi

echo ">>> Seed hint: MYSQL_CONTAINER=$MYSQL_NAME ../seed/seed.sh [smoke|full]"

# 3. Engine container via docker compose. A leftover container with the same
#    name created outside compose (plain docker run) blocks compose — remove it.
if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
	COMPOSE_PROJECT=$(docker inspect "$CONTAINER" \
		--format '{{index .Config.Labels "com.docker.compose.project"}}')

	if [ -z "$COMPOSE_PROJECT" ]; then
		echo ">>> Removing non-compose leftover container $CONTAINER"
		docker rm -f "$CONTAINER" > /dev/null
	fi
fi

echo ">>> Starting $CONTAINER (docker compose up -d --build)"
docker compose -f "$ENGINE_DIR/docker-compose.yml" up -d --build

echo -n ">>> Waiting for the engine on localhost:$PORT "
until curl -s "localhost:$PORT" 2>/dev/null | grep -q cluster_name; do
	echo -n "."
	sleep 3
done
echo " up"

curl -s "localhost:$PORT" | grep -E '"number"|"distribution"' || true

# 4. Bundle: swap the engine OSGi configs. The BundleBlacklistConfiguration in
#    the OpenSearch set is what selects the engine (it stops the ES connectors).
"$SETUP_DIR/../switch-engine.sh" "$ENGINE"

# 5. Bundle: point the portal at the benchmark schema (one-time backup first).
if [ ! -f "$PROPS.lpd98298-backup" ]; then
	cp "$PROPS" "$PROPS.lpd98298-backup"
	echo ">>> Backup: $PROPS.lpd98298-backup"
fi

if ! grep -q "localhost/$BENCH_SCHEMA?" "$PROPS"; then
	sed -i "s|localhost/[A-Za-z0-9_]*?|localhost/$BENCH_SCHEMA?|" "$PROPS"
	echo ">>> jdbc.default.url now points at $BENCH_SCHEMA"
fi

# 6. Bundle properties. Both are one-time and harmless for either engine:
#    - system.packages.extra: required while the ES connectors are blacklisted
#      (search-tuning imports that package);
#    - osgi.console: Gogo telnet used to run preview:benchmark from the CLI.
#    Changing module.framework.properties.* requires cleaning osgi/state.
FRAMEWORK_PROPS_CHANGED=0

if ! grep -q "org.osgi.framework.system.packages.extra" "$PROPS"; then
	cat >> "$PROPS" <<'EOF'

# LPD-98298: required with the ES connectors blacklisted (search-tuning import)
module.framework.properties.org.osgi.framework.system.packages.extra=com.liferay.portal.search.elasticsearch8.settings
EOF
	FRAMEWORK_PROPS_CHANGED=1
fi

if ! grep -q "osgi.console" "$PROPS"; then
	cat >> "$PROPS" <<'EOF'

# LPD-98298: Gogo telnet console to run preview:benchmark from the CLI
module.framework.properties.osgi.console=localhost:11311
EOF
	FRAMEWORK_PROPS_CHANGED=1
fi

if [ "$FRAMEWORK_PROPS_CHANGED" = 1 ]; then
	echo ">>> module.framework properties added — cleaning osgi/state"
	rm -rf "$BUNDLES/osgi/state"
fi

# 7. OpenSearch only: the bundle does not ship the opensearch2 connector.
if [ "$ENGINE" = "os" ] &&
	[ ! -f "$BUNDLES/osgi/portal/com.liferay.portal.search.opensearch2.impl.jar" ]; then

	if [ ! -d "$PORTAL/modules" ]; then
		echo "ERROR: OpenSearch2 connector missing and portal source not found" >&2
		echo "at '$PORTAL' — set LIFERAY_PORTAL_SRC_DIR and rerun." >&2
		exit 1
	fi

	echo ">>> OpenSearch2 connector not deployed — building from source"
	(cd "$PORTAL/modules" && ../gradlew \
		:apps:portal-search-opensearch2:portal-search-opensearch2-api:deploy \
		:apps:portal-search-opensearch2:portal-search-opensearch2-impl:deploy \
		--console=plain -q)
fi

# 8. Benchmark module (deployable jar) — install if a dist copy exists and the
#    bundle does not have it yet.
DIST_JAR="$SETUP_DIR/../dist/com.liferay.portal.search.preview.benchmark.jar"

if [ -f "$DIST_JAR" ] &&
	[ ! -f "$BUNDLES/osgi/modules/com.liferay.portal.search.preview.benchmark.jar" ] &&
	[ ! -f "$BUNDLES/osgi/portal/com.liferay.portal.search.preview.benchmark.jar" ]; then

	echo ">>> Installing benchmark module into osgi/modules"
	cp "$DIST_JAR" "$BUNDLES/osgi/modules/"
fi

# 9. Benchmark config: seed from the template if absent, then set target=.
if [ ! -f "$BENCH_PROPS" ]; then
	cp "$SETUP_DIR/../preview-benchmark.properties.template" "$BENCH_PROPS"
	echo ">>> preview-benchmark.properties created from the template"
fi

sed -i "s/^target=.*/target=$TARGET/" "$BENCH_PROPS"
echo ">>> preview-benchmark.properties: target=$TARGET"

TOMCAT_DIR="$(ls -d "$BUNDLES"/tomcat-* 2>/dev/null | head -1 || true)"

cat <<EOF

>>> Environment ready for $ENGINE. Next steps (details in runbook.md):
  1. Seed the corpus (skip if already seeded — ./setup.sh check $ENGINE shows it):
     MYSQL_CONTAINER=$MYSQL_NAME ../seed/seed.sh full
  2. Start Tomcat: ${TOMCAT_DIR:-<bundle>/tomcat-<version>}/bin/startup.sh
  3. Run ONE full reindex (Control Panel > Search > Index Actions).
  4. Verify: ./setup.sh check $ENGINE
  5. Edit $BENCH_PROPS and run the benchmark (runbook.md section 7).
EOF
