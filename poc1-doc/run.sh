#!/usr/bin/env bash
#
# LPD-98298 PoC 1 — integration-test benchmark runner.
#
# Drives the five standard performance-test phases at RUN level. The Java class
# drives them again at CELL level (once per point in the matrix); the two layers
# are deliberate:
#
#   shell (this file)          Java (PreviewSearchBenchmarkTest)
#   -----------------------    ---------------------------------
#   1 prepare  environment  -> 1 prepare  corpus + index readiness
#   2 reset    engine stats -> 2 reset    thread-locals, heap, index assert
#   3 warm-up  (delegated)  -> 3 warm-up  unmeasured iterations per cell
#   4 measure  (delegated)  -> 4 measure  constant load per cell
#   5 collect  engine delta -> 5 collect  one JSONL row per iteration
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMON="${HERE}/common"

# shellcheck source=common/engine-stats.sh
source "${COMMON}/engine-stats.sh"

# shellcheck source=common/preflight.sh
source "${COMMON}/preflight.sh"

# ---------------------------------------------------------------------------
# Phase 0 — configuration
# ---------------------------------------------------------------------------

# poc1-doc/ lives at the root of the checkout, so the default is simply its
# parent. Override only if you moved this directory out of the repo.
: "${PORTAL_DIR:=$(cd "${HERE}/.." && pwd)}"
: "${ARTIFACTS_DIR:=${HERE}/artifacts}"
: "${RUN_ID:=poc1-$(date -u +%Y%m%dT%H%M%SZ)}"

: "${N_VALUES:=1,10,100,500,1000}"
: "${QUERY_VARIANTS:=match-all,keyword,faceted}"
: "${RESULT_SIZES:=20}"
: "${CONCURRENCY:=1}"
: "${WARMUP_ITERATIONS:=100}"
: "${MEASURE_ITERATIONS:=300}"
: "${MEASURE_DURATION_SECONDS:=30}"
: "${BACKGROUND_CORPUS_SIZE:=0}"
: "${COLD_MODE_ENABLED:=true}"
: "${BASELINE_ENABLED:=true}"

: "${TEST_CLASS:=com.liferay.journal.search.test.PreviewSearchBenchmarkTest}"
: "${GRADLE_PROJECT:=:apps:journal:journal-test}"

# The Gradle root is modules/, NOT the repository root: there is no
# settings.gradle at the top level, so invoking ./gradlew from ${PORTAL_DIR}
# fails with "does not contain a Gradle build". The wrapper lives at the repo
# root but must be run with modules/ as the working directory.
: "${GRADLE_DIR:=${PORTAL_DIR}/modules}"
: "${GRADLE_WRAPPER:=${PORTAL_DIR}/gradlew}"

RUN_DIR="${ARTIFACTS_DIR}/${RUN_ID}"
mkdir -p "${RUN_DIR}/queries"

RESULTS_FILE="${RUN_DIR}/results.jsonl"
MANIFEST_FILE="${RUN_DIR}/manifest.json"

echo "=== PHASE 0: CONFIGURATION ==="
echo "Run ID:    ${RUN_ID}"
echo "Artifacts: ${RUN_DIR}"

# ---------------------------------------------------------------------------
# Phase 1 — Preparation (environment)
# ---------------------------------------------------------------------------

echo
echo "=== PHASE 1: PREPARATION (environment) ==="

IFS='|' read -r ENGINE_VENDOR ENGINE_VERSION <<< "$(engine_info)"
IFS='|' read -r ENGINE_HEAP_MB ENGINE_CPUS <<< "$(engine_resources)"

if [ "${ENGINE_VENDOR}" = "unknown" ]; then
	echo "ERROR: no search engine reachable at ${ENGINE_URL}" >&2
	echo "Start it first, e.g.: ant -f build-test-elasticsearch8.xml start-elasticsearch" >&2
	exit 1
fi

echo "Engine:    ${ENGINE_VENDOR} ${ENGINE_VERSION}"
echo "Resources: heap=${ENGINE_HEAP_MB}MB cpus=${ENGINE_CPUS}"
echo "Docs:      $(engine_doc_count)"

# --- Preflight -------------------------------------------------------------
#
# Guards the failure mode that yields confidently wrong numbers instead of an
# error: measuring one engine while labelling the rows with another's version,
# heap and CPU. Fatal, because a fabricated "similar CPU-heap allocation" claim
# is worse than a failed run.

echo
echo "--- Preflight ---"

assert_engine_url_matches_portal || exit 1

# app.server.properties sets app.server.parent.dir=${project.dir}/../bundles, so
# the bundle is a SIBLING of the liferay-portal checkout, not a child. Getting
# this wrong makes assert_remote_mode silently find no OSGi config and report
# "cannot confirm remote mode" for the wrong reason.
: "${LIFERAY_HOME:=${PORTAL_DIR}/../bundles}"

if [ ! -d "${LIFERAY_HOME}/osgi" ]; then
	echo "NOTE: no osgi/ under ${LIFERAY_HOME}." >&2
	echo "Set LIFERAY_HOME explicitly if your bundle lives elsewhere." >&2
fi

REMOTE_MODE=true
assert_remote_mode "${LIFERAY_HOME}" || REMOTE_MODE=false

JIRA_ENGINE_OK=true
assert_jira_engine_version || JIRA_ENGINE_OK=false

JIRA_N_COMPLETE=true
assert_jira_n_coverage "${N_VALUES}" || JIRA_N_COMPLETE=false

record_engine_baseline "${RUN_DIR}/engine-baseline.json"

echo "Engine baseline recorded: ${RUN_DIR}/engine-baseline.json"
echo "  (compare two runs with:"
echo "   source ${COMMON}/preflight.sh && compare_engine_resources runA/engine-baseline.json runB/engine-baseline.json)"

# IMPORTANT — how configuration reaches the test.
#
# Liferay integration tests run INSIDE the portal container (Arquillian deploys
# the test class into OSGi), so `System.getProperty(...)` in the test body reads
# the PORTAL JVM's properties, not this shell's and not Gradle's test-JVM ones.
# Setting them via `gradle -D...` alone is therefore not reliable.
#
# The dependable channel is the portal JVM itself. Two options:
#
#   a) export CATALINA_OPTS="... -Dpreview.benchmark.n.values=..." before
#      starting Tomcat (or put them in $TOMCAT/bin/setenv.sh), then restart;
#   b) additionally forward them Gradle-side via a `testIntegration {
#      systemProperties(...) }` block in the module's build.gradle -- the
#      mechanism LPD-97915 used. See build.gradle.snippet in this directory.
#
# This script emits the exact CATALINA_OPTS line for (a) and applies (b) as a
# belt-and-braces measure. VERIFY ON FIRST RUN that the values landed: the test
# prints its effective configuration in phase 1.

# Identifies the code that produced the numbers. Meaningful now that each PoC
# lives on its own branch -- with snippet-applied changes a SHA would not have
# described the code actually running.
GIT_SHA="$(git -C "${PORTAL_DIR}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
GIT_BRANCH="$(git -C "${PORTAL_DIR}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"

echo "Code:      ${GIT_BRANCH} @ ${GIT_SHA}"

if git -C "${PORTAL_DIR}" diff --quiet 2>/dev/null &&
	git -C "${PORTAL_DIR}" diff --cached --quiet 2>/dev/null; then
	:
else
	echo "WARNING: the checkout has uncommitted changes, so ${GIT_SHA} does not" >&2
	echo "fully describe the code under test. Commit before a run you intend to cite." >&2
fi

BENCHMARK_PROPS=(
	"-Dpreview.benchmark.run.id=${RUN_ID}"
	"-Dpreview.benchmark.git.sha=${GIT_SHA}"
	"-Dpreview.benchmark.n.values=${N_VALUES}"
	"-Dpreview.benchmark.query.variants=${QUERY_VARIANTS}"
	"-Dpreview.benchmark.result.sizes=${RESULT_SIZES}"
	"-Dpreview.benchmark.concurrency=${CONCURRENCY}"
	"-Dpreview.benchmark.warmup.iterations=${WARMUP_ITERATIONS}"
	"-Dpreview.benchmark.measure.iterations=${MEASURE_ITERATIONS}"
	"-Dpreview.benchmark.measure.duration.seconds=${MEASURE_DURATION_SECONDS}"
	"-Dpreview.benchmark.background.corpus.size=${BACKGROUND_CORPUS_SIZE}"
	"-Dpreview.benchmark.cold.mode.enabled=${COLD_MODE_ENABLED}"
	"-Dpreview.benchmark.baseline.enabled=${BASELINE_ENABLED}"
	"-Dpreview.benchmark.results.file=${RESULTS_FILE}"
	"-Dpreview.benchmark.manifest.file=${MANIFEST_FILE}"
	"-Dpreview.benchmark.query.dump.dir=${RUN_DIR}/queries"
	# Without this the test logs to java.io.tmpdir/lpd98298-run.log and the
	# phase markers -- the only observable output of an in-container test --
	# are left behind. The portal JVM writes here directly.
	"-Dpreview.benchmark.run.log.file=${RUN_DIR}/run.log"
	"-Dpreview.benchmark.engine.vendor=${ENGINE_VENDOR}"
	"-Dpreview.benchmark.engine.version=${ENGINE_VERSION}"
)

cat > "${RUN_DIR}/catalina-opts.sh" <<-EOF
	# LPD-98298 PoC 1 — add to \$TOMCAT/bin/setenv.sh (or export before start),
	# then restart the portal so the in-container test can read them.
	#
	# Recommended JVM flags for a benchmark run are included: a fixed heap and
	# AlwaysPreTouch remove heap-resize noise from the measurement, and JFR lets
	# you prove warm-up converged rather than asserting it.
	export CATALINA_OPTS="\${CATALINA_OPTS} ${BENCHMARK_PROPS[*]}"
	export CATALINA_OPTS="\${CATALINA_OPTS} -Xms4g -Xmx4g -XX:+AlwaysPreTouch"
	export CATALINA_OPTS="\${CATALINA_OPTS} -XX:StartFlightRecording=settings=profile,filename=${RUN_DIR}/portal.jfr,dumponexit=true"
EOF

echo
echo "Portal JVM options written to: ${RUN_DIR}/catalina-opts.sh"
echo "If the test reports 'unknown'/default configuration, source that file"
echo "into setenv.sh and restart the portal before re-running."

# ---------------------------------------------------------------------------
# Phase 2 — State reset (run level)
# ---------------------------------------------------------------------------

echo
echo "=== PHASE 2: STATE RESET (run level) ==="

engine_stats_snapshot "${RUN_DIR}/engine-stats-before.json"

echo "Engine stats snapshot taken (before)"

if [ "${CLEAR_ENGINE_CACHES:-false}" = "true" ]; then
	engine_cache_clear
fi

# ---------------------------------------------------------------------------
# Phases 3 & 4 — Warm-up and measurement window (inside the test)
# ---------------------------------------------------------------------------

echo
echo "=== PHASES 3 & 4: WARM-UP + MEASUREMENT (delegated to the test) ==="
echo "Running ${TEST_CLASS}"

cd "${GRADLE_DIR}"

set +e
"${GRADLE_WRAPPER}" "${GRADLE_PROJECT}:testIntegration" \
	--tests "${TEST_CLASS}" \
	--console=plain \
	"${BENCHMARK_PROPS[@]}" \
	2>&1 | tee "${RUN_DIR}/gradle.log"
TEST_STATUS="${PIPESTATUS[0]}"
set -e

echo "Test exit status: ${TEST_STATUS}"

# ---------------------------------------------------------------------------
# Phase 5 — Artifact collection
# ---------------------------------------------------------------------------

echo
echo "=== PHASE 5: ARTIFACT COLLECTION ==="

engine_stats_snapshot "${RUN_DIR}/engine-stats-after.json"

engine_stats_delta \
	"${RUN_DIR}/engine-stats-before.json" \
	"${RUN_DIR}/engine-stats-after.json" \
	> "${RUN_DIR}/engine-stats-delta.json"

echo "Engine stats delta:"
cat "${RUN_DIR}/engine-stats-delta.json"

# The portal console is where the in-container test's stdout goes, so collect it
# alongside the JSONL -- it carries the phase markers and the effective
# configuration echo.
# LIFERAY_HOME already accounts for the bundle being a SIBLING of the checkout
# (app.server.parent.dir=${project.dir}/../bundles). An earlier version looked
# under ${PORTAL_DIR}/bundles/, which never matches, so this tail was silently
# never collected.
for candidate in \
	"${LIFERAY_HOME}/tomcat"*/logs/catalina.out \
	"${CATALINA_BASE:-}/logs/catalina.out"; do
	if [ -f "${candidate}" ]; then
		tail -n 5000 "${candidate}" > "${RUN_DIR}/portal-console.log"
		echo "Portal console tail: ${RUN_DIR}/portal-console.log"
		break
	fi
done

if [ -f "${RUN_DIR}/run.log" ]; then
	echo "Test log: ${RUN_DIR}/run.log"
elif [ -f "${TMPDIR:-/tmp}/lpd98298-run.log" ]; then
	# The properties did not reach the portal JVM, so the test fell back to its
	# default path. Salvage the log and say so -- it means the configuration echo
	# in phase 1 is worth checking before citing this run.
	cp "${TMPDIR:-/tmp}/lpd98298-run.log" "${RUN_DIR}/run.log"
	echo "WARNING: test log came from the fallback path, so the benchmark" >&2
	echo "properties likely did not reach the portal JVM. Check the phase-1" >&2
	echo "configuration echo in ${RUN_DIR}/run.log before citing this run." >&2
fi

if [ -f "${RESULTS_FILE}" ]; then
	echo "Result rows: $(wc -l < "${RESULTS_FILE}")"

	python3 "${COMMON}/analyze.py" \
		--format poc1 \
		--input "${RESULTS_FILE}" \
		--output-markdown "${RUN_DIR}/report.md" \
		--output-csv "${RUN_DIR}/summary.csv"

	echo
	cat "${RUN_DIR}/report.md"
else
	echo "WARNING: no results file at ${RESULTS_FILE}." >&2
	echo "The most likely cause is that the benchmark properties did not reach" >&2
	echo "the portal JVM -- see the CATALINA_OPTS note in phase 1." >&2
fi

# Record how far this run is from satisfying the Jira, next to the results, so a
# partial run can never be mistaken for the finished answer later.
cat > "${RUN_DIR}/jira-coverage.md" <<-EOF
	# LPD-98298 coverage for run ${RUN_ID}

	| Desired Outcome | Status |
	| --- | --- |
	| 1 — integration test, N = ${JIRA_REQUIRED_N} | $([ "${JIRA_N_COMPLETE}" = "true" ] && echo "covered (N=${N_VALUES})" || echo "**PARTIAL** — ran N=${N_VALUES}") |
	| 2 — remote mode | $([ "${REMOTE_MODE}" = "true" ] && echo "confirmed" || echo "**NOT CONFIRMED** — see preflight warnings in this run's log") |
	| 2 — engine is ES 8.19 / OS 2.19 | $([ "${JIRA_ENGINE_OK}" = "true" ] && echo "yes" || echo "**NO** — ran ${ENGINE_VENDOR} ${ENGINE_VERSION}; results do not answer the task") |
	| 2 — engine under test | ${ENGINE_VENDOR} ${ENGINE_VERSION}, heap=${ENGINE_HEAP_MB}MB, cpus=${ENGINE_CPUS} |
	| 2 — both ES 8.19 and OS 2.19 | requires a second run against the other engine, then \`compare_engine_resources\` |
	| 3 — present findings | uses report.md + summary.csv from this run |
	| code under test | ${GIT_BRANCH} @ ${GIT_SHA} |
EOF

echo
echo "Jira coverage: ${RUN_DIR}/jira-coverage.md"
cat "${RUN_DIR}/jira-coverage.md"

echo
echo "=== RUN COMPLETE: ${RUN_DIR} ==="
exit "${TEST_STATUS}"
