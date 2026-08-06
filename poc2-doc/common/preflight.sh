#!/usr/bin/env bash
#
# LPD-98298 — preflight guards shared by both PoCs.
#
# These exist because of one failure mode that produces confidently WRONG
# numbers rather than an error: measuring one engine while labelling the results
# with another engine's version, heap and CPU count. LPD-98298's Desired Outcome
# 2 requires ES 8.19 and OS 2.19 "in remote mode with similar CPU-heap
# allocation"; a run that silently used a portal-launched sidecar would satisfy
# none of that while still producing a plausible-looking report.
#
# Source this file; do not execute it.

# Liferay launches its own Elasticsearch SIDECAR when
# ElasticsearchConfiguration.productionModeEnabled() is false. The sidecar's
# default HTTP port is 9201 (ElasticsearchConfiguration.sidecarHttpPort), not
# 9200 -- so a benchmark pointed at 9200 would be reading a different engine
# than the portal is querying.
: "${SIDECAR_PORT:=9201}"

# The N values LPD-98298 explicitly enumerates in its Desired Outcome 1.
JIRA_REQUIRED_N="1 10 100 500 1000 10000"

_preflight_warn() {
	echo "PREFLIGHT WARNING: $*" >&2
}

_preflight_fail() {
	echo "PREFLIGHT FAILURE: $*" >&2
	return 1
}

# Reads the portal's Elasticsearch OSGi config to determine whether it is
# talking to a remote cluster or to a sidecar.
#
# $1 = LIFERAY_HOME (the bundle dir holding osgi/configs)
#
# Returns 0 if remote mode is confirmed, 1 otherwise. Never aborts the run on
# its own -- the caller decides whether a non-remote run is acceptable, because
# a local run is a legitimate first smoke test even though it cannot satisfy the
# Jira.
assert_remote_mode() {
	local liferay_home="$1"
	local es_config="${liferay_home}/osgi/configs/com.liferay.portal.search.elasticsearch8.configuration.ElasticsearchConfiguration.config"
	local os_config="${liferay_home}/osgi/configs/com.liferay.portal.search.opensearch2.configuration.OpenSearchConfiguration.config"

	local remote_confirmed=1

	if [ -f "${es_config}" ]; then
		# OSGi .config files are TYPED: a boolean is written productionModeEnabled=B"true",
		# not ="true". Matching only the untyped form gives a false negative and
		# reports a correctly-configured remote setup as unconfirmed.
		if grep -qE 'productionModeEnabled=B?"?true"?' "${es_config}" 2>/dev/null; then
			echo "Remote mode: Elasticsearch productionModeEnabled=true"
			remote_confirmed=0
		else
			_preflight_warn \
				"productionModeEnabled is not true in ${es_config}."
			_preflight_warn \
				"The portal will launch a SIDECAR Elasticsearch on port ${SIDECAR_PORT}."
			_preflight_warn \
				"LPD-98298 Desired Outcome 2 asks for remote mode; this run cannot satisfy it."
		fi

		if grep -q 'remoteClusterConnectionId' "${es_config}" 2>/dev/null; then
			echo "Remote mode: remoteClusterConnectionId is set"
		fi
	elif [ -f "${os_config}" ]; then
		if grep -q 'remoteClusterConnectionId' "${os_config}" 2>/dev/null; then
			echo "Remote mode: OpenSearch remoteClusterConnectionId is set"
			remote_confirmed=0
		else
			_preflight_warn \
				"No remoteClusterConnectionId in ${os_config}."
		fi
	else
		_preflight_warn \
			"No search-engine OSGi config found under ${liferay_home}/osgi/configs."
		_preflight_warn \
			"Cannot confirm remote mode. Configure it with:"
		_preflight_warn \
			"  ant -f build-test-elasticsearch8.xml  (see configure-portal-remote-elasticsearch-osgi-properties)"
		_preflight_warn \
			"  ant -f build-test-opensearch2.xml     (writes OpenSearchConnectionConfiguration-REMOTE.config)"
	fi

	return "${remote_confirmed}"
}

# Catches the mismatch that silently corrupts results: a sidecar answering on
# ${SIDECAR_PORT} while the benchmark reads version/heap/CPU from ENGINE_URL.
#
# If a sidecar is up and ENGINE_URL does not point at it, every result row would
# carry the wrong engine metadata. That is worse than a crash, so this one is
# treated as fatal unless explicitly overridden.
assert_engine_url_matches_portal() {
	local sidecar_url="http://localhost:${SIDECAR_PORT}"

	if ! curl -s --max-time 5 "${sidecar_url}/" > /dev/null 2>&1; then
		return 0
	fi

	# Something answers on the sidecar port. That is NOT enough to conclude a
	# sidecar: a multi-node cluster commonly binds 9200, 9201, 9202. Compare
	# cluster identity -- same cluster_uuid means these are peer nodes of the
	# cluster being measured, which is fine and common.

	local target_uuid sidecar_uuid

	target_uuid="$(engine_cluster_uuid "${ENGINE_URL}")"
	sidecar_uuid="$(engine_cluster_uuid "${sidecar_url}")"

	if [ "${target_uuid}" != "unknown" ] &&
		[ "${target_uuid}" = "${sidecar_uuid}" ]; then

		echo "Port ${SIDECAR_PORT} is a peer node of the same cluster (uuid ${target_uuid}); not a sidecar."

		return 0
	fi

	case "${ENGINE_URL}" in
		*:"${SIDECAR_PORT}")
			_preflight_warn \
				"Measuring the engine on the Liferay sidecar port (${ENGINE_URL})."
			_preflight_warn \
				"If this is a portal-launched sidecar its heap and CPU come from"
			_preflight_warn \
				"sidecarJVMOptions, not from a cluster you sized -- smoke test only."
			return 0
			;;
	esac

	_preflight_warn "A DIFFERENT engine answers on ${sidecar_url} (the Liferay sidecar port):"
	_preflight_warn "  ${ENGINE_URL}   cluster_uuid=${target_uuid}"
	_preflight_warn "  ${sidecar_url}  cluster_uuid=${sidecar_uuid}"
	_preflight_warn ""
	_preflight_warn "If the portal is using that one, every result row would be labelled with"
	_preflight_warn "the WRONG engine version, heap and CPU count -- fabricating exactly the"
	_preflight_warn "'similar CPU-heap allocation' claim LPD-98298 Desired Outcome 2 needs."
	_preflight_warn ""
	_preflight_warn "Confirm which engine the portal actually queries, then either enable remote"
	_preflight_warn "mode against ${ENGINE_URL} or point ENGINE_URL at the one in use."
	_preflight_warn ""
	_preflight_warn "Override with ALLOW_ENGINE_URL_MISMATCH=true if this is intentional."

	if [ "${ALLOW_ENGINE_URL_MISMATCH:-false}" = "true" ]; then
		_preflight_warn "Overridden by ALLOW_ENGINE_URL_MISMATCH=true; continuing."
		return 0
	fi

	_preflight_fail "engine on the sidecar port belongs to a different cluster"
}

# LPD-98298 Desired Outcome 2 names ES 8.19 and OS 2.19 specifically. Index
# internals that drive this benchmark differ across major versions (Lucene
# version, terms-query execution, cache policy), so a run on another major
# answers a different question than the task asked.
assert_jira_engine_version() {
	local vendor version

	IFS='|' read -r vendor version <<< "$(engine_info)"

	case "${vendor}:${version}" in
		elasticsearch:8.19*)
			echo "Engine version: Elasticsearch ${version} matches LPD-98298"
			return 0
			;;
		opensearch:2.19*)
			echo "Engine version: OpenSearch ${version} matches LPD-98298"
			return 0
			;;
	esac

	_preflight_warn \
		"LPD-98298 Desired Outcome 2 asks for Elasticsearch 8.19 or OpenSearch 2.19."
	_preflight_warn \
		"This engine is ${vendor} ${version}."
	_preflight_warn \
		"Terms-query execution, Lucene version and cache policy differ across majors,"
	_preflight_warn \
		"so results from this engine do not answer the task's question."
	_preflight_warn \
		"Start a matching engine, e.g.:"
	_preflight_warn \
		"  ant -f build-test-elasticsearch8.xml start-elasticsearch   # defaults to 8.19.11"
	_preflight_warn \
		"  ant -f build-test-opensearch2.xml start-opensearch"

	return 1
}

# LPD-98298 Desired Outcome 1 enumerates six N values. A run that covers only
# some of them answers only part of the task; say so rather than letting a
# partial sweep read as complete.
#
# $1 = the N values this run will use (comma- or space-separated)
assert_jira_n_coverage() {
	local configured="${1//,/ }"
	local missing=""
	local required n found

	for required in ${JIRA_REQUIRED_N}; do
		found=0

		for n in ${configured}; do
			if [ "${n}" = "${required}" ]; then
				found=1
				break
			fi
		done

		if [ "${found}" = "0" ]; then
			missing="${missing} ${required}"
		fi
	done

	if [ -n "${missing}" ]; then
		_preflight_warn \
			"LPD-98298 Desired Outcome 1 requires N =${JIRA_REQUIRED_N}."
		_preflight_warn \
			"This run is missing N =${missing}."
		_preflight_warn \
			"The report will be a partial answer to the task. Fine for a smoke run;"
		_preflight_warn \
			"not fine for the findings presented to the Core Infra Team."

		return 1
	fi

	echo "N coverage: all six values required by LPD-98298 are included"

	return 0
}

# Writes the engine's identity and resources so two runs (ES and OS) can be
# compared afterwards. Recording is not enough on its own -- see
# compare_engine_resources.
#
# $1 = output file
record_engine_baseline() {
	local out="$1"

	local vendor version heap cpus

	IFS='|' read -r vendor version <<< "$(engine_info)"
	IFS='|' read -r heap cpus <<< "$(engine_resources)"

	cat > "${out}" <<-EOF
		{
		  "engine_vendor": "${vendor}",
		  "engine_version": "${version}",
		  "engine_heap_mb": ${heap},
		  "engine_cpus": ${cpus},
		  "engine_url": "${ENGINE_URL}"
		}
	EOF
}

# Turns "similar CPU-heap allocation" from an assumption into a check.
#
# $1, $2 = two files written by record_engine_baseline
compare_engine_resources() {
	python3 - "$1" "$2" <<-'PY'
		import json, sys


		def load(path):
		    with open(path) as f:
		        return json.load(f)


		try:
		    a, b = load(sys.argv[1]), load(sys.argv[2])
		except Exception as exception:
		    print("could not compare engine baselines: %s" % exception)
		    sys.exit(0)

		print("Engine A: %s %s  heap=%sMB cpus=%s" % (
		    a["engine_vendor"], a["engine_version"], a["engine_heap_mb"],
		    a["engine_cpus"]))
		print("Engine B: %s %s  heap=%sMB cpus=%s" % (
		    b["engine_vendor"], b["engine_version"], b["engine_heap_mb"],
		    b["engine_cpus"]))

		problems = []

		heap_a, heap_b = a["engine_heap_mb"], b["engine_heap_mb"]

		if heap_a and heap_b:
		    drift = abs(heap_a - heap_b) / max(heap_a, heap_b)

		    if drift > 0.10:
		        problems.append(
		            "heap differs by %.0f%% (%sMB vs %sMB)"
		            % (drift * 100, heap_a, heap_b))
		else:
		    problems.append("heap unknown for at least one engine")

		if a["engine_cpus"] != b["engine_cpus"]:
		    problems.append(
		        "CPU count differs (%s vs %s)"
		        % (a["engine_cpus"], b["engine_cpus"]))

		if problems:
		    print("")
		    print("NOT COMPARABLE -- LPD-98298 asks for similar CPU-heap allocation:")
		    for problem in problems:
		        print("  - %s" % problem)
		    print("")
		    print("Any ES-vs-OS difference in the report may be the resource gap,")
		    print("not the engine. Re-run with matched allocation before concluding.")
		    sys.exit(1)

		print("")
		print("Resources match within 10% heap and exact CPU count: comparable.")
	PY
}
