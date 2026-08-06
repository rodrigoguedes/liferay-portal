#!/usr/bin/env bash
#
# LPD-98298 PoC 2 — k6 benchmark runner.
#
# One k6 invocation per matrix cell. k6 scenarios are static configuration, so
# sweeping N inside a single run would mean generating scenario config
# dynamically and losing the clean per-cell artifact boundary. Looping here is
# simpler and gives one summary + one raw-sample file per cell.
#
# Phase mapping:
#   1 Preparation   engine reachable, corpus sized, query files present
#   2 State reset   per-cell engine stats snapshot + settle pause
#   3 Warm-up       inside k6 (`warmup` scenario, metrics not thresholded)
#   4 Measurement   inside k6 (`measure` scenario, constant arrival rate)
#   5 Collection    engine stats delta, raw samples, summaries, report
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

: "${TARGETS:=portal}"         # space-separated: "portal", "engine", or both
# Where discover-pairs.sh writes the swap maps it reconstructs from the engine.
# For the optional engine target, point this at a directory holding captured
# query-n<N>-<variant>-size<S>-<suffix>.json files instead.
: "${INPUT_DIR:=${HERE}/inputs}"
: "${SKIP_DISCOVERY:=false}"
: "${ARTIFACTS_DIR:=${HERE}/artifacts}"
: "${RUN_ID:=poc2-$(date -u +%Y%m%dT%H%M%SZ)}"

: "${N_VALUES:=1 10 100 500 1000}"
: "${QUERY_VARIANTS:=match-all keyword faceted}"
: "${RESULT_SIZES:=20}"
: "${CACHE_MODES:=warm cold}"
: "${RATES:=10}"

: "${WARMUP_DURATION:=30s}"
: "${MEASURE_DURATION:=60s}"
: "${WARMUP_RATE:=5}"
: "${P95_THRESHOLD_MS:=500}"
: "${ENGINE_INDEX:=liferay-*}"
: "${RESET_SETTLE_SECONDS:=5}"

# Portal target (LPD-98298 Option A). PORTAL_SCOPE and the attribute name are
# PORTAL_SCOPE is discovered from the engine (see discover-pairs.sh) unless set
# explicitly, so PoC 2 needs no hand-off from any other PoC.
: "${PORTAL_URL:=http://localhost:8080}"
: "${PORTAL_USER:=test@liferay.com}"
: "${PORTAL_PASSWORD:=test}"
: "${PORTAL_ENTRY_CLASS_NAMES:=com.liferay.journal.model.JournalArticle}"
: "${PORTAL_KEYWORDS:=lpd98298corpus}"

: "${SWAP_MAP_ATTRIBUTE_NAME:=search.experiences.preview.swap.map}"
# PORTAL_SCOPE is filled from discovery.json after discovery runs, unless set.
: "${PORTAL_SCOPE:=}"

RUN_DIR="${ARTIFACTS_DIR}/${RUN_ID}"
mkdir -p "${RUN_DIR}"

echo "=== PHASE 0: CONFIGURATION ==="
echo "Run ID:    ${RUN_ID}"
echo "Targets:   ${TARGETS}"
echo "Inputs:    ${INPUT_DIR}"
echo "Artifacts: ${RUN_DIR}"

if ! command -v k6 > /dev/null 2>&1; then
	cat >&2 <<-'EOF'
		ERROR: k6 not found on PATH.

		Install (pick one):
		  Fedora/RHEL  sudo dnf install k6      # or the Grafana rpm repo
		  Docker       docker run --rm -i --network host grafana/k6 run - < script.js
		  Binary       https://github.com/grafana/k6/releases

		Note the Docker form needs --network host to reach localhost:9200/8080.
	EOF
	exit 1
fi

# ---------------------------------------------------------------------------
# Phase 1 — Preparation
# ---------------------------------------------------------------------------

echo
echo "=== PHASE 1: PREPARATION ==="

IFS='|' read -r ENGINE_VENDOR ENGINE_VERSION <<< "$(engine_info)"
IFS='|' read -r ENGINE_HEAP_MB ENGINE_CPUS <<< "$(engine_resources)"

if [ "${ENGINE_VENDOR}" = "unknown" ]; then
	echo "ERROR: no search engine reachable at ${ENGINE_URL}" >&2
	exit 1
fi

DOC_COUNT="$(engine_doc_count "${ENGINE_INDEX}")"

echo "Engine:    ${ENGINE_VENDOR} ${ENGINE_VERSION}"
echo "Resources: heap=${ENGINE_HEAP_MB}MB cpus=${ENGINE_CPUS}"
echo "Docs:      ${DOC_COUNT} (index pattern ${ENGINE_INDEX})"

# --- Preflight -------------------------------------------------------------
#
# PoC 2 talks to the engine directly, so the sidecar-vs-remote mismatch matters
# here too: pointing at 9200 while the portal indexed into a sidecar on 9201
# would measure an empty or stale index and report it as a latency curve.

echo
echo "--- Preflight ---"

assert_engine_url_matches_portal || exit 1

JIRA_ENGINE_OK=true
assert_jira_engine_version || JIRA_ENGINE_OK=false

JIRA_N_COMPLETE=true
assert_jira_n_coverage "${N_VALUES}" || JIRA_N_COMPLETE=false

record_engine_baseline "${RUN_DIR}/engine-baseline.json"

echo "Engine baseline recorded: ${RUN_DIR}/engine-baseline.json"

if [ "${DOC_COUNT}" -lt 10000 ]; then
	echo
	echo "WARNING: corpus is ${DOC_COUNT} docs, under the ~10k-per-segment mark." >&2
	echo "Caching may still engage: 0 hits in one run and 16362 in an identical" >&2
	echo "one, same ~2000-doc corpus. But the cold-warm gap there was smaller than" >&2
	echo "the run-to-run spread, so treat warm/cold as unresolved." >&2
fi

# --- Inputs: reconstructed here, not inherited from PoC 1 ------------------
#
# PoC 2 is an independent execution of the same scenarios, not a second stage of
# a pipeline. discover-pairs.sh rebuilds the live/draft ID pairs from the engine
# index, so nothing has to be handed over from PoC 1.

case " ${TARGETS} " in
	*" portal "*)
		if [ "${SKIP_DISCOVERY}" = "true" ]; then
			echo "Discovery skipped (SKIP_DISCOVERY=true); using ${INPUT_DIR} as-is"
		else
			echo
			echo "--- Discovering live/draft pairs from the engine ---"

			OUT_DIR="${INPUT_DIR}" \
			N_VALUES="${N_VALUES}" \
			ENGINE_URL="${ENGINE_URL}" \
			ENGINE_INDEX="${ENGINE_INDEX}" \
			ENGINE_USER="${ENGINE_USER}" \
			ENGINE_PASSWORD="${ENGINE_PASSWORD}" \
			ENTRY_CLASS_NAME="${PORTAL_ENTRY_CLASS_NAMES}" \
				"${HERE}/discover-pairs.sh"
		fi

		if [ -z "${PORTAL_SCOPE}" ] && [ -f "${INPUT_DIR}/discovery.json" ]; then
			PORTAL_SCOPE="$(python3 -c "import json,sys
print(json.load(open(sys.argv[1])).get('portal_scope',''))" "${INPUT_DIR}/discovery.json")"

			echo "PORTAL_SCOPE from discovery: ${PORTAL_SCOPE}"
		fi
		;;
esac

case " ${TARGETS} " in
	*" engine "*)
		if [ ! -d "${INPUT_DIR}" ]; then
			echo "ERROR: TARGET=engine needs captured engine queries in ${INPUT_DIR}." >&2
			echo "That target replays a real query, so one must be captured first" >&2
			echo "(PoC 1 writes them under artifacts/<run>/queries/). It is opt-in" >&2
			echo "precisely because it cannot be self-sufficient -- see WHAT-IS-MEASURED.md." >&2
			exit 1
		fi
		;;
esac

cat > "${RUN_DIR}/manifest.json" <<-EOF
	{
	  "run_id": "${RUN_ID}",
	  "poc": "poc2-k6",
	  "targets": "${TARGETS}",
	  "engine_vendor": "${ENGINE_VENDOR}",
	  "engine_version": "${ENGINE_VERSION}",
	  "engine_heap_mb": ${ENGINE_HEAP_MB},
	  "engine_cpus": ${ENGINE_CPUS},
	  "engine_index": "${ENGINE_INDEX}",
	  "doc_count": ${DOC_COUNT},
	  "n_values": "${N_VALUES}",
	  "query_variants": "${QUERY_VARIANTS}",
	  "result_sizes": "${RESULT_SIZES}",
	  "cache_modes": "${CACHE_MODES}",
	  "rates": "${RATES}",
	  "warmup_duration": "${WARMUP_DURATION}",
	  "measure_duration": "${MEASURE_DURATION}",
	  "p95_threshold_ms": ${P95_THRESHOLD_MS}
	}
EOF

echo "Manifest:  ${RUN_DIR}/manifest.json"

# ---------------------------------------------------------------------------
# Phases 2-4, per cell
# ---------------------------------------------------------------------------

FAILED_CELLS=0
TOTAL_CELLS=0
SKIPPED_CELLS=0

# Runs one matrix cell through phases 2, 3, 4 and per-cell 5.
#   $1 target  $2 n  $3 variant  $4 size  $5 mode  $6 rate  $7 query file
run_cell() {
	local target="$1" n="$2" variant="$3" size="$4" mode="$5" rate="$6"
	local query_file="$7"

	# The target is part of the cell identity: engine and portal exercise
	# different request paths, so their samples must never share a bucket.
	local cell="${target}-n${n}-${variant}-size${size}-${mode}-rate${rate}"
	local cell_dir="${RUN_DIR}/${cell}"

	mkdir -p "${cell_dir}"

	TOTAL_CELLS=$((TOTAL_CELLS + 1))

	echo
	echo "=== CELL ${cell} ==="

	if [ -n "${query_file}" ] && [ -f "${query_file}" ]; then
		echo "Input: ${query_file} ($(wc -c < "${query_file}") bytes)"
	else
		echo "Input: built in-script (portal target)"
	fi

	# --- Phase 2: state reset ---------------------------------------------
	echo "PHASE 2: state reset"

	engine_stats_snapshot "${cell_dir}/engine-stats-before.json"

	sleep "${RESET_SETTLE_SECONDS}"

	# --- Phases 3 & 4: warm-up + measurement (inside k6) ------------------
	echo "PHASES 3 & 4: warm-up ${WARMUP_DURATION} then measure ${MEASURE_DURATION} at ${rate}/s"

	local cell_status

	set +e
	TARGET="${target}" \
	N="${n}" \
	QUERY_VARIANT="${variant}" \
	RESULT_SIZE="${size}" \
	CACHE_MODE="${mode}" \
	RATE="${rate}" \
	WARMUP_RATE="${WARMUP_RATE}" \
	WARMUP_DURATION="${WARMUP_DURATION}" \
	MEASURE_DURATION="${MEASURE_DURATION}" \
	P95_THRESHOLD_MS="${P95_THRESHOLD_MS}" \
	QUERY_FILE="${query_file}" \
	SWAP_MAP_FILE="${INPUT_DIR}/swapmap-n${n}.json" \
	PORTAL_URL="${PORTAL_URL}" \
	PORTAL_USER="${PORTAL_USER}" \
	PORTAL_PASSWORD="${PORTAL_PASSWORD}" \
	PORTAL_SCOPE="${PORTAL_SCOPE}" \
	PORTAL_ENTRY_CLASS_NAMES="${PORTAL_ENTRY_CLASS_NAMES}" \
	PORTAL_KEYWORDS="${PORTAL_KEYWORDS}" \
	SWAP_MAP_ATTRIBUTE_NAME="${SWAP_MAP_ATTRIBUTE_NAME}" \
	ENGINE_URL="${ENGINE_URL}" \
	ENGINE_INDEX="${ENGINE_INDEX}" \
	ENGINE_USER="${ENGINE_USER}" \
	ENGINE_PASSWORD="${ENGINE_PASSWORD}" \
	ENGINE_VENDOR="${ENGINE_VENDOR}" \
	ENGINE_VERSION="${ENGINE_VERSION}" \
	RUN_ID="${RUN_ID}" \
		k6 run \
			--out "json=${cell_dir}/raw-samples.json" \
			--summary-export "${cell_dir}/k6-summary.json" \
			"${HERE}/preview-search-benchmark.js" \
			2>&1 | tee "${cell_dir}/k6.log"
	cell_status="${PIPESTATUS[0]}"
	set -e

	# k6 exits 99 when a threshold fails: the run completed and the data is
	# valid, the budget was just missed. That is a result, not an error, so it
	# must not abort the sweep.
	if [ "${cell_status}" = "99" ]; then
		echo "THRESHOLD FAILED for ${cell} (data is still valid)"
		FAILED_CELLS=$((FAILED_CELLS + 1))
	elif [ "${cell_status}" != "0" ]; then
		echo "ERROR: k6 exited ${cell_status} for ${cell}" >&2
		FAILED_CELLS=$((FAILED_CELLS + 1))
	fi

	# --- Phase 5: per-cell collection -------------------------------------
	engine_stats_snapshot "${cell_dir}/engine-stats-after.json"

	engine_stats_delta \
		"${cell_dir}/engine-stats-before.json" \
		"${cell_dir}/engine-stats-after.json" \
		> "${cell_dir}/engine-stats-delta.json"

	# handleSummary writes into k6's cwd; move it next to the rest of the cell.
	local f
	for f in summary-*.json; do
		[ -f "${f}" ] && mv "${f}" "${cell_dir}/"
	done

	echo "Cell artifacts: ${cell_dir}"
}

# --- Input resolution ------------------------------------------------------
#
# The two targets need different inputs:
#   portal  the swap-map ID pairs that discover-pairs.sh reconstructed, and
#           nothing at all for the baseline cell (the body is a Liferay
#           SearchRequestBody, built in-script)
#   engine  a previously captured Elasticsearch query JSON, replayed verbatim
#
# Echoes the resolved path on stdout, empty when none is needed. Returns 1 when a
# required input is missing so the caller can count the cell as skipped.
resolve_input() {
	local target="$1" n="$2" variant="$3" size="$4" mode="$5"

	if [ "${target}" = "portal" ]; then
		if [ "${n}" = "0" ]; then
			echo ""
			return 0
		fi

		local swapmap="${INPUT_DIR}/swapmap-n${n}.json"

		if [ ! -f "${swapmap}" ]; then
			return 1
		fi

		echo "${swapmap}"
		return 0
	fi

	local suffix="warm"

	[ "${mode}" = "baseline" ] && suffix="baseline"

	local query="${INPUT_DIR}/query-n${n}-${variant}-size${size}-${suffix}.json"

	if [ ! -f "${query}" ]; then
		return 1
	fi

	echo "${query}"
	return 0
}

# --- Baseline pre-pass ------------------------------------------------------
#
# Without a baseline cell the report can only show absolute latency, and the
# figure the other team actually needs is the preview DELTA (preview p95 minus
# baseline p95 at the same target, query and size). One baseline per
# (target, variant, size) rather than one per N.

if [ "${BASELINE_ENABLED:-true}" = "true" ]; then
	echo
	echo "=== BASELINE PRE-PASS ==="

	for target in ${TARGETS}; do
		for variant in ${QUERY_VARIANTS}; do
			for size in ${RESULT_SIZES}; do
				for rate in ${RATES}; do
					if ! baseline_file="$(resolve_input "${target}" 0 "${variant}" "${size}" baseline)"; then
						echo "SKIP baseline ${target} ${variant} size=${size}: no input"
						echo "  (engine target only: capture a baseline query first)"
						SKIPPED_CELLS=$((SKIPPED_CELLS + 1))
						continue
					fi

					run_cell "${target}" 0 "${variant}" "${size}" baseline \
						"${rate}" "${baseline_file}"
				done
			done
		done
	done
fi

# --- Main sweep -------------------------------------------------------------

for target in ${TARGETS}; do
	for n in ${N_VALUES}; do
		for variant in ${QUERY_VARIANTS}; do
			for size in ${RESULT_SIZES}; do
				for mode in ${CACHE_MODES}; do
					for rate in ${RATES}; do
						if ! input_file="$(resolve_input "${target}" "${n}" "${variant}" "${size}" "${mode}")"; then
							echo
							echo "SKIP ${target} n=${n} ${variant} size=${size}: missing input"
							echo "  (portal: not enough live/draft pairs in the corpus for this N;"
							echo "   engine: no captured query for this cell)"
							SKIPPED_CELLS=$((SKIPPED_CELLS + 1))
							continue
						fi

						run_cell "${target}" "${n}" "${variant}" "${size}" \
							"${mode}" "${rate}" "${input_file}"
					done
				done
			done
		done
	done
done

# ---------------------------------------------------------------------------
# Phase 5 — run-level artifact collection
# ---------------------------------------------------------------------------

echo
echo "=== PHASE 5: ARTIFACT COLLECTION (run level) ==="
echo "Cells run: ${TOTAL_CELLS}   failures/threshold misses: ${FAILED_CELLS}   skipped: ${SKIPPED_CELLS}"

# Never let a partially-covered sweep read as a complete one: a skipped cell is
# a hole in the curve, and a report with holes invites the wrong conclusion
# about where the ceiling is.
if [ "${SKIPPED_CELLS}" -gt 0 ]; then
	echo
	echo "NOTE: ${SKIPPED_CELLS} cell(s) were skipped for lack of an input." >&2
	echo "The curve in report.md has holes. For the portal target that means the" >&2
	echo "corpus does not hold enough live/draft pairs for those N values -- grow it" >&2
	echo "before drawing a cap from this run." >&2
fi

if [ "${TOTAL_CELLS}" -eq 0 ]; then
	echo "ERROR: no cells ran. Check ${INPUT_DIR} and the discovery output above." >&2
	exit 1
fi

python3 "${COMMON}/analyze.py" \
	--format k6 \
	--input "${RUN_DIR}" \
	--output-markdown "${RUN_DIR}/report.md" \
	--output-csv "${RUN_DIR}/summary.csv"

echo
cat "${RUN_DIR}/report.md"

cat > "${RUN_DIR}/jira-coverage.md" <<-EOF
	# LPD-98298 coverage for run ${RUN_ID}

	| Desired Outcome | Status |
	| --- | --- |
	| 1 — N = ${JIRA_REQUIRED_N} | $([ "${JIRA_N_COMPLETE}" = "true" ] && echo "covered (N=${N_VALUES})" || echo "**PARTIAL** — ran N=${N_VALUES}") |
	| 1 — integration test | not this PoC; PoC 1 covers the integration-test strategy independently |
	| request paths measured | ${TARGETS} |
	| 2 — engine is ES 8.19 / OS 2.19 | $([ "${JIRA_ENGINE_OK}" = "true" ] && echo "yes" || echo "**NO** — ran ${ENGINE_VENDOR} ${ENGINE_VERSION}; results do not answer the task") |
	| 2 — engine under test | ${ENGINE_VENDOR} ${ENGINE_VERSION}, heap=${ENGINE_HEAP_MB}MB, cpus=${ENGINE_CPUS} |
	| 2 — both ES 8.19 and OS 2.19 | requires a second run with a different ENGINE_URL, then \`compare_engine_resources\` |
	| 2 — similar CPU-heap | run \`compare_engine_resources\` on the two runs' engine-baseline.json; recording alone does not verify it |
	| 3 — present findings | uses report.md + summary.csv from this run |

	Cells: ${TOTAL_CELLS} run, ${SKIPPED_CELLS} skipped, ${FAILED_CELLS} failed/threshold-missed.
EOF

echo
echo "Jira coverage: ${RUN_DIR}/jira-coverage.md"
cat "${RUN_DIR}/jira-coverage.md"

echo
echo "=== RUN COMPLETE: ${RUN_DIR} ==="

# Threshold misses are reported, not fatal to the sweep. A CI gate should key on
# report.md / summary.csv, so it can distinguish "the budget was missed" from
# "the benchmark did not run".
exit 0
