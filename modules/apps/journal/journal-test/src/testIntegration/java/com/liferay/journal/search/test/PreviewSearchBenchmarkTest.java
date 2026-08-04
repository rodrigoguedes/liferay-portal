/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.journal.search.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.dynamic.data.mapping.test.util.DDMStructureTestUtil;
import com.liferay.journal.model.JournalArticle;
import com.liferay.journal.model.JournalFolder;
import com.liferay.journal.test.util.JournalTestUtil;
import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.portlet.PortalPreferences;
import com.liferay.portal.kernel.portlet.PortletPreferencesFactoryUtil;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.security.auth.CompanyThreadLocal;
import com.liferay.portal.kernel.service.PortalPreferencesLocalServiceUtil;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.rule.DeleteAfterTestRun;
import com.liferay.portal.kernel.test.util.GroupTestUtil;
import com.liferay.portal.kernel.test.util.RandomTestUtil;
import com.liferay.portal.kernel.test.util.SearchContextTestUtil;
import com.liferay.portal.kernel.test.util.ServiceContextTestUtil;
import com.liferay.portal.kernel.test.util.TestPropsValues;
import com.liferay.portal.kernel.test.util.UserTestUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.PortletKeys;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.search.aggregation.Aggregations;
import com.liferay.portal.search.document.Document;
import com.liferay.portal.search.legacy.searcher.SearchRequestBuilderFactory;
import com.liferay.portal.search.model.uid.UIDFactory;
import com.liferay.portal.search.searcher.SearchRequestBuilder;
import com.liferay.portal.search.searcher.SearchResponse;
import com.liferay.portal.search.searcher.SearchTimeValue;
import com.liferay.portal.search.searcher.Searcher;
import com.liferay.portal.test.rule.Inject;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;

import java.io.File;
import java.io.FileWriter;
import java.io.Serializable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * PoC 1 (LPD-98298) — integration-test benchmark for the LPD-92301 preview
 * search rewrite.
 *
 * <p>
 * Measures how search latency scales with N, the number of preview swap
 * entries, which is also the size of each of the two <code>terms</code> filters
 * that {@code JournalArticleModelPreFilterContributor} builds. Mirrors the
 * setup of {@code JournalArticlePreviewSearchTest} exactly — same preview swap
 * map {@link SearchContext} attribute, same legacy
 * {@link SearchRequestBuilderFactory}, same per-version
 * {@link JournalArticle#getId()} keys — so this measures the production code
 * path the functional PoC proved, not a re-implementation of it.
 * </p>
 *
 * <p>
 * <b>No portal-code change required.</b> This class reads the
 * {@code preview.swap.map} attribute that the unpatched contributor already
 * consumes, so its branch is purely additive: one new test file, zero
 * modifications to existing portal sources. That is deliberate — PoC 1 exists to
 * produce a benchmark with the minimum possible perturbation of the code under
 * test. {@link #_assertPreviewAttributeIsRead} still fails the run if the
 * attribute never reaches the contributor, so a mismatch cannot be measured by
 * accident.
 * </p>
 *
 * <p>
 * Independent of PoC 2 by design. The query and swap-map dumps this class writes
 * are artifacts for inspection, not inputs another PoC consumes.
 * </p>
 *
 * <p>
 * <b>The five standard performance-test phases are explicit in this class:</b>
 * </p>
 *
 * <ol>
 * <li><b>Preparation</b> — {@link #setUp()}. Builds the corpus once at
 * {@code MAX_N} swap pairs and slices it per N, so the sweep does not re-seed.
 * Verifies the index is queryable and, critically, that the previewed draft
 * documents are actually indexed (if they are not, the include-side
 * {@code terms} filter matches nothing and the whole curve is understated), and
 * that the contributor reads the attribute key this class sets.</li>
 * <li><b>State reset</b> — {@link #_resetState}. Runs between every cell.</li>
 * <li><b>Warm-up</b> — {@link #_runWarmup}. Unmeasured in the sense that rows
 * are tagged {@code phase=warmup} and excluded from percentiles; they are still
 * recorded so the warm-up curve can be inspected (proving warm-up actually
 * converged rather than asserting it).</li>
 * <li><b>Measurement window</b> — {@link #_runMeasurement}. Constant load: a
 * fixed concurrency for a fixed duration (or iteration cap).</li>
 * <li><b>Artifact collection</b> — {@link #_Recorder} streams one JSONL row per
 * iteration; {@link #_dumpQuery} writes the generated engine query per N, which
 * is the input PoC 2 (k6) replays.</li>
 * </ol>
 *
 * <p>
 * Deliberate deviation from {@code JournalArticlePreviewSearchTest}: no
 * {@code SearchTestRule}. That rule retries the test body to absorb
 * search-engine eventual consistency, and retrying a multi-minute benchmark
 * would silently double the run and corrupt the results. Index readiness is
 * instead polled explicitly in the preparation phase.
 * </p>
 *
 * <p>
 * Everything is driven by system properties so the matrix can be widened
 * without editing the class — see {@code run.sh}.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@RunWith(Arquillian.class)
public class PreviewSearchBenchmarkTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new LiferayIntegrationTestRule();

	/**
	 * Phase 1 — Preparation.
	 */
	@Before
	public void setUp() throws Exception {
		_log("=== PHASE 1: PREPARATION ===");
		_log(_describeConfiguration());

		_group = GroupTestUtil.addGroup();

		UserTestUtil.setUser(TestPropsValues.getUser());

		_enableIndexAllArticleVersions();

		_folder = JournalTestUtil.addFolder(
			_group.getGroupId(), RandomTestUtil.randomString());

		// Seed once at the largest N the sweep needs, then slice per N. Seeding
		// through the service layer is the slow part (two service calls plus
		// indexing per pair), so seeding per N would multiply an already long
		// preparation phase by the number of N values.

		int maxN = _maxOf(_nValues);

		_log("Seeding " + maxN + " approved/draft pairs (this is the slow part)");

		long seedStart = System.nanoTime();

		for (int i = 0; i < maxN; i++) {
			_pairs.add(_addArticleWithDraft(i));

			if (((i + 1) % 100) == 0) {
				_log("  seeded " + (i + 1) + "/" + maxN + " pairs");
			}
		}

		if (_backgroundCorpusSize > 0) {
			_log(
				"Seeding " + _backgroundCorpusSize +
					" approved-only background articles");

			for (int i = 0; i < _backgroundCorpusSize; i++) {
				JournalTestUtil.addArticleWithWorkflow(
					_group.getGroupId(), _folder.getFolderId(), "title",
					_CORPUS_TOKEN + " background " + i, true);
			}
		}

		_log(
			"Seeding took " +
				TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - seedStart) +
					"s");

		_awaitIndexReady();

		// Order matters: prove the attribute is read BEFORE blaming the index.
		_assertPreviewAttributeIsRead();
		_assertDraftsAreIndexed();

		_log("=== PHASE 1 COMPLETE ===");
	}

	@After
	public void tearDown() throws Exception {
		if (_recorder != null) {
			_recorder.close();
		}

		if (_originalPortalPreferencesXML != null) {
			PortalPreferencesLocalServiceUtil.updatePreferences(
				TestPropsValues.getCompanyId(),
				PortletKeys.PREFS_OWNER_TYPE_COMPANY,
				_originalPortalPreferencesXML);
		}
	}

	/**
	 * Drives the whole matrix in a single test method. One method (rather than
	 * one per cell) keeps {@link #setUp()} — and therefore the expensive corpus
	 * seeding — running exactly once.
	 */
	@Test
	public void testPreviewSearchBenchmark() throws Exception {
		_recorder = new _Recorder();

		_recordManifest();

		for (int n : _nValues) {
			for (String queryVariant : _queryVariants) {
				for (int resultSize : _resultSizes) {
					for (int concurrency : _concurrencyLevels) {

						// Baseline (no preview) and preview, at the same query
						// and size, so the preview delta is computable.

						if (_baselineEnabled) {
							_runCell(
								new _Cell(
									0, queryVariant, resultSize, concurrency,
									_CACHE_MODE_BASELINE));
						}

						_runCell(
							new _Cell(
								n, queryVariant, resultSize, concurrency,
								_CACHE_MODE_WARM));

						if (_coldModeEnabled) {
							_runCell(
								new _Cell(
									n, queryVariant, resultSize, concurrency,
									_CACHE_MODE_COLD));
						}
					}
				}
			}
		}

		_log("=== PHASE 5: ARTIFACT COLLECTION ===");
		_log("Results:  " + _resultsFile);
		_log("Queries:  " + _queryDumpDir);
		_log("Manifest: " + _manifestFile);
		_log("=== RUN COMPLETE ===");
	}

	private JournalArticle[] _addArticleWithDraft(int index) throws Exception {
		JournalArticle approved = JournalTestUtil.addArticleWithWorkflow(
			_group.getGroupId(), _folder.getFolderId(), "title",
			_CORPUS_TOKEN + " approved " + index, true);

		ServiceContext serviceContext =
			ServiceContextTestUtil.getServiceContext(_group.getGroupId());

		serviceContext.setWorkflowAction(WorkflowConstants.ACTION_SAVE_DRAFT);

		JournalArticle draft = JournalTestUtil.updateArticle(
			approved, approved.getTitleMap(),
			DDMStructureTestUtil.getSampleStructuredContent(
				_DRAFT_TOKEN + " draft " + index),
			false, true, serviceContext);

		Assert.assertNotEquals(
			"Approved and draft must be distinct version rows",
			(Long)approved.getId(), (Long)draft.getId());

		return new JournalArticle[] {approved, draft};
	}

	/**
	 * The trap the test plan warns about: if the previewed draft documents are
	 * not in the index, the include-side {@code terms} filter matches nothing,
	 * the postings union is empty, and the benchmark reports a flat,
	 * reassuring, wrong curve. Fail loudly instead.
	 */
	/**
	 * Proves the contributor actually reads the attribute key this class sets.
	 *
	 * <p>
	 * Without this, a key mismatch surfaces as {@link #_assertDraftsAreIndexed}
	 * failing and blaming the index — the wrong diagnosis for the wrong cause,
	 * which is an expensive way to lose an hour after a 20-minute seeding phase.
	 * </p>
	 *
	 * <p>
	 * Comparing hit counts with and without the map would prove nothing: the swap
	 * is 1:1, so exchanging one live version for one draft leaves the count
	 * unchanged either way. Probing with a target id that does not exist does
	 * work — the contributor then excludes the live version and includes nothing,
	 * so the count must drop by exactly one.
	 * </p>
	 */
	private void _assertPreviewAttributeIsRead() throws Exception {
		long baselineCount = _search(
			null, false, 1, null
		).getCount();

		JournalArticle[] pair = _pairs.get(0);

		HashMap<Long, Long> swaps = new HashMap<>();

		swaps.put(pair[0].getId(), _NONEXISTENT_CLASS_PK);

		HashMap<String, Serializable> probe = new HashMap<>();

		probe.put(JournalArticle.class.getName(), swaps);

		long probeCount = _search(
			null, false, 1, probe
		).getCount();

		Assert.assertNotEquals(
			StringBundler.concat(
				"The contributor never read the attribute \"",
				_PREVIEW_SWAP_MAP_ATTRIBUTE_NAME, "\": excluding one live ",
				"version left the hit count at ", baselineCount,
				". Every \"preview\" cell would measure BASELINE latency. ",
				"Either apply poc/patches/",
				"JournalArticleModelPreFilterContributor.snippet.java (which ",
				"makes the contributor read this key), or run against the ",
				"unpatched contributor with ",
				"-Dpreview.benchmark.swap.map.attribute.name=preview.swap.map"),
			baselineCount, probeCount);

		if (probeCount != (baselineCount - 1)) {
			_log(
				StringBundler.concat(
					"WARNING: the preview filter is applied, but the count moved ",
					"by ", baselineCount - probeCount, ", not 1. The corpus may ",
					"not be what this run assumes."));
		}

		_log(
			StringBundler.concat(
				"Preview attribute \"", _PREVIEW_SWAP_MAP_ATTRIBUTE_NAME,
				"\" confirmed read by the contributor (", baselineCount, " -> ",
				probeCount, ")"));
	}

	private void _assertDraftsAreIndexed() throws Exception {
		JournalArticle[] pair = _pairs.get(0);

		// Search the draft-only token, not match-all: with a corpus larger than
		// the page size the draft would fall outside a match-all page and this
		// check would fail for the wrong reason. Only draft documents carry
		// _DRAFT_TOKEN, and the size-1 swap map includes exactly one of them.

		SearchResponse searchResponse = _search(
			_DRAFT_TOKEN, false, 100, _previewSwapMap(1, 0));

		String draftUID = _uidFactory.getUID(pair[1]);

		boolean found = false;

		for (Document document : searchResponse.getDocuments()) {
			if (draftUID.equals(document.getString(Field.UID))) {
				found = true;

				break;
			}
		}

		Assert.assertTrue(
			StringBundler.concat(
				"The previewed draft ", draftUID, " is not in the index. The ",
				"attribute IS being read (proven by the previous check), so this ",
				"is genuinely an indexing problem: the include-side terms filter ",
				"matches nothing and every latency number would be understated. ",
				"Check that indexAllArticleVersionsEnabled is true and that the ",
				"draft versions were indexed."),
			found);

		_log("Draft documents confirmed present in the index");
	}

	/**
	 * Polls until the index reports the expected document count, rather than
	 * relying on {@code SearchTestRule} retries.
	 */
	private void _awaitIndexReady() throws Exception {
		int expected = _pairs.size() + _backgroundCorpusSize;

		long deadline =
			System.currentTimeMillis() +
				TimeUnit.SECONDS.toMillis(_indexReadyTimeoutSeconds);

		long count = -1;

		while (System.currentTimeMillis() < deadline) {
			SearchResponse searchResponse = _search(null, false, 1, null);

			count = searchResponse.getCount();

			if (count >= expected) {
				_log("Index ready: " + count + " documents visible");

				return;
			}

			Thread.sleep(1000);
		}

		Assert.fail(
			StringBundler.concat(
				"Index did not reach ", expected, " documents within ",
				_indexReadyTimeoutSeconds, "s (last count: ", count,
				"). Refusing to benchmark against an incomplete index."));
	}

	private String _describeConfiguration() {
		StringBundler sb = new StringBundler();

		sb.append("\n  nValues=");
		sb.append(_join(_nValues));
		sb.append("\n  queryVariants=");
		sb.append(StringUtil.merge(_queryVariants, ","));
		sb.append("\n  resultSizes=");
		sb.append(_join(_resultSizes));
		sb.append("\n  concurrencyLevels=");
		sb.append(_join(_concurrencyLevels));
		sb.append("\n  warmupIterations=");
		sb.append(_warmupIterations);
		sb.append("\n  measureIterations=");
		sb.append(_measureIterations);
		sb.append("\n  measureDurationSeconds=");
		sb.append(_measureDurationSeconds);
		sb.append("\n  backgroundCorpusSize=");
		sb.append(_backgroundCorpusSize);
		sb.append("\n  baselineEnabled=");
		sb.append(_baselineEnabled);
		sb.append("\n  coldModeEnabled=");
		sb.append(_coldModeEnabled);
		sb.append("\n  resultsFile=");
		sb.append(_resultsFile);

		return sb.toString();
	}

	private void _dumpQuery(_Cell cell, String requestString) {
		if (_queryDumpDir == null) {
			return;
		}

		File dir = new File(_queryDumpDir);

		dir.mkdirs();

		String name = StringBundler.concat(
			"query-n", cell.n, "-", cell.queryVariant, "-size", cell.resultSize,
			"-", cell.cacheMode, ".json");

		File file = new File(dir, name);

		if (file.exists()) {
			return;
		}

		try (FileWriter fileWriter = new FileWriter(file)) {
			fileWriter.write(requestString);
		}
		catch (Exception exception) {
			_log("Could not dump query: " + exception.getMessage());
		}
	}

	/**
	 * Writes the raw ID pairs for a cell, keyed as the contributor expects.
	 *
	 * <p>
	 * PoC 2's engine target replays the dumped Elasticsearch query, but its
	 * portal target cannot: over the headless API the request body is a Liferay
	 * {@code SearchRequestBody}, not an engine query, so k6 has to build the
	 * preview map itself. It has no database access, so PoC 1 has to hand it the
	 * pairs.
	 * </p>
	 */
	private void _dumpSwapMap(_Cell cell, int offset) {
		if ((_queryDumpDir == null) || (cell.n == 0)) {
			return;
		}

		File dir = new File(_queryDumpDir);

		dir.mkdirs();

		File file = new File(dir, "swapmap-n" + cell.n + ".json");

		if (file.exists()) {
			return;
		}

		StringBundler sb = new StringBundler();

		sb.append("{\"");
		sb.append(JournalArticle.class.getName());
		sb.append("\":{");

		int size = _pairs.size();

		for (int i = 0; i < cell.n; i++) {
			if (i > 0) {
				sb.append(",");
			}

			JournalArticle[] pair = _pairs.get((offset + i) % size);

			sb.append("\"");
			sb.append(pair[0].getId());
			sb.append("\":");
			sb.append(pair[1].getId());
		}

		sb.append("}}");

		try (FileWriter fileWriter = new FileWriter(file)) {
			fileWriter.write(sb.toString());
		}
		catch (Exception exception) {
			_log("Could not dump swap map: " + exception.getMessage());
		}
	}

	private void _enableIndexAllArticleVersions() throws Exception {
		PortalPreferences portalPreferences =
			PortletPreferencesFactoryUtil.getPortalPreferences(
				TestPropsValues.getUserId(), true);

		_originalPortalPreferencesXML = PortletPreferencesFactoryUtil.toXML(
			portalPreferences);

		portalPreferences.setValue(
			"", "indexAllArticleVersionsEnabled", "true");

		PortalPreferencesLocalServiceUtil.updatePreferences(
			TestPropsValues.getCompanyId(),
			PortletKeys.PREFS_OWNER_TYPE_COMPANY,
			PortletPreferencesFactoryUtil.toXML(portalPreferences));
	}

	private String _join(int[] values) {
		StringBundler sb = new StringBundler();

		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				sb.append(",");
			}

			sb.append(values[i]);
		}

		return sb.toString();
	}

	private void _log(String message) {
		System.out.println("[LPD-98298] " + message);
	}

	private int _maxOf(int[] values) {
		int max = 0;

		for (int value : values) {
			max = Math.max(max, value);
		}

		return max;
	}

	private int[] _parseInts(String propertyName, String defaultValue) {
		String[] parts = StringUtil.split(
			System.getProperty(propertyName, defaultValue));

		int[] values = new int[parts.length];

		for (int i = 0; i < parts.length; i++) {
			values[i] = GetterUtil.getInteger(parts[i].trim());
		}

		return values;
	}

	/**
	 * Builds a swap map of exactly {@code n} entries, taking the window that
	 * starts at {@code offset}. A non-zero offset is how the cold /
	 * across-preview mode produces a different terms set per iteration without
	 * clearing engine caches (clearing would also cold the corpus and
	 * contaminate the measurement).
	 */
	private Serializable _previewSwapMap(int n, int offset) {
		if (n == 0) {
			return null;
		}

		HashMap<Long, Long> swaps = new HashMap<>();

		int size = _pairs.size();

		for (int i = 0; i < n; i++) {
			JournalArticle[] pair = _pairs.get((offset + i) % size);

			swaps.put(pair[0].getId(), pair[1].getId());
		}

		HashMap<String, Serializable> previewSwapMap = new HashMap<>();

		previewSwapMap.put(JournalArticle.class.getName(), swaps);

		return previewSwapMap;
	}

	private void _recordManifest() {
		Map<String, Object> manifest = new LinkedHashMap<>();

		manifest.put("run_id", _RUN_ID);
		manifest.put("git_sha", _GIT_SHA);
		manifest.put("poc", "poc1-integration");
		manifest.put("engine_vendor", _engineVendor);
		manifest.put("engine_version", _engineVersion);
		manifest.put("n_values", _join(_nValues));
		manifest.put("query_variants", StringUtil.merge(_queryVariants, ","));
		manifest.put("result_sizes", _join(_resultSizes));
		manifest.put("concurrency_levels", _join(_concurrencyLevels));
		manifest.put("warmup_iterations", _warmupIterations);
		manifest.put("measure_iterations", _measureIterations);
		manifest.put("measure_duration_seconds", _measureDurationSeconds);
		// PoC 2's portal target needs the scope and the attribute name to build a
		// headless request against the same data this run measured.
		manifest.put("group_id", _group.getGroupId());
		manifest.put("company_id", _group.getCompanyId());
		manifest.put(
			"swap_map_attribute_name", _PREVIEW_SWAP_MAP_ATTRIBUTE_NAME);
		manifest.put("seeded_pairs", _pairs.size());
		manifest.put("background_corpus_size", _backgroundCorpusSize);
		manifest.put("jvm_max_heap_mb", Runtime.getRuntime().maxMemory() / _MB);
		manifest.put(
			"jvm_version", System.getProperty("java.version", "unknown"));

		try (FileWriter fileWriter = new FileWriter(_manifestFile)) {
			fileWriter.write(_toJSON(manifest));
			fileWriter.write("\n");
		}
		catch (Exception exception) {
			_log("Could not write manifest: " + exception.getMessage());
		}
	}

	/**
	 * Phase 2 — State reset. Runs between every cell so a cell never inherits
	 * the previous cell's thread-locals, index drift, or heap pressure.
	 */
	private void _resetState() throws Exception {
		CompanyThreadLocal.setCompanyId(TestPropsValues.getCompanyId());

		UserTestUtil.setUser(TestPropsValues.getUser());

		// A GC hint, not a guarantee. The point is to make heap state between
		// cells more comparable, not to control the collector.

		System.gc();

		Thread.sleep(_resetSettleMillis);

		// Re-assert the corpus is still what the previous cell measured
		// against. A silent index change invalidates cross-cell comparison.

		SearchResponse searchResponse = _search(null, false, 1, null);

		Assert.assertTrue(
			"The index shrank between cells; results are not comparable",
			searchResponse.getCount() >= _pairs.size());
	}

	private void _runCell(_Cell cell) throws Exception {
		_log(
			StringBundler.concat(
				"--- CELL n=", cell.n, " variant=", cell.queryVariant,
				" size=", cell.resultSize, " concurrency=", cell.concurrency,
				" mode=", cell.cacheMode, " ---"));

		_log("PHASE 2: state reset");

		_resetState();

		_log("PHASE 3: warm-up (" + _warmupIterations + " iterations)");

		_runWarmup(cell);

		_log(
			StringBundler.concat(
				"PHASE 4: measurement window (", _measureDurationSeconds,
				"s cap / ", _measureIterations, " iterations, concurrency ",
				cell.concurrency, ")"));

		_runMeasurement(cell);
	}

	/**
	 * Phase 4 — Measurement window. Constant load at {@code cell.concurrency}
	 * for a fixed duration, capped by an iteration budget.
	 */
	private void _runMeasurement(_Cell cell) throws Exception {
		int concurrency = cell.concurrency;

		long deadline =
			System.currentTimeMillis() +
				TimeUnit.SECONDS.toMillis(_measureDurationSeconds);

		int perThread = Math.max(1, _measureIterations / concurrency);

		if (concurrency == 1) {
			for (int i = 0; i < perThread; i++) {
				if (System.currentTimeMillis() > deadline) {
					break;
				}

				_recordSample(cell, _PHASE_MEASURE, i);
			}

			return;
		}

		ExecutorService executorService = Executors.newFixedThreadPool(
			concurrency);

		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(concurrency);
		Queue<Throwable> throwables = new ConcurrentLinkedQueue<>();

		long companyId = TestPropsValues.getCompanyId();

		for (int t = 0; t < concurrency; t++) {
			int threadIndex = t;

			executorService.submit(
				() -> {
					try {
						CompanyThreadLocal.setCompanyId(companyId);

						startLatch.await();

						for (int i = 0; i < perThread; i++) {
							if (System.currentTimeMillis() > deadline) {
								break;
							}

							_recordSample(
								cell, _PHASE_MEASURE,
								(threadIndex * perThread) + i);
						}
					}
					catch (Throwable throwable) {
						throwables.add(throwable);
					}
					finally {
						doneLatch.countDown();
					}
				});
		}

		startLatch.countDown();

		doneLatch.await(_measureDurationSeconds + 120, TimeUnit.SECONDS);

		executorService.shutdownNow();

		if (!throwables.isEmpty()) {
			throw new AssertionError(
				"Measurement window failed", throwables.peek());
		}
	}

	/**
	 * Phase 3 — Warm-up. Light, constant load whose rows are tagged
	 * {@code warmup} and excluded from percentiles by the analyzer.
	 */
	private void _runWarmup(_Cell cell) throws Exception {
		for (int i = 0; i < _warmupIterations; i++) {
			_recordSample(cell, _PHASE_WARMUP, i);
		}
	}

	/**
	 * Keywords are passed explicitly rather than derived from a query-variant
	 * name, because the preparation-phase verification searches need a
	 * different token than the measured searches do.
	 *
	 * @param keywords <code>null</code> for a match-all
	 */
	private SearchResponse _search(
			String keywords, boolean faceted, int resultSize,
			Serializable previewSwapMap)
		throws Exception {

		SearchContext searchContext = SearchContextTestUtil.getSearchContext(
			_group.getGroupId());

		searchContext.setGroupIds(new long[] {_group.getGroupId()});
		searchContext.setUserId(0);

		if (keywords != null) {
			searchContext.setKeywords(keywords);
		}

		if (previewSwapMap != null) {
			searchContext.setAttribute(
				_PREVIEW_SWAP_MAP_ATTRIBUTE_NAME, previewSwapMap);
		}

		SearchRequestBuilder searchRequestBuilder =
			_searchRequestBuilderFactory.builder(
				searchContext
			).emptySearchEnabled(
				true
			).modelIndexerClasses(
				JournalArticle.class
			).size(
				resultSize
			);

		if (faceted) {
			searchRequestBuilder.addAggregation(
				_aggregations.terms("statusAggregation", Field.STATUS));
		}

		return _searcher.search(searchRequestBuilder.build());
	}

	private SearchResponse _searchForCell(_Cell cell, Serializable previewSwapMap)
		throws Exception {

		String keywords = null;

		if (!_MATCH_ALL.equals(cell.queryVariant)) {
			keywords = _CORPUS_TOKEN;
		}

		return _search(
			keywords, _FACETED.equals(cell.queryVariant), cell.resultSize,
			previewSwapMap);
	}

	private String _toJSON(Map<String, Object> map) {
		StringBundler sb = new StringBundler();

		sb.append("{");

		boolean first = true;

		for (Map.Entry<String, Object> entry : map.entrySet()) {
			if (!first) {
				sb.append(",");
			}

			first = false;

			sb.append("\"");
			sb.append(entry.getKey());
			sb.append("\":");

			Object value = entry.getValue();

			if ((value instanceof Number) || (value instanceof Boolean)) {
				sb.append(String.valueOf(value));
			}
			else {
				sb.append("\"");
				sb.append(
					StringUtil.replace(
						String.valueOf(value), new String[] {"\\", "\""},
						new String[] {"\\\\", "\\\""}));
				sb.append("\"");
			}
		}

		sb.append("}");

		return sb.toString();
	}

	/**
	 * Executes one iteration and streams one JSONL row. Records both the
	 * round-trip and the engine {@code took} — their delta is the Liferay-side
	 * plus network cost, which {@code took} alone hides and which is where the
	 * terms-serialization cost shows up.
	 */
	private void _recordSample(_Cell cell, String phase, int iteration)
		throws Exception {

		int offset = 0;

		if (_CACHE_MODE_COLD.equals(cell.cacheMode)) {

			// A different terms window per iteration. Only meaningful if the
			// corpus holds more pairs than N.

			offset = (iteration * cell.n) % Math.max(1, _pairs.size());
		}

		Serializable previewSwapMap = _previewSwapMap(cell.n, offset);

		long start = System.nanoTime();

		SearchResponse searchResponse = _searchForCell(cell, previewSwapMap);

		long roundtripNanos = System.nanoTime() - start;

		long engineTookMillis = -1;

		SearchTimeValue searchTimeValue = searchResponse.getSearchTimeValue();

		if (searchTimeValue != null) {
			engineTookMillis = searchTimeValue.getTimeUnit(
			).toMillis(
				searchTimeValue.getDuration()
			);
		}

		String requestString = searchResponse.getRequestString();

		int requestBytes = 0;

		if (requestString != null) {
			requestBytes = requestString.length();

			if (_PHASE_MEASURE.equals(phase) && (iteration == 0)) {
				_dumpQuery(cell, requestString);
				_dumpSwapMap(cell, offset);
			}
		}

		Map<String, Object> row = new LinkedHashMap<>();

		row.put("run_id", _RUN_ID);
		row.put("timestamp", System.currentTimeMillis());

		// Identifies the code that produced these numbers. Meaningful now that
		// each PoC lives on its own branch; run.sh passes it in.
		row.put("git_sha", _GIT_SHA);
		row.put("poc", "poc1-integration");

		// The request path, as a first-class dimension. PoC 2 emits "engine" and
		// "portal"; this is the in-process portal path. Without it the analyzer
		// would average rows from different request paths into one cell.
		row.put("target", "integration");
		row.put("engine_vendor", _engineVendor);
		row.put("engine_version", _engineVersion);
		row.put("scenario", "preview-search");
		row.put("query_type", cell.queryVariant);
		row.put("result_size", cell.resultSize);
		row.put("n_preview_items", cell.n);
		row.put("concurrency", cell.concurrency);
		row.put("terms_key_type", "uid");
		row.put("cache_mode", cell.cacheMode);
		row.put("phase", phase);
		row.put("iteration", iteration);
		row.put(
			"roundtrip_ms",
			TimeUnit.NANOSECONDS.toMicros(roundtripNanos) / 1000.0);
		row.put("engine_took_ms", engineTookMillis);
		row.put("request_bytes", requestBytes);
		row.put("hits_total", searchResponse.getCount());
		row.put("returned_size", searchResponse.getDocuments().size());

		_recorder.write(_toJSON(row));
	}

	private static final String _CACHE_MODE_BASELINE = "baseline";

	private static final String _CACHE_MODE_COLD = "cold";

	private static final String _CACHE_MODE_WARM = "warm";

	private static final String _CORPUS_TOKEN = "lpd98298corpus";

	private static final String _DRAFT_TOKEN = "lpd98298draft";

	private static final String _FACETED = "faceted";

	private static final String _GIT_SHA = System.getProperty(
		"preview.benchmark.git.sha", "unknown");

	private static final String _MATCH_ALL = "match-all";

	// A per-version id that cannot exist, for the attribute-is-read probe.
	private static final long _NONEXISTENT_CLASS_PK = 999999999L;

	private static final long _MB = 1024L * 1024L;

	private static final String _PHASE_MEASURE = "measure";

	private static final String _PHASE_WARMUP = "warmup";

	// The key the UNPATCHED contributor already reads. PoC 1's whole point is to
	// benchmark with no change to portal code, so it must not require the Option A
	// rename. Override with
	// -Dpreview.benchmark.swap.map.attribute.name=search.experiences.preview.swap.map
	// only if running against a portal that has the Option A patch applied.
	private static final String _PREVIEW_SWAP_MAP_ATTRIBUTE_NAME =
		System.getProperty(
			"preview.benchmark.swap.map.attribute.name", "preview.swap.map");

	private static final String _RUN_ID = System.getProperty(
		"preview.benchmark.run.id", "local-run");

	@Inject
	private Aggregations _aggregations;

	private final boolean _baselineEnabled = GetterUtil.getBoolean(
		System.getProperty("preview.benchmark.baseline.enabled", "true"));
	private final int _backgroundCorpusSize = GetterUtil.getInteger(
		System.getProperty("preview.benchmark.background.corpus.size", "0"));
	private final boolean _coldModeEnabled = GetterUtil.getBoolean(
		System.getProperty("preview.benchmark.cold.mode.enabled", "true"));
	private final int[] _concurrencyLevels = _parseInts(
		"preview.benchmark.concurrency", "1");
	private final String _engineVendor = System.getProperty(
		"preview.benchmark.engine.vendor", "unknown");
	private final String _engineVersion = System.getProperty(
		"preview.benchmark.engine.version", "unknown");

	@DeleteAfterTestRun
	private JournalFolder _folder;

	@DeleteAfterTestRun
	private Group _group;

	private final int _indexReadyTimeoutSeconds = GetterUtil.getInteger(
		System.getProperty(
			"preview.benchmark.index.ready.timeout.seconds", "600"));
	private final String _manifestFile = System.getProperty(
		"preview.benchmark.manifest.file",
		System.getProperty("java.io.tmpdir") + "/lpd98298-manifest.json");
	private final int _measureDurationSeconds = GetterUtil.getInteger(
		System.getProperty(
			"preview.benchmark.measure.duration.seconds", "30"));
	private final int _measureIterations = GetterUtil.getInteger(
		System.getProperty("preview.benchmark.measure.iterations", "300"));
	private final int[] _nValues = _parseInts(
		"preview.benchmark.n.values", "1,10,100,500,1000");
	private String _originalPortalPreferencesXML;
	private final List<JournalArticle[]> _pairs = Collections.synchronizedList(
		new ArrayList<>());
	private final String[] _queryVariants = StringUtil.split(
		System.getProperty(
			"preview.benchmark.query.variants",
			"match-all,keyword,faceted"));
	private final String _queryDumpDir = System.getProperty(
		"preview.benchmark.query.dump.dir",
		System.getProperty("java.io.tmpdir") + "/lpd98298-queries");
	private _Recorder _recorder;
	private final int _resetSettleMillis = GetterUtil.getInteger(
		System.getProperty("preview.benchmark.reset.settle.millis", "500"));
	private final String _resultsFile = System.getProperty(
		"preview.benchmark.results.file",
		System.getProperty("java.io.tmpdir") + "/lpd98298-results.jsonl");
	private final int[] _resultSizes = _parseInts(
		"preview.benchmark.result.sizes", "20");

	@Inject
	private SearchRequestBuilderFactory _searchRequestBuilderFactory;

	@Inject
	private Searcher _searcher;

	@Inject
	private UIDFactory _uidFactory;

	private final int _warmupIterations = GetterUtil.getInteger(
		System.getProperty("preview.benchmark.warmup.iterations", "100"));

	/**
	 * One point in the measurement matrix.
	 */
	private static class _Cell {

		private _Cell(
			int n, String queryVariant, int resultSize, int concurrency,
			String cacheMode) {

			this.n = n;
			this.queryVariant = queryVariant;
			this.resultSize = resultSize;
			this.concurrency = concurrency;
			this.cacheMode = cacheMode;
		}

		private final String cacheMode;
		private final int concurrency;
		private final int n;
		private final String queryVariant;
		private final int resultSize;

	}

	/**
	 * Phase 5 — Artifact collection. Streams one JSONL row per iteration.
	 *
	 * <p>
	 * A file rather than {@code System.out} because this test runs inside the
	 * Liferay container, where stdout goes to the app-server console and is not
	 * capturable by CI (the constraint LPD-97915 hit). JSONL rather than a
	 * formatted line because percentiles must be computed in analysis, not in
	 * the harness — pre-aggregating destroys the ability to re-cut the data.
	 * </p>
	 */
	private class _Recorder {

		private _Recorder() throws Exception {
			_fileWriter = new FileWriter(_resultsFile, true);
		}

		private synchronized void close() throws Exception {
			_fileWriter.flush();
			_fileWriter.close();
		}

		private synchronized void write(String line) {
			try {
				_fileWriter.write(line);
				_fileWriter.write("\n");

				if ((++_lineCount % 100) == 0) {
					_fileWriter.flush();
				}
			}
			catch (Exception exception) {

				// Never fail a benchmark run on an I/O hiccup writing results;
				// a gap in the JSONL is visible in analysis.

				_log("Could not write result row: " + exception.getMessage());
			}
		}

		private final FileWriter _fileWriter;
		private int _lineCount;

	}

}
