/*
 * LPD-98298 PoC 2 — k6 load benchmark for the LPD-92301 preview search rewrite.
 *
 * Replays the engine query that PoC 1 generated. That coupling is the point:
 * PoC 1 dumps the real request JSON that
 * JournalArticleModelPreFilterContributor produces at each N (via
 * SearchResponse.getRequestString()), and this script sends exactly that. So
 * the two PoCs measure the same query, not two independent approximations of
 * it.
 *
 * TWO TARGETS
 * -----------
 *   TARGET=portal  (DEFAULT — the independent path)
 *     POSTs to the headless Search API. The portal builds the real query, so
 *     nothing here approximates what the contributor produces. Self-sufficient:
 *     discover-pairs.sh reconstructs the live/draft ID pairs straight from the
 *     search engine, so this PoC needs nothing from PoC 1.
 *
 *     Requires the Option A contributor change, which is ALREADY COMMITTED on
 *     this branch -- see JournalArticleModelPreFilterContributor (path in
 *     RUNBOOK.md §1). That change is why PoC 2 has its own branch.
 *
 *   TARGET=engine  (OPTIONAL — needs a captured query)
 *     Replays a previously captured engine query straight against
 *     Elasticsearch / OpenSearch. This is the only confound-free way to compare
 *     ES 8.19 with OS 2.19, because going through Liferay would put two
 *     different connector stacks (portal-search-elasticsearch8 vs
 *     portal-search-opensearch2) inside the measurement.
 *
 *     But it cannot be self-sufficient: replaying a real query means someone had
 *     to capture one first (QUERY_FILE). Building the query here instead would
 *     make it this script's approximation of the contributor's output, not the
 *     contributor's actual output -- which is exactly the thing worth avoiding.
 *     So it is opt-in, and it is not part of PoC 2's independent path.
 *
 *   TARGET=portal  (ACTIVE — LPD-98298 Option A)
 *     POSTs to the headless Search API, so the measurement includes the full
 *     Liferay stack: building the two TermsFilter objects, serializing them to
 *     an engine query, and the HTTP hop the client pays to send the map in.
 *
 * The portal target works because SearchResultResourceImpl already copies a
 * request-body `attributes` map onto the SearchContext, and its allowlist admits
 * any key prefixed "search.experiences.". The contributor is renamed to read
 * "search.experiences.preview.swap.map" -- a one-method change, no allowlist
 * edit, no thread-local propagation.
 *
 * Payload, measured on both hops at N=1000 (do not trust the older "~1.2 MB on
 * each hop" estimate, which this measurement refuted):
 *
 *   client -> portal   14.1 B/pair   numeric per-version ids
 *   portal -> engine    232 B/pair   UID strings, two clauses, emitted twice
 *
 * A 16.5x expansion inside the portal, so at N=10000 the client sends ~141 KB
 * and the portal sends ~2.3 MB. Only the second hop is large.
 *
 * FIVE PHASES
 * -----------
 *   1 Preparation   run.sh: corpus, engine check, query files (see run.sh)
 *   2 State reset   setup(): verify corpus, capture doc count, settle
 *   3 Warm-up       `warmup` scenario -- runs first, metrics NOT thresholded
 *   4 Measurement   `measure` scenario -- constant arrival rate
 *   5 Collection    handleSummary() + `--out json` per-sample rows
 *
 * WHY constant-arrival-rate AND NOT constant-vus
 * ----------------------------------------------
 * constant-vus is a closed model: each VU waits for its response before
 * sending the next request, so when the engine slows down the offered load
 * drops with it. That hides degradation -- exactly the effect being measured
 * here (coordinated omission). constant-arrival-rate is an open model: it holds
 * the request rate fixed regardless of response time, so a slowdown shows up as
 * rising latency and, if the engine cannot keep up, as dropped iterations.
 * That is the market-standard choice for a latency benchmark.
 */

import http from 'k6/http';
import exec from 'k6/execution';
import encoding from 'k6/encoding';
import { check, fail } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// ---------------------------------------------------------------------------
// Configuration (init context)
// ---------------------------------------------------------------------------

const TARGET = __ENV.TARGET || 'portal';
const N = parseInt(__ENV.N || '1', 10);
const QUERY_VARIANT = __ENV.QUERY_VARIANT || 'match-all';
const RESULT_SIZE = parseInt(__ENV.RESULT_SIZE || '20', 10);
const CACHE_MODE = __ENV.CACHE_MODE || 'warm';

const ENGINE_URL = __ENV.ENGINE_URL || 'http://localhost:9200';
const ENGINE_INDEX = __ENV.ENGINE_INDEX || 'liferay-*';
const ENGINE_USER = __ENV.ENGINE_USER || '';
const ENGINE_PASSWORD = __ENV.ENGINE_PASSWORD || '';

const PORTAL_URL = __ENV.PORTAL_URL || 'http://localhost:8080';
const PORTAL_USER = __ENV.PORTAL_USER || 'test@liferay.com';
const PORTAL_PASSWORD = __ENV.PORTAL_PASSWORD || 'test';
const PORTAL_SCOPE = __ENV.PORTAL_SCOPE || '';
const PORTAL_ENTRY_CLASS_NAMES =
	__ENV.PORTAL_ENTRY_CLASS_NAMES || 'com.liferay.journal.model.JournalArticle';
const SWAP_MAP_ATTRIBUTE_NAME =
	__ENV.SWAP_MAP_ATTRIBUTE_NAME || 'search.experiences.preview.swap.map';
// Must match the token PoC 1 seeded into the corpus (_CORPUS_TOKEN).
const PORTAL_KEYWORDS = __ENV.PORTAL_KEYWORDS || 'lpd98298corpus';

const RUN_ID = __ENV.RUN_ID || 'poc2-local';
const ENGINE_VENDOR = __ENV.ENGINE_VENDOR || 'unknown';
const ENGINE_VERSION = __ENV.ENGINE_VERSION || 'unknown';
const TERMS_KEY_TYPE = __ENV.TERMS_KEY_TYPE || 'uid';

const WARMUP_DURATION = __ENV.WARMUP_DURATION || '30s';
const MEASURE_DURATION = __ENV.MEASURE_DURATION || '60s';
const RATE = parseInt(__ENV.RATE || '10', 10);
const WARMUP_RATE = parseInt(__ENV.WARMUP_RATE || '5', 10);
const PRE_ALLOCATED_VUS = parseInt(__ENV.PRE_ALLOCATED_VUS || '20', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '100', 10);

const P95_THRESHOLD_MS = parseInt(__ENV.P95_THRESHOLD_MS || '500', 10);

// `open()` is only legal in init context, which is why both paths come from env.

// Engine target only: a previously captured engine query, replayed verbatim.
const QUERY_FILE = __ENV.QUERY_FILE || '';
const QUERY_BODY = TARGET === 'engine' ? open(QUERY_FILE) : '';

// Portal target only: the live/draft ID pairs, written by discover-pairs.sh
// straight from the engine index. No PoC 1 involvement.
const SWAP_MAP_FILE = __ENV.SWAP_MAP_FILE || '';

const SWAP_MAP_JSON = TARGET === 'portal' && N > 0 ? open(SWAP_MAP_FILE) : '';

// Precomputed in init context: building this per iteration would put JSON
// serialization of up to 10,000 entries inside the measured window and report
// k6's own cost as portal latency.
const PORTAL_BODY = TARGET === 'portal' ? buildPortalBody() : '';

const REQUEST_BYTES =
	TARGET === 'portal' ? PORTAL_BODY.length : QUERY_BODY.length;

function buildPortalBody() {
	const attributes = {};

	// Allowlisted by _isAllowedSearchContextAttribute; required for a match-all.
	if (QUERY_VARIANT === 'match-all') {
		attributes['search.empty.search'] = true;
	}

	if (N > 0 && SWAP_MAP_JSON) {
		attributes[SWAP_MAP_ATTRIBUTE_NAME] = JSON.parse(SWAP_MAP_JSON);
	}

	return JSON.stringify({ attributes: attributes });
}

// ---------------------------------------------------------------------------
// Custom metrics
//
// Recorded ONLY during the measurement window. k6's built-in http_req_duration
// spans both scenarios, so thresholding it would let warm-up latency leak into
// the pass/fail decision. These custom metrics are the measured surface.
// ---------------------------------------------------------------------------

const previewLatency = new Trend('preview_latency_ms', true);
const engineTook = new Trend('preview_engine_took_ms', true);
const previewHits = new Trend('preview_hits_total');
const previewErrors = new Counter('preview_errors');
const responseBytes = new Trend('preview_response_bytes');

// ---------------------------------------------------------------------------
// Scenarios — phases 3 and 4
// ---------------------------------------------------------------------------

export const options = {
	discardResponseBodies: false, // needed: `took` is read from the body
	scenarios: {
		warmup: {
			exec: 'warmup',
			executor: 'constant-arrival-rate',
			duration: WARMUP_DURATION,
			maxVUs: MAX_VUS,
			preAllocatedVUs: PRE_ALLOCATED_VUS,
			rate: WARMUP_RATE,
			startTime: '0s',
			timeUnit: '1s',
		},
		measure: {
			exec: 'measure',
			executor: 'constant-arrival-rate',
			duration: MEASURE_DURATION,
			maxVUs: MAX_VUS,
			preAllocatedVUs: PRE_ALLOCATED_VUS,
			rate: RATE,
			startTime: WARMUP_DURATION,
			timeUnit: '1s',
		},
	},
	summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
	thresholds: {
		// Thresholds as code: this is what turns a benchmark into a regression
		// gate. A CI lane can fail the build when a change pushes preview-search
		// p95 past the agreed budget -- once the team agrees a budget, which is
		// still an open decision.
		preview_latency_ms: [`p(95)<${P95_THRESHOLD_MS}`],
		preview_errors: ['count==0'],
	},
};

// ---------------------------------------------------------------------------
// Phase 2 — state reset / preflight
// ---------------------------------------------------------------------------

export function setup() {
	if (TARGET === 'portal' && N > 0) {
		assertPreviewAttributeTakesEffect();
	}

	const params = { headers: { 'Content-Type': 'application/json' } };

	if (ENGINE_USER) {
		params.headers.Authorization = basicAuth(ENGINE_USER, ENGINE_PASSWORD);
	}

	// Confirm the corpus is big enough to be worth measuring. A small index
	// does not prevent caching -- measured 0 cache hits in one run and 16362 in
	// an identical one on the same ~2,000-doc corpus. What it does block is
	// signal-to-noise: the cold-warm gap there was under 2 ms while the same cell
	// moved 9.7 ms between runs.
	const countResponse = http.get(
		`${ENGINE_URL}/${ENGINE_INDEX}/_count`,
		params
	);

	let docCount = 0;

	try {
		docCount = JSON.parse(countResponse.body).count || 0;
	} catch (e) {
		fail(`Could not read doc count from ${ENGINE_URL}: ${e}`);
	}

	if (docCount < 10000) {
		console.warn(
			`Corpus is ${docCount} docs, under the ~10k-per-segment mark. Caching ` +
				'may still engage, but the cold-warm gap at this corpus size is ' +
				'smaller than the run-to-run spread -- treat warm/cold as ' +
				'unresolved rather than measured.'
		);
	}

	console.log(
		`[setup] target=${TARGET} n=${N} variant=${QUERY_VARIANT} ` +
			`size=${RESULT_SIZE} mode=${CACHE_MODE} docs=${docCount} ` +
			`requestBytes=${REQUEST_BYTES}`
	);

	return { docCount };
}

/*
 * Proves the preview attribute actually reaches the contributor.
 *
 * This is the single most important guard in the portal target. An unrecognized
 * SearchContext attribute is silently DROPPED -- the search then returns
 * ordinary approved/live results and every latency number would be baseline
 * latency reported as preview latency. That is worse than a crash, because it
 * looks like a successful run.
 *
 * The naive check -- compare totalCount with and without the map -- does not
 * work: the swap is 1:1, so exchanging one live version for one draft leaves
 * the count unchanged whether or not the attribute took effect.
 *
 * So probe with a map whose TARGET id does not exist. The contributor then
 * excludes the live version and includes nothing, and the count must drop by
 * exactly one. If the count does not move, the attribute was ignored.
 */
function assertPreviewAttributeTakesEffect() {
	const swaps = JSON.parse(SWAP_MAP_JSON)[PORTAL_ENTRY_CLASS_NAMES];
	const liveIds = Object.keys(swaps);

	if (!liveIds.length) {
		fail(`${SWAP_MAP_FILE} holds no swap pairs for ${PORTAL_ENTRY_CLASS_NAMES}`);
	}

	const baselineCount = portalCount({});

	const probe = {};
	probe[PORTAL_ENTRY_CLASS_NAMES] = { [liveIds[0]]: 999999999 };

	const probeAttributes = {};
	probeAttributes[SWAP_MAP_ATTRIBUTE_NAME] = probe;

	const probeCount = portalCount(probeAttributes);

	console.log(
		`[setup] preview-attribute probe: baseline=${baselineCount} ` +
			`probe=${probeCount} (expected ${baselineCount - 1})`
	);

	if (probeCount === baselineCount) {
		fail(
			`The preview attribute "${SWAP_MAP_ATTRIBUTE_NAME}" had NO EFFECT: ` +
				`excluding one live version left the count at ${baselineCount}. ` +
				'The attribute is being dropped, so this run would measure ' +
				'BASELINE latency and label it preview latency. Check that (a) the ' +
				'contributor reads this exact key (see RUNBOOK.md 1) ' +
				'and (b) that change is deployed to the running portal.'
		);
	}

	if (probeCount !== baselineCount - 1) {
		console.warn(
			`Preview attribute works but the count moved by ` +
				`${baselineCount - probeCount}, not 1. Possibly the probed article ` +
				'is not in scope, or several versions share the excluded id. ' +
				'Results are usable; the corpus may not be what you think.'
		);
	}
}

function portalCount(attributes) {
	const body = { attributes: Object.assign({}, attributes) };

	if (QUERY_VARIANT === 'match-all') {
		body.attributes['search.empty.search'] = true;
	}

	const query = [
		`entryClassNames=${encodeURIComponent(PORTAL_ENTRY_CLASS_NAMES)}`,
		'pageSize=1',
		'page=1',
	];

	if (PORTAL_SCOPE) {
		query.push(`scope=${encodeURIComponent(PORTAL_SCOPE)}`);
	}

	if (QUERY_VARIANT !== 'match-all') {
		query.push(`search=${encodeURIComponent(PORTAL_KEYWORDS)}`);
	}

	const response = http.post(
		`${PORTAL_URL}/o/search/v1.0/search?${query.join('&')}`,
		JSON.stringify(body),
		{
			headers: {
				Authorization: basicAuth(PORTAL_USER, PORTAL_PASSWORD),
				'Content-Type': 'application/json',
			},
		}
	);

	if (response.status !== 200) {
		fail(
			`Headless search returned ${response.status}: ` +
				`${String(response.body).substring(0, 400)}`
		);
	}

	try {
		return JSON.parse(response.body).totalCount;
	} catch (e) {
		fail(`Could not parse headless search response: ${e}`);
	}
}

// ---------------------------------------------------------------------------
// Phase 3 — warm-up (unmeasured: no custom metrics recorded)
// ---------------------------------------------------------------------------

export function warmup() {
	send('warmup');
}

// ---------------------------------------------------------------------------
// Phase 4 — measurement window
// ---------------------------------------------------------------------------

export function measure() {
	send('measure');
}

function send(phase) {
	const tags = {
		cache_mode: CACHE_MODE,
		engine_vendor: ENGINE_VENDOR,
		engine_version: ENGINE_VERSION,
		n_preview_items: String(N),
		phase: phase,
		query_type: QUERY_VARIANT,
		result_size: String(RESULT_SIZE),
		run_id: RUN_ID,
		// A dimension, not metadata: engine and portal measure different request
		// paths, so their samples must never be averaged into one cell.
		target: TARGET,
		terms_key_type: TERMS_KEY_TYPE,
	};

	const body = bodyForIteration();

	const params = {
		headers: { 'Content-Type': 'application/json' },
		tags: tags,
	};

	let url;

	if (TARGET === 'portal') {
		const query = [
			`entryClassNames=${encodeURIComponent(PORTAL_ENTRY_CLASS_NAMES)}`,
			`pageSize=${RESULT_SIZE}`,
			'page=1',
		];

		if (PORTAL_SCOPE) {
			query.push(`scope=${encodeURIComponent(PORTAL_SCOPE)}`);
		}

		if (QUERY_VARIANT !== 'match-all') {
			query.push(`search=${encodeURIComponent(PORTAL_KEYWORDS)}`);
		}

		url = `${PORTAL_URL}/o/search/v1.0/search?${query.join('&')}`;
		params.headers.Authorization = basicAuth(PORTAL_USER, PORTAL_PASSWORD);
	} else {
		url = `${ENGINE_URL}/${ENGINE_INDEX}/_search`;

		if (ENGINE_USER) {
			params.headers.Authorization = basicAuth(
				ENGINE_USER,
				ENGINE_PASSWORD
			);
		}
	}

	const response = http.post(url, body, params);

	const ok = check(response, {
		'status is 200': (r) => r.status === 200,
	});

	if (!ok) {
		previewErrors.add(1, tags);

		return;
	}

	// Only the measurement window feeds the metrics the thresholds watch.
	if (phase !== 'measure') {
		return;
	}

	previewLatency.add(response.timings.duration, tags);
	responseBytes.add(response.body.length, tags);

	try {
		const parsed = JSON.parse(response.body);

		if (TARGET === 'portal') {
			// The headless response is a Page<SearchResult>; it carries no engine
			// `took`. Engine-side attribution is PoC 1's job -- it reads
			// SearchResponse.getSearchTimeValue() in-process. Here only the
			// round-trip is available, which is exactly the user-facing number
			// this target exists to produce.
			if (typeof parsed.totalCount === 'number') {
				previewHits.add(parsed.totalCount, tags);
			}
		} else {
			if (typeof parsed.took === 'number') {
				engineTook.add(parsed.took, tags);
			}

			const total = parsed.hits && parsed.hits.total;

			if (total) {
				previewHits.add(
					typeof total === 'object' ? total.value : total,
					tags
				);
			}
		}
	} catch (e) {
		previewErrors.add(1, tags);
	}
}

/*
 * Cold / across-preview mode.
 *
 * The captured query holds a fixed terms set, so replaying it verbatim measures
 * the warm, within-session case. For the cold case the terms must differ every
 * iteration -- and crucially NOT by clearing engine caches, which would also
 * cold the corpus and contaminate the measurement.
 *
 * Perturbing one term per iteration keeps N, the payload size, and the query
 * shape identical while making the filter byte-different, so the engine cannot
 * serve it from a cached filter. It is a proxy for "a different preview each
 * time", not a literal one; the literal version needs N fresh real IDs per
 * iteration, which PoC 1 does in-process where it has the seeded corpus.
 */
function bodyForIteration() {
	if (TARGET === 'portal') {
		if (CACHE_MODE !== 'cold' || N === 0) {
			return PORTAL_BODY;
		}

		// Bump one target id so the include-side terms set is byte-different
		// every iteration. A string replace, not JSON.parse + stringify: at
		// N=10000 re-serializing the map would put k6's own JSON cost inside the
		// measured window and report it as portal latency.
		const bumped = 900000000 + exec.scenario.iterationInTest;

		return PORTAL_BODY.replace(/"(\d+)":(\d+)/, `"$1":${bumped}`);
	}

	if (CACHE_MODE !== 'cold') {
		return QUERY_BODY;
	}

	const marker = `__lpd98298_${exec.scenario.iterationInTest}`;

	// Replace the first occurrence of the first term with a unique value. The
	// term will not match anything, which is fine: the cost being measured is
	// the dictionary lookup and cache miss, not the hit.
	return QUERY_BODY.replace(/"([^"]*_PORTLET_\d+)"/, `"$1${marker}"`);
}

function basicAuth(user, password) {
	return `Basic ${encoding.b64encode(`${user}:${password}`)}`;
}

// ---------------------------------------------------------------------------
// Phase 5 — artifact collection
//
// Per-iteration rows come from `k6 run --out json=...` (every metric sample,
// with the tags set above). handleSummary adds a human-readable summary and a
// machine-readable roll-up next to it. Percentiles for the report are computed
// by common/analyze.py from the raw samples -- not from this summary -- so the
// data can be re-cut without re-running.
// ---------------------------------------------------------------------------

export function handleSummary(data) {
	const cell = `n${N}-${QUERY_VARIANT}-size${RESULT_SIZE}-${CACHE_MODE}`;

	const summary = {
		run_id: RUN_ID,
		poc: 'poc2-k6',
		target: TARGET,
		cell: cell,
		engine_vendor: ENGINE_VENDOR,
		engine_version: ENGINE_VERSION,
		n_preview_items: N,
		query_type: QUERY_VARIANT,
		result_size: RESULT_SIZE,
		cache_mode: CACHE_MODE,
		terms_key_type: TERMS_KEY_TYPE,
		request_bytes: REQUEST_BYTES,
		rate_per_second: RATE,
		warmup_duration: WARMUP_DURATION,
		measure_duration: MEASURE_DURATION,
		metrics: {
			preview_latency_ms: trendOf(data, 'preview_latency_ms'),
			preview_engine_took_ms: trendOf(data, 'preview_engine_took_ms'),
			preview_response_bytes: trendOf(data, 'preview_response_bytes'),
			iterations: countOf(data, 'iterations'),
			dropped_iterations: countOf(data, 'dropped_iterations'),
			errors: countOf(data, 'preview_errors'),
		},
		thresholds_passed: !hasFailedThreshold(data),
	};

	const out = {};

	out[`summary-${cell}.json`] = JSON.stringify(summary, null, 2);
	out.stdout = renderText(summary);

	return out;
}

function trendOf(data, name) {
	const metric = data.metrics[name];

	if (!metric || !metric.values) {
		return null;
	}

	return {
		avg: metric.values.avg,
		min: metric.values.min,
		med: metric.values.med,
		p90: metric.values['p(90)'],
		p95: metric.values['p(95)'],
		p99: metric.values['p(99)'],
		max: metric.values.max,
	};
}

function countOf(data, name) {
	const metric = data.metrics[name];

	if (!metric || !metric.values) {
		return 0;
	}

	return metric.values.count || 0;
}

function hasFailedThreshold(data) {
	for (const name in data.metrics) {
		const thresholds = data.metrics[name].thresholds;

		for (const key in thresholds || {}) {
			if (thresholds[key].ok === false) {
				return true;
			}
		}
	}

	return false;
}

function renderText(summary) {
	const latency = summary.metrics.preview_latency_ms || {};
	const took = summary.metrics.preview_engine_took_ms || {};

	const round = (v) => (typeof v === 'number' ? v.toFixed(2) : 'n/a');

	// The headline number is the delta: round-trip minus engine `took` is the
	// client-side plus network cost, which is where terms serialization shows
	// up and which `took` alone hides.
	const delta =
		typeof latency.p95 === 'number' && typeof took.p95 === 'number'
			? (latency.p95 - took.p95).toFixed(2)
			: 'n/a';

	return [
		'',
		`  LPD-98298 PoC 2 — ${summary.cell}`,
		`  engine        ${summary.engine_vendor} ${summary.engine_version}`,
		`  target        ${summary.target}`,
		`  N             ${summary.n_preview_items}`,
		`  requestBytes  ${summary.request_bytes}`,
		`  rate          ${summary.rate_per_second}/s for ${summary.measure_duration}`,
		'',
		`  roundtrip     p50 ${round(latency.med)}  p95 ${round(
			latency.p95
		)}  p99 ${round(latency.p99)} ms`,
		`  engine took   p50 ${round(took.med)}  p95 ${round(
			took.p95
		)}  p99 ${round(took.p99)} ms`,
		`  delta (p95)   ${delta} ms   <- client + network cost`,
		'',
		`  iterations    ${summary.metrics.iterations}`,
		`  dropped       ${summary.metrics.dropped_iterations}  <- >0 means the engine could not keep up`,
		`  errors        ${summary.metrics.errors}`,
		`  thresholds    ${summary.thresholds_passed ? 'PASS' : 'FAIL'}`,
		'',
	].join('\n');
}
