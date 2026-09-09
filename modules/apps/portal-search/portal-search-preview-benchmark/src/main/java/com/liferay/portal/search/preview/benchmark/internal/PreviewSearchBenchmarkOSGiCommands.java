/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.preview.benchmark.internal;

import com.liferay.journal.model.JournalArticle;
import com.liferay.journal.service.JournalArticleLocalService;
import com.liferay.petra.lang.SafeCloseable;
import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.dao.orm.DynamicQuery;
import com.liferay.portal.kernel.dao.orm.OrderFactoryUtil;
import com.liferay.portal.kernel.dao.orm.ProjectionFactoryUtil;
import com.liferay.portal.kernel.dao.orm.ProjectionList;
import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.dao.orm.RestrictionsFactoryUtil;
import com.liferay.portal.kernel.json.JSONUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.UserConstants;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.IndexWriterHelperUtil;
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.security.auth.CompanyThreadLocal;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.PropsKeys;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.preview.PreviewableResolverUtil;
import com.liferay.portal.search.aggregation.Aggregations;
import com.liferay.portal.search.engine.SearchEngineInformation;
import com.liferay.portal.search.legacy.searcher.SearchRequestBuilderFactory;
import com.liferay.portal.search.searcher.SearchRequestBuilder;
import com.liferay.portal.search.searcher.SearchResponse;
import com.liferay.portal.search.searcher.SearchTimeValue;
import com.liferay.portal.search.searcher.Searcher;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.io.Serializable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.time.Instant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * LPD-98298 performance benchmark: measures how the preview-swap query rewrite
 * (two {@code terms} filters on {@link Field#UID}, built by the model's
 * pre-filter contributor) scales with the number of preview entries (N),
 * against the no-preview baseline.
 *
 * <p>
 * Self-contained deployable module: build with the {@code deploy} Gradle task,
 * drop the resulting jar (from {@code osgi/portal/}) into any environment's
 * {@code osgi/modules/}, then run {@code preview:benchmark} from the Gogo
 * Shell. No portal source or Gradle is needed on the target machine.
 * </p>
 *
 * <p>
 * This component does NOT seed data. Run the LPD-98298 seed pipeline first
 * (corpus of live/draft pairs: an approved v1.0 head plus an indexed v2.0
 * draft per article) and a full reindex. The benchmark discovers the pairs
 * from the database and only issues searches.
 * </p>
 *
 * <p>
 * Configuration is read from
 * {@code ${liferay.home}/preview-benchmark.properties}. All keys are optional;
 * defaults amount to a smoke run. Results are written as JSONL, compatible
 * with the LPD-98298 {@code analyze.py} tool.
 * </p>
 *
 * <p>
 * Two independent knobs describe a run. {@code preview.rewrite} states whether
 * the build under measurement carries the preview query rewrite — it selects
 * which direction the swap probes assert, and appends {@code -nopreview} to the
 * target when false, so the two builds cannot pool into one analyzer cell.
 * {@code sweep} states what to measure: {@code full} walks the N sweep,
 * {@code baseline} measures only the no-preview baseline cells.
 * </p>
 *
 * <p>
 * {@code sweep=baseline} on a build that DOES carry the rewrite is the fast,
 * strictly symmetric half of a baseline-vs-baseline A/B: the rewrite is
 * verified to be present, but the cells measured are the same twelve a control
 * run produces, at the same sample count. Without the rewrite the sweep is
 * baseline-only regardless, since every N would issue the identical query.
 * </p>
 *
 * <p>
 * JournalArticle is the first benchmarked model. To benchmark another model
 * type (e.g. ObjectEntry once per-version indexing lands), the model-specific
 * surface is small: {@link #_discoverPairs()} (how live/draft pairs are found)
 * and the model class registered in the preview map.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@Component(
	property = {
		"osgi.command.function=benchmark", "osgi.command.function=flag",
		"osgi.command.function=ping", "osgi.command.function=reindex",
		"osgi.command.scope=preview"
	},
	service = PreviewSearchBenchmarkOSGiCommands.class
)
public class PreviewSearchBenchmarkOSGiCommands {

	public void benchmark() throws Exception {
		_loadConfiguration();

		_pairs = _discoverPairs();

		if (_pairs.isEmpty()) {
			throw new IllegalStateException(
				"No live/draft pairs found. Run the LPD-98298 seed pipeline " +
					"first.");
		}

		// A control run does not sweep N, but it is only comparable against a
		// preview run over the same corpus — so it is held to the same corpus
		// requirement rather than being allowed to pass on a smaller one.

		int maxN = 0;

		for (int n : _ns) {
			maxN = Math.max(maxN, n);
		}

		if (_pairs.size() < maxN) {
			throw new IllegalStateException(
				"Corpus has " + _pairs.size() + " pairs but the sweep needs " +
					maxN);
		}

		CompanyThreadLocal.setCompanyId(_companyId);

		_assertSwapMechanism();

		Path runDirectoryPath = Paths.get(
			_outputDir, _target + "-" + System.currentTimeMillis());

		Files.createDirectories(runDirectoryPath.resolve("queries"));

		_queriesDirectoryPath = runDirectoryPath.resolve("queries");
		_runId = String.valueOf(runDirectoryPath.getFileName());

		try (BufferedWriter bufferedWriter = Files.newBufferedWriter(
				runDirectoryPath.resolve("results.jsonl"),
				StandardCharsets.UTF_8)) {

			_resultsWriter = bufferedWriter;

			_globalWarmup();

			if (_previewRewrite && _sweep.equals(_SWEEP_FULL)) {
				_runPreviewSweep();
			}
			else {
				_runBaselineSweep();
			}
		}

		System.out.println("[preview-benchmark] Results: " + runDirectoryPath);
	}

	/**
	 * Sets or clears a {@code preview.benchmark.<name>} system property.
	 * The code under measurement reads these at query time, so a
	 * configuration can be switched between runs without a redeploy; every
	 * row of results.jsonl records the flags in effect.
	 */
	public void flag(String name, String value) {
		String key = "preview.benchmark." + name;

		if ((value == null) || value.isEmpty() || value.equals("off")) {
			System.clearProperty(key);

			System.out.println(key + " cleared");

			return;
		}

		System.setProperty(key, value);

		System.out.println(key + "=" + value);
	}

	/**
	 * Writes a file and a log line, so "the console did not run it" can be
	 * told apart from "it ran and failed" without reading the Gogo session.
	 */
	public void ping() throws Exception {
		if (_log.isInfoEnabled()) {
			_log.info("[preview-benchmark] ping");
		}

		Files.write(
			Paths.get(
				System.getProperty("java.io.tmpdir"),
				"preview-benchmark-ping.txt"),
			String.valueOf(
				System.currentTimeMillis()
			).getBytes(
				StandardCharsets.UTF_8
			));

		System.out.println("[preview-benchmark] ping");
	}

	/**
	 * Full reindex of the benchmarked model, for the company the corpus lives
	 * in. The runbook requires one before every run, and on a seeded schema
	 * there is otherwise no way to ask for it: the Control Panel needs a user
	 * the seed never creates, and index.on.startup rides on
	 * PortalInstanceLifecycleListener, which never fires when the instance
	 * fails to register. This calls the index writer directly instead.
	 */
	public void reindex() throws Exception {

		// Everything here is logged as well as printed: a Gogo command's
		// stdout goes to the console session, so a failure would otherwise be
		// invisible to anyone reading the log.

		try {
			_loadConfiguration();

			_pairs = _discoverPairs();

			if (_pairs.isEmpty()) {
				throw new IllegalStateException(
					"No live/draft pairs found, so no company to reindex. " +
						"Run the seed pipeline first.");
			}

			String message = StringBundler.concat(
				"[preview-benchmark] reindexing ",
				JournalArticle.class.getName(), " for company ", _companyId,
				" (", _pairs.size(), " pairs in the corpus)");

			System.out.println(message);

			if (_log.isInfoEnabled()) {
				_log.info(message);
			}

			IndexWriterHelperUtil.reindex(
				UserConstants.USER_ID_DEFAULT, "preview-benchmark-reindex",
				new long[] {_companyId}, JournalArticle.class.getName(),
				new LinkedHashMap<String, Serializable>());

			message =
				"[preview-benchmark] reindex submitted as a background task";

			System.out.println(message);

			if (_log.isInfoEnabled()) {
				_log.info(message);
			}
		}
		catch (Exception exception) {
			_log.error(
				"[preview-benchmark] unable to submit the reindex", exception);

			throw exception;
		}
	}

	/**
	 * Cheap probes that make an entire run trustworthy. The first one always
	 * applies: the baseline sees at least every pair's approved head (the
	 * corpus may also contain a few unpaired default articles). What the two
	 * swap probes assert depends on the build under measurement.
	 *
	 * <p>
	 * With the rewrite present, the contributor must actually read the preview
	 * map: a swap to a nonexistent target drops the count by exactly one, and
	 * a real swap keeps it (the draft document exists in the index and the
	 * include terms match it).
	 * </p>
	 *
	 * <p>
	 * With {@code preview.rewrite=false} the assertion is the mirror image —
	 * neither swap may move the count. That is what separates a control run
	 * from a mislabeled one: the flag alone would happily stamp
	 * {@code -nopreview} on numbers measured with the rewrite active.
	 * </p>
	 */
	private void _assertSwapMechanism() throws Exception {
		SearchResponse baselineSearchResponse = _search("match_all", 1, null);

		long baselineCount = baselineSearchResponse.getTotalHits();

		if (baselineCount < _pairs.size()) {
			throw new IllegalStateException(
				"Baseline returned " + baselineCount + " hits but the " +
					"corpus has " + _pairs.size() + " approved heads (index " +
						"stale? run a full reindex)");
		}

		long[] firstPair = _pairs.get(0);

		Map<Serializable, Serializable> bogusSwaps = new LinkedHashMap<>();

		bogusSwaps.put(firstPair[0], -1L);

		SearchResponse bogusSearchResponse = _search(
			"match_all", 1, bogusSwaps);

		long bogusCount = bogusSearchResponse.getTotalHits();

		Map<Serializable, Serializable> realSwaps = new LinkedHashMap<>();

		realSwaps.put(firstPair[0], firstPair[1]);

		SearchResponse swapSearchResponse = _search("match_all", 1, realSwaps);

		long swapCount = swapSearchResponse.getTotalHits();

		if (!_previewRewrite) {
			if ((bogusCount != baselineCount) || (swapCount != baselineCount)) {
				throw new IllegalStateException(
					StringBundler.concat(
						"The preview map still moves the count (baseline ",
						baselineCount, ", bogus ", bogusCount, ", swap ",
						swapCount, "): this build carries the preview query ",
						"rewrite, so the run would not be a no-preview ",
						"control. Remove \"preview.rewrite=false\" to sweep ",
						"it as the preview half of the A/B."));
			}

			return;
		}

		if (bogusCount != (baselineCount - 1)) {
			throw new IllegalStateException(
				StringBundler.concat(
					"A swap to a nonexistent target must drop the count by ",
					"exactly 1, but it moved from ", baselineCount, " to ",
					bogusCount, ": this build has no preview query rewrite. ",
					"Set \"preview.rewrite=false\" to measure it as the ",
					"control half of the A/B."));
		}

		if (swapCount != baselineCount) {
			throw new IllegalStateException(
				"A real swap must keep the count (draft not indexed? run a " +
					"full reindex with indexAllArticleVersionsEnabled=true)");
		}
	}

	private SearchRequestBuilder _buildSearchRequestBuilder(
		String queryType, int resultSize) {

		SearchContext searchContext = new SearchContext();

		searchContext.setCompanyId(_companyId);
		searchContext.setUserId(0);

		if (queryType.equals("keyword")) {
			searchContext.setKeywords(_keywordTerm);
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

		if (queryType.equals("faceted")) {
			searchRequestBuilder.addAggregation(
				_aggregations.terms("statusAggregation", Field.STATUS));
		}

		return searchRequestBuilder;
	}

	/**
	 * Pairs come from the seeded corpus: v1.0 approved (the live head) and
	 * v2.0 draft of the same {@code resourcePrimKey}. The swap map is keyed by
	 * the per-version {@link JournalArticle#getId()}. This is the
	 * model-specific part of the benchmark.
	 */
	private List<long[]> _discoverPairs() {
		Map<Long, Long> approvedIds = new LinkedHashMap<>();

		for (Object[] row :
				_queryVersions(1, WorkflowConstants.STATUS_APPROVED)) {

			approvedIds.put((Long)row[0], (Long)row[1]);
		}

		List<long[]> pairs = new ArrayList<>();

		for (Object[] row : _queryVersions(2, WorkflowConstants.STATUS_DRAFT)) {
			Long approvedId = approvedIds.get((Long)row[0]);

			if (approvedId != null) {
				pairs.add(new long[] {approvedId, (Long)row[1]});

				if (_companyId == 0) {
					_companyId = (Long)row[2];
				}
			}
		}

		return pairs;
	}

	private String _getFlags() {
		StringBundler sb = new StringBundler();

		for (String name : _FLAG_NAMES) {
			String value = System.getProperty("preview.benchmark." + name);

			if (value == null) {
				continue;
			}

			if (sb.index() > 0) {
				sb.append(';');
			}

			sb.append(name);
			sb.append('=');
			sb.append(value);
		}

		return sb.toString();
	}

	private void _globalWarmup() throws Exception {
		for (int i = 0; i < (_warmupIterations * 2); i++) {
			_search("match_all", 20, null);
		}
	}

	private void _loadConfiguration() throws Exception {
		String liferayHome = PropsUtil.get(PropsKeys.LIFERAY_HOME);

		Properties properties = new Properties();

		File file = new File(liferayHome, "preview-benchmark.properties");

		if (file.exists()) {
			try (Reader reader = new FileReader(file)) {
				properties.load(reader);
			}
		}

		_ns = _toIntArray(properties.getProperty("ns", "1,10,100"));
		_queryTypes = _toStringArray(
			properties.getProperty("query.types", "match_all,keyword"));
		_keywordTerm = properties.getProperty("keyword.term", "test");
		_resultSizes = _toIntArray(
			properties.getProperty("result.sizes", "20"));
		_concurrencies = _toIntArray(
			properties.getProperty("concurrency", "1"));
		_cacheModes = _toStringArray(
			properties.getProperty("cache.modes", "warm,cold"));
		_warmupIterations = Integer.parseInt(
			properties.getProperty("warmup.iterations", "10"));
		_measureIterations = Integer.parseInt(
			properties.getProperty("measure.iterations", "50"));
		_previewRewrite = GetterUtil.getBoolean(
			properties.getProperty("preview.rewrite"), true);

		_sweep = properties.getProperty("sweep", _SWEEP_FULL);

		if (!_sweep.equals(_SWEEP_BASELINE) && !_sweep.equals(_SWEEP_FULL)) {
			throw new IllegalArgumentException(
				StringBundler.concat(
					"Invalid sweep \"", _sweep, "\": expected \"",
					_SWEEP_BASELINE, "\" or \"", _SWEEP_FULL, "\""));
		}

		_target = properties.getProperty("target", "es8-remote");
		_outputDir = properties.getProperty(
			"output.dir", liferayHome + "/preview-benchmark");

		// The analyzer keys a cell by target, and a control run's baseline
		// cell is otherwise indistinguishable from the preview run's own
		// baseline cell — concatenating the two results.jsonl would silently
		// pool the halves of the A/B into one row.

		if (!_previewRewrite) {
			_target += "-nopreview";
		}

		_companyId = 0;

		System.out.println(
			"[preview-benchmark] config=" + file + " exists=" + file.exists() +
				" target=" + _target + " previewRewrite=" + _previewRewrite +
					" sweep=" + _sweep);
	}

	private List<Object[]> _queryVersions(double version, int status) {
		DynamicQuery dynamicQuery = _journalArticleLocalService.dynamicQuery();

		dynamicQuery.add(RestrictionsFactoryUtil.eq("version", version));
		dynamicQuery.add(RestrictionsFactoryUtil.eq("status", status));

		ProjectionList projectionList = ProjectionFactoryUtil.projectionList();

		projectionList.add(ProjectionFactoryUtil.property("resourcePrimKey"));
		projectionList.add(ProjectionFactoryUtil.property("id"));
		projectionList.add(ProjectionFactoryUtil.property("companyId"));

		dynamicQuery.setProjection(projectionList);

		dynamicQuery.addOrder(OrderFactoryUtil.asc("resourcePrimKey"));

		return _journalArticleLocalService.dynamicQuery(
			dynamicQuery, QueryUtil.ALL_POS, QueryUtil.ALL_POS);
	}

	private void _record(
			String queryType, int resultSize, int concurrency, int n,
			String cacheMode, String phase, int iteration, double roundtripMs,
			long engineTookMs, long requestBytes, long hitsTotal)
		throws Exception {

		// Liferay's JSONObject serializes long values as strings (JavaScript
		// precision); analyze.py needs numbers, and these all fit an int.

		String row = JSONUtil.put(
			"cache_mode", cacheMode
		).put(
			"concurrency", concurrency
		).put(
			"engine_took_ms", (int)engineTookMs
		).put(
			"engine_vendor", _searchEngineInformation.getVendorString()
		).put(
			"engine_version", _searchEngineInformation.getClientVersionString()
		).put(
			"flags", _getFlags()
		).put(
			"hits_total", (int)hitsTotal
		).put(
			"iteration", iteration
		).put(
			"n_preview_items", n
		).put(
			"phase", phase
		).put(
			"preview_rewrite", _previewRewrite
		).put(
			"query_type", queryType
		).put(
			"request_bytes", (int)requestBytes
		).put(
			"result_size", resultSize
		).put(
			"roundtrip_ms", Math.round(roundtripMs * 1000) / 1000.0
		).put(
			"run_id", _runId
		).put(
			"sweep", _sweep
		).put(
			"target", _target
		).put(
			"terms_key_type",
			System.getProperty("preview.benchmark.terms.key.type", "uid")
		).put(
			"timestamp", String.valueOf(Instant.now())
		).toString();

		synchronized (this) {
			_resultsWriter.write(row);
			_resultsWriter.write('\n');

			_resultsWriter.flush();
		}
	}

	/**
	 * The no-preview baseline cells alone, one per query shape. This is the
	 * whole of a run on a build without the rewrite (every N would issue the
	 * identical query), and the symmetric counterpart to measure on a build
	 * that has it when the question is whether the rewrite moved the baseline.
	 */
	private void _runBaselineSweep() throws Exception {
		for (String queryType : _queryTypes) {
			for (int resultSize : _resultSizes) {
				for (int concurrency : _concurrencies) {
					_runCell(queryType, resultSize, concurrency, 0, "baseline");
				}
			}
		}
	}

	private void _runCell(
			String queryType, int resultSize, int concurrency, int n,
			String cacheMode)
		throws Exception {

		String cellName =
			queryType + "-size" + resultSize + "-c" + concurrency + "-" +
				cacheMode + "-n" + n;

		System.out.println("[preview-benchmark] cell " + cellName);

		Long warmPreviewId = null;

		if ((n > 0) && cacheMode.equals("warm")) {
			warmPreviewId = PreviewableResolverUtil.addPreviewableMap(
				Map.of(JournalArticle.class, _swapMap(n, 0)));
		}

		try {
			_runIterations(
				queryType, resultSize, concurrency, n, cacheMode, "warmup",
				_warmupIterations, warmPreviewId);
			_runIterations(
				queryType, resultSize, concurrency, n, cacheMode, "measure",
				_measureIterations, warmPreviewId);

			SearchResponse searchResponse = _searchCell(
				queryType, resultSize, n, cacheMode, 0, warmPreviewId);

			Files.write(
				_queriesDirectoryPath.resolve(cellName + ".json"),
				searchResponse.getRequestString(
				).getBytes(
					StandardCharsets.UTF_8
				));
		}
		finally {
			if (warmPreviewId != null) {
				PreviewableResolverUtil.removePreviewableMap(warmPreviewId);
			}
		}
	}

	private void _runIterations(
			String queryType, int resultSize, int concurrency, int n,
			String cacheMode, String phase, int iterations, Long warmPreviewId)
		throws Exception {

		if (concurrency == 1) {
			for (int i = 0; i < iterations; i++) {
				_timeOne(
					queryType, resultSize, concurrency, n, cacheMode, phase, i,
					warmPreviewId);
			}

			return;
		}

		ExecutorService executorService = Executors.newFixedThreadPool(
			concurrency);

		try {
			AtomicInteger iterationCounter = new AtomicInteger();

			List<Throwable> throwables = new ArrayList<>();

			int perWorker = Math.max(1, iterations / concurrency);

			long companyId = _companyId;

			for (int worker = 0; worker < concurrency; worker++) {
				executorService.submit(
					() -> {
						try {
							CompanyThreadLocal.setCompanyId(companyId);

							for (int i = 0; i < perWorker; i++) {
								_timeOne(
									queryType, resultSize, concurrency, n,
									cacheMode, phase,
									iterationCounter.getAndIncrement(),
									warmPreviewId);
							}
						}
						catch (Throwable throwable) {
							synchronized (throwables) {
								throwables.add(throwable);
							}
						}
					});
			}

			executorService.shutdown();

			if (!executorService.awaitTermination(30, TimeUnit.MINUTES)) {
				throw new IllegalStateException("Concurrent cell timed out");
			}

			if (!throwables.isEmpty()) {
				throw new IllegalStateException(
					"Concurrent cell failure", throwables.get(0));
			}
		}
		finally {
			executorService.shutdownNow();
		}
	}

	private void _runPreviewSweep() throws Exception {
		for (int n : _ns) {
			for (String queryType : _queryTypes) {
				for (int resultSize : _resultSizes) {
					for (int concurrency : _concurrencies) {
						_runCell(
							queryType, resultSize, concurrency, 0, "baseline");

						for (String cacheMode : _cacheModes) {
							_runCell(
								queryType, resultSize, concurrency, n,
								cacheMode);
						}
					}
				}
			}
		}
	}

	private SearchResponse _search(
			String queryType, int resultSize,
			Map<Serializable, Serializable> swaps)
		throws Exception {

		SearchRequestBuilder searchRequestBuilder = _buildSearchRequestBuilder(
			queryType, resultSize);

		if (swaps == null) {
			return _searcher.search(searchRequestBuilder.build());
		}

		Long previewId = PreviewableResolverUtil.addPreviewableMap(
			Map.of(JournalArticle.class, swaps));

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId)) {

			return _searcher.search(searchRequestBuilder.build());
		}
		finally {
			PreviewableResolverUtil.removePreviewableMap(previewId);
		}
	}

	/**
	 * One search of a cell: baseline (n=0) runs plain; warm reuses the
	 * registered map (set/unset per search, the product-realistic thread
	 * binding); cold rotates the pair window per iteration so the terms values
	 * differ and the engine query cache cannot amortize them.
	 */
	private SearchResponse _searchCell(
			String queryType, int resultSize, int n, String cacheMode,
			int iteration, Long warmPreviewId)
		throws Exception {

		if (n == 0) {
			return _search(queryType, resultSize, null);
		}

		if (cacheMode.equals("warm")) {
			SearchRequestBuilder searchRequestBuilder =
				_buildSearchRequestBuilder(queryType, resultSize);

			try (SafeCloseable safeCloseable =
					PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
						warmPreviewId)) {

				return _searcher.search(searchRequestBuilder.build());
			}
		}

		return _search(queryType, resultSize, _swapMap(n, iteration * n));
	}

	private Map<Serializable, Serializable> _swapMap(int n, int offset) {
		Map<Serializable, Serializable> swaps = new LinkedHashMap<>();

		for (int i = 0; i < n; i++) {
			long[] pair = _pairs.get((offset + i) % _pairs.size());

			swaps.put(pair[0], pair[1]);
		}

		return swaps;
	}

	private void _timeOne(
			String queryType, int resultSize, int concurrency, int n,
			String cacheMode, String phase, int iteration, Long warmPreviewId)
		throws Exception {

		long startTime = System.nanoTime();

		SearchResponse searchResponse = _searchCell(
			queryType, resultSize, n, cacheMode, iteration, warmPreviewId);

		double roundtripMs = (System.nanoTime() - startTime) / 1000000.0;

		long engineTookMs = -1;

		SearchTimeValue searchTimeValue = searchResponse.getSearchTimeValue();

		if (searchTimeValue != null) {
			TimeUnit timeUnit = searchTimeValue.getTimeUnit();

			engineTookMs = timeUnit.toMillis(searchTimeValue.getDuration());
		}

		String requestString = searchResponse.getRequestString();

		long requestBytes = 0;

		if (requestString != null) {
			requestBytes = requestString.getBytes(
				StandardCharsets.UTF_8).length;
		}

		_record(
			queryType, resultSize, concurrency, n, cacheMode, phase, iteration,
			roundtripMs, engineTookMs, requestBytes,
			searchResponse.getTotalHits());
	}

	private int[] _toIntArray(String value) {
		String[] parts = _toStringArray(value);

		int[] ints = new int[parts.length];

		for (int i = 0; i < parts.length; i++) {
			ints[i] = Integer.parseInt(parts[i]);
		}

		return ints;
	}

	private String[] _toStringArray(String value) {
		String[] parts = value.split(",");

		for (int i = 0; i < parts.length; i++) {
			parts[i] = parts[i].trim();
		}

		return parts;
	}

	private static final String[] _FLAG_NAMES = {
		"numeric.terms", "sortable.field", "terms.key.type"
	};

	private static final String _SWEEP_BASELINE = "baseline";

	private static final String _SWEEP_FULL = "full";

	private static final Log _log = LogFactoryUtil.getLog(
		PreviewSearchBenchmarkOSGiCommands.class);

	@Reference
	private Aggregations _aggregations;

	private String[] _cacheModes;
	private long _companyId;
	private int[] _concurrencies;

	@Reference
	private JournalArticleLocalService _journalArticleLocalService;

	private String _keywordTerm;
	private int _measureIterations;
	private int[] _ns;
	private String _outputDir;
	private List<long[]> _pairs;
	private boolean _previewRewrite;
	private Path _queriesDirectoryPath;
	private String[] _queryTypes;
	private int[] _resultSizes;
	private BufferedWriter _resultsWriter;
	private String _runId;

	@Reference
	private SearchEngineInformation _searchEngineInformation;

	@Reference
	private Searcher _searcher;

	@Reference
	private SearchRequestBuilderFactory _searchRequestBuilderFactory;

	private String _sweep;
	private String _target;
	private int _warmupIterations;

}