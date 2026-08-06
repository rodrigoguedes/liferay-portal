#!/usr/bin/env bash
#
# LPD-98298 — shared engine observability helpers for both PoCs.
#
# Works against Elasticsearch 8.x and OpenSearch 2.x: every endpoint used here
# (`/`, `_nodes/stats`, `_cache/clear`, `_count`) exists in both, which is what
# makes the ES-8.19-vs-OS-2.19 comparison the Jira asks for possible with one
# script.
#
# Uses python3 for JSON (stdlib only) rather than jq, to avoid adding a
# dependency to the benchmark host.
#
# Source this file; do not execute it.

: "${ENGINE_URL:=http://localhost:9200}"
: "${ENGINE_USER:=}"
: "${ENGINE_PASSWORD:=}"

_engine_curl() {
	local args=(-s --max-time 30)

	if [ -n "${ENGINE_USER}" ]; then
		args+=(-u "${ENGINE_USER}:${ENGINE_PASSWORD}")
	fi

	curl "${args[@]}" "$@"
}

# Vendor + version, so every result row is self-describing. Prints
# "vendor|version"; falls back to "unknown|unknown" so a missing engine never
# aborts the run silently.
engine_info() {
	local body
	body="$(_engine_curl "${ENGINE_URL}/" || true)"

	python3 - "$body" <<-'PY'
		import json, sys

		try:
		    data = json.loads(sys.argv[1])
		    version = data.get("version", {})
		    vendor = version.get("distribution") or "elasticsearch"
		    print("%s|%s" % (vendor, version.get("number", "unknown")))
		except Exception:
		    print("unknown|unknown")
	PY
}

# Heap and CPU actually granted to the engine. The Jira requires ES and OS to
# run at "similar CPU-heap allocation"; recording the real numbers is how that
# claim becomes checkable instead of assumed.
engine_resources() {
	local body
	# available_processors lives in the node INFO endpoint (_nodes/os), NOT in
	# _nodes/stats/os -- stats only carries timestamp/cpu/mem/swap, which is why
	# this reported cpus=0. heap_max_in_bytes does come from stats/jvm, so query
	# both and merge.
	body="$(_engine_curl "${ENGINE_URL}/_nodes/stats/jvm" || true)"
	local info
	info="$(_engine_curl "${ENGINE_URL}/_nodes/os" || true)"

	python3 - "$body" "$info" <<-'PY'
		import json, sys

		def first(raw):
		    try:
		        nodes = json.loads(raw).get("nodes", {})
		        for node in nodes.values():
		            return node
		    except Exception:
		        pass
		    return {}

		try:
		    heap = first(sys.argv[1]).get("jvm", {}).get("mem", {}).get(
		        "heap_max_in_bytes", 0)
		    os_info = first(sys.argv[2]).get("os", {})
		    cpus = os_info.get(
		        "available_processors", os_info.get("allocated_processors", 0))
		    print("%d|%d" % (heap // (1024 * 1024), cpus))
		except Exception:
		    print("0|0")
	PY
}

# Raw stats snapshot. Called before and after each measurement window; the
# delta is what matters (absolute counters are meaningless on a warm node).
engine_stats_snapshot() {
	local out="$1"

	_engine_curl \
		"${ENGINE_URL}/_nodes/stats/indices,jvm,os,thread_pool" > "${out}" \
		|| echo '{}' > "${out}"
}

# The metrics that actually explain a preview-search curve:
#   query_cache        -> does the terms filter amortize within a session?
#   request_cache      -> is the whole response being served from cache?
#   gc                 -> is the engine spending the extra time collecting?
#   search thread pool -> `rejected` climbing is the real degradation signal at
#                         high concurrency, and it is invisible in latency
#                         percentiles until it is already bad.
engine_stats_delta() {
	local before="$1"
	local after="$2"

	python3 - "$before" "$after" <<-'PY'
		import json, sys


		def load(path):
		    try:
		        with open(path) as f:
		            return json.load(f).get("nodes", {})
		    except Exception:
		        return {}


		def first(nodes):
		    for node in nodes.values():
		        return node
		    return {}


		before = first(load(sys.argv[1]))
		after = first(load(sys.argv[2]))


		def dig(node, *path):
		    cur = node
		    for key in path:
		        if not isinstance(cur, dict):
		            return 0
		        cur = cur.get(key, {})
		    return cur if isinstance(cur, (int, float)) else 0


		metrics = {
		    "query_cache_hits": ("indices", "query_cache", "hit_count"),
		    "query_cache_misses": ("indices", "query_cache", "miss_count"),
		    "query_cache_size_bytes": (
		        "indices", "query_cache", "memory_size_in_bytes"),
		    "request_cache_hits": ("indices", "request_cache", "hit_count"),
		    "request_cache_misses": ("indices", "request_cache", "miss_count"),
		    "fielddata_bytes": (
		        "indices", "fielddata", "memory_size_in_bytes"),
		    "search_query_total": ("indices", "search", "query_total"),
		    "search_query_time_ms": ("indices", "search", "query_time_in_millis"),
		    "search_pool_completed": ("thread_pool", "search", "completed"),
		    "search_pool_rejected": ("thread_pool", "search", "rejected"),
		    "search_pool_queue": ("thread_pool", "search", "queue"),
		}

		result = {}

		for name, path in metrics.items():
		    result[name] = dig(after, *path) - dig(before, *path)

		# GC is reported per collector; sum young + old.

		gc_count = gc_time = 0

		for phase in ("young", "old"):
		    gc_count += (
		        dig(after, "jvm", "gc", "collectors", phase, "collection_count")
		        - dig(before, "jvm", "gc", "collectors", phase,
		              "collection_count"))
		    gc_time += (
		        dig(after, "jvm", "gc", "collectors", phase,
		            "collection_time_in_millis")
		        - dig(before, "jvm", "gc", "collectors", phase,
		              "collection_time_in_millis"))

		result["gc_count"] = gc_count
		result["gc_time_ms"] = gc_time

		# Absolute, not a delta: heap pressure at the end of the window.

		result["heap_used_percent"] = dig(
		    after, "jvm", "mem", "heap_used_percent")

		print(json.dumps(result, indent=2, sort_keys=True))
	PY
}

# Deliberately NOT called by default.
#
# Clearing caches to force a "cold" measurement also colds the corpus (page
# cache, segment readers), which contaminates the very thing being measured.
# The correct way to measure the cold/across-preview cost is to vary the terms
# set per iteration and leave the caches alone -- which is what both PoCs do in
# `cold` mode. This function exists only for a deliberate, documented
# cache-behaviour experiment.
engine_cache_clear() {
	echo "WARNING: clearing engine caches also colds the corpus; prefer cold mode" >&2

	_engine_curl -X POST "${ENGINE_URL}/_cache/clear?request=true&query=true" \
		> /dev/null
}

# Cluster identity for a given URL. Two ports answering with the SAME
# cluster_uuid are two nodes of one cluster; different uuids mean genuinely
# different engines. This is what distinguishes a multi-node cluster from a
# Liferay-launched sidecar, which port numbers alone cannot do.
#
# $1 = base URL (defaults to ENGINE_URL)
engine_cluster_uuid() {
	local url="${1:-${ENGINE_URL}}"
	local body

	body="$(curl -s --max-time 10 "${url}/" 2>/dev/null || true)"

	python3 - "$body" <<-'PY'
		import json, sys

		try:
		    print(json.loads(sys.argv[1]).get("cluster_uuid", "unknown"))
		except Exception:
		    print("unknown")
	PY
}

# "vendor|version" for an arbitrary URL, unlike engine_info which is fixed to
# ENGINE_URL.
engine_info_at() {
	local url="$1"
	local body

	body="$(curl -s --max-time 10 "${url}/" 2>/dev/null || true)"

	python3 - "$body" <<-'PY'
		import json, sys

		try:
		    data = json.loads(sys.argv[1])
		    version = data.get("version", {})
		    vendor = version.get("distribution") or "elasticsearch"
		    print("%s|%s" % (vendor, version.get("number", "unknown")))
		except Exception:
		    print("unknown|unknown")
	PY
}

engine_doc_count() {
	local index_pattern="${1:-*}"
	local body

	body="$(_engine_curl "${ENGINE_URL}/${index_pattern}/_count" || true)"

	python3 - "$body" <<-'PY'
		import json, sys

		try:
		    print(json.loads(sys.argv[1]).get("count", 0))
		except Exception:
		    print(0)
	PY
}
