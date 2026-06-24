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
import com.liferay.petra.lang.SafeCloseable;
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
import com.liferay.portal.kernel.util.PortletKeys;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.preview.PreviewableResolverUtil;
import com.liferay.portal.search.aggregation.AggregationResult;
import com.liferay.portal.search.aggregation.Aggregations;
import com.liferay.portal.search.aggregation.bucket.Bucket;
import com.liferay.portal.search.aggregation.bucket.TermsAggregationResult;
import com.liferay.portal.search.document.Document;
import com.liferay.portal.search.legacy.searcher.SearchRequestBuilderFactory;
import com.liferay.portal.search.model.uid.UIDFactory;
import com.liferay.portal.search.searcher.SearchRequestBuilder;
import com.liferay.portal.search.searcher.SearchResponse;
import com.liferay.portal.search.searcher.Searcher;
import com.liferay.portal.search.test.rule.SearchTestRule;
import com.liferay.portal.test.rule.Inject;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Proof-of-concept for LPD-92301: prove that Search can honor a Preview Context
 * Model (swap an approved/live version for a specific draft version) for
 * JournalArticle, entirely inside the search query.
 *
 * <p>
 * This variant runs through the modern {@link Searcher} /
 * {@code SearchRequest} API (the path headless and newer code use), to match
 * the LPD-95367 POC. The swap itself lives in production code
 * ({@code JournalArticleModelPreFilterContributor}, made preview-aware): the
 * test only registers a preview swap map ({@code Map<fromClassPK, toClassPK>},
 * PKs are the per-version {@link JournalArticle#getId()}) with
 * {@link PreviewableResolverUtil} and sets the preview id on the current thread
 * for the duration of the search — the same context the service-layer
 * {@code PreviewableAdvice} reads. No {@code postFilter} and no manual
 * {@code head=false}/{@code status=ANY} relaxation here — the contributor does
 * that, which keeps the swap in the filter context so aggregations honor it
 * (scenario 6).
 * </p>
 *
 * <p>
 * Covers Confluence scenarios 1-9; 4 (Headless) and 5 (FreeMarker) are
 * {@link Ignore}d — see their Javadoc.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@RunWith(Arquillian.class)
public class JournalArticlePreviewSearchTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new LiferayIntegrationTestRule();

	@Before
	public void setUp() throws Exception {
		_group = GroupTestUtil.addGroup();
		_group2 = GroupTestUtil.addGroup();

		UserTestUtil.setUser(TestPropsValues.getUser());

		// Index every version (including drafts) as its own document. Product
		// default, set explicitly to be independent of company configuration.

		PortalPreferences portalPreferences =
			PortletPreferencesFactoryUtil.getPortalPreferences(
				TestPropsValues.getUserId(), true);

		_originalPortalPreferencesXML = PortletPreferencesFactoryUtil.toXML(
			portalPreferences);

		portalPreferences.setValue("", "indexAllArticleVersionsEnabled", "true");

		PortalPreferencesLocalServiceUtil.updatePreferences(
			TestPropsValues.getCompanyId(), PortletKeys.PREFS_OWNER_TYPE_COMPANY,
			PortletPreferencesFactoryUtil.toXML(portalPreferences));

		_folder = JournalTestUtil.addFolder(
			_group.getGroupId(), RandomTestUtil.randomString());
		_folder2 = JournalTestUtil.addFolder(
			_group2.getGroupId(), RandomTestUtil.randomString());
	}

	@After
	public void tearDown() throws Exception {
		PortalPreferencesLocalServiceUtil.updatePreferences(
			TestPropsValues.getCompanyId(), PortletKeys.PREFS_OWNER_TYPE_COMPANY,
			_originalPortalPreferencesXML);
	}

	/**
	 * Scenario 1 - Baseline, no preview context. The default search returns
	 * only the approved/live version; the draft is not discoverable.
	 */
	@Test
	public void testScenario1BaselineNoPreviewContext() throws Exception {
		Article article = _addArticleWithDraft("alpaca", "zebra");

		_assertUIDs(_search("alpaca", null), article.approved);
		_assertUIDs(_search("zebra", null));
	}

	/**
	 * Scenario 2 - Single asset preview (ad-hoc). A one-entry swap map replaces
	 * the approved version with its draft version in the search results,
	 * including draft-only content matching.
	 */
	@Test
	public void testScenario2SingleAssetPreview() throws Exception {
		Article article = _addArticleWithDraft("alpaca", "zebra");
		Article unmapped = _addArticleWithDraft("cobra", "ocelot");

		Map<Serializable, Serializable> journalSwaps = _swaps(
			article.approved, article.draft);

		_assertUIDs(_search("zebra", journalSwaps), article.draft);
		_assertUIDs(_search("alpaca", journalSwaps));
		_assertUIDs(
			_search(null, journalSwaps), article.draft, unmapped.approved);
	}

	/**
	 * Scenario 3 - Launch preview (multi-asset map). Multiple entries are
	 * swapped to their draft versions; unmapped entries pass through.
	 */
	@Test
	public void testScenario3LaunchMultiAssetPreview() throws Exception {
		Article article1 = _addArticleWithDraft("alpaca", "zebra");
		Article article2 = _addArticleWithDraft("beaver", "yak");
		Article unmapped = _addArticleWithDraft("cobra", "ocelot");

		Map<Serializable, Serializable> journalSwaps = _swaps(
			article1.approved, article1.draft);

		journalSwaps.putAll(_swaps(article2.approved, article2.draft));

		_assertUIDs(_search("zebra", journalSwaps), article1.draft);
		_assertUIDs(_search("yak", journalSwaps), article2.draft);
		_assertUIDs(
			_search(null, journalSwaps), article1.draft, article2.draft,
			unmapped.approved);
	}

	/**
	 * Scenario 4 - Headless APIs. OUT OF SCOPE for the search-query POC. The
	 * swap lives in the model pre-filter contributor, which this test now
	 * exercises through the same modern {@link Searcher} API that the headless
	 * backend uses, so the swap applies once the headless resource layer
	 * propagates the preview context onto the request thread (the preview id
	 * read by {@link PreviewableResolverUtil}). Carrying that signal across the
	 * fresh HTTP request thread plus driving the HTTP stack is product wiring,
	 * exactly the Confluence "Concern".
	 */
	@Ignore
	@Test
	public void testScenario4HeadlessApis() {
	}

	/**
	 * Scenario 5 - FreeMarker. OUT OF SCOPE for the search-query POC, same
	 * reasoning as scenario 4: a template invoking search reaches the same
	 * query path; the swap applies once the preview signal is in the
	 * {@code SearchContext}.
	 */
	@Ignore
	@Test
	public void testScenario5FreeMarker() {
	}

	/**
	 * Scenario 6 - Faceting and aggregations reflect the preview version.
	 *
	 * <p>
	 * Asset tags in a JournalArticle are per-asset (per {@code resourcePrimKey}),
	 * not per-version, so the Confluence red/blue tag cannot differ between the
	 * approved and draft docs. We aggregate on the per-version
	 * {@link Field#STATUS}: the previewed entry must contribute its draft's
	 * status ({@code 2}) instead of the live status ({@code 0}). Because the
	 * swap is in the filter context (not a {@code postFilter}), the terms
	 * aggregation is computed over the previewed result set.
	 * </p>
	 */
	@Test
	public void testScenario6FacetsReflectPreview() throws Exception {
		Article article = _addArticleWithDraft("alpaca", "zebra");
		Article unmapped = _addArticleWithDraft("cobra", "ocelot");

		HashMap<String, Long> baselineAggregation = _statusAggregation(null);

		Assert.assertEquals(
			"Baseline: both approved heads counted", Long.valueOf(2),
			baselineAggregation.getOrDefault(
				String.valueOf(WorkflowConstants.STATUS_APPROVED), 0L));
		Assert.assertEquals(
			"Baseline: no draft status in the aggregation", Long.valueOf(0),
			baselineAggregation.getOrDefault(
				String.valueOf(WorkflowConstants.STATUS_DRAFT), 0L));

		Map<Serializable, Serializable> journalSwaps = _swaps(
			article.approved, article.draft);

		HashMap<String, Long> previewAggregation = _statusAggregation(
			journalSwaps);

		Assert.assertEquals(
			"Preview: the previewed entry contributes its draft status",
			Long.valueOf(1),
			previewAggregation.getOrDefault(
				String.valueOf(WorkflowConstants.STATUS_DRAFT), 0L));
		Assert.assertEquals(
			"Preview: only the unmapped entry remains approved", Long.valueOf(1),
			previewAggregation.getOrDefault(
				String.valueOf(WorkflowConstants.STATUS_APPROVED), 0L));

		Assert.assertNotNull(unmapped);
	}

	/**
	 * Scenario 7 - Concurrent previews are isolated. Two threads search the
	 * same entry simultaneously, one with a preview swap map and one without.
	 * The preview id lives in a per-thread {@code ThreadLocal}, so each thread
	 * sees only its own preview context and neither leaks into the other.
	 */
	@Test
	public void testScenario7ConcurrentPreviewsAreIsolated() throws Exception {
		Article article = _addArticleWithDraft("alpaca", "zebra");

		Map<Serializable, Serializable> journalSwaps = _swaps(
			article.approved, article.draft);

		String draftUID = _uidFactory.getUID(article.draft);
		long companyId = TestPropsValues.getCompanyId();
		long groupId = _group.getGroupId();

		int iterations = 25;

		AtomicInteger previewerSawDraft = new AtomicInteger();
		AtomicInteger baselinerSawDraft = new AtomicInteger();

		List<Throwable> throwables = Collections.synchronizedList(
			new ArrayList<>());

		CyclicBarrier cyclicBarrier = new CyclicBarrier(2);

		Thread previewerThread = new Thread(
			() -> _runConcurrentSearch(
				companyId, groupId, "zebra", journalSwaps, iterations,
				cyclicBarrier, throwables,
				searchResponse -> {
					if (_containsUID(searchResponse, draftUID)) {
						previewerSawDraft.incrementAndGet();
					}
				}));
		Thread baselinerThread = new Thread(
			() -> _runConcurrentSearch(
				companyId, groupId, "zebra", null, iterations, cyclicBarrier,
				throwables,
				searchResponse -> {
					if (searchResponse.getCount() > 0) {
						baselinerSawDraft.incrementAndGet();
					}
				}));

		previewerThread.start();
		baselinerThread.start();

		previewerThread.join();
		baselinerThread.join();

		if (!throwables.isEmpty()) {
			throw new AssertionError(
				"Concurrent search failure", throwables.get(0));
		}

		Assert.assertEquals(
			"Previewer thread always saw the draft", iterations,
			previewerSawDraft.get());
		Assert.assertEquals(
			"Baseliner thread never saw the draft (no cross-thread leak)", 0,
			baselinerSawDraft.get());
	}

	/**
	 * Scenario 8 - Cross-group launch. A swap map spanning two groups (a site
	 * and an "asset library") swaps both entries to their drafts. The swap is
	 * keyed by {@link Field#UID}, with no {@code groupId} filter, so a
	 * cross-group preview is not broken.
	 */
	@Test
	public void testScenario8CrossGroupLaunch() throws Exception {
		Article article1 = _addArticleWithDraft("alpaca", "zebra");
		Article article2 = _addArticleWithDraft(
			_group2.getGroupId(), _folder2.getFolderId(), "beaver", "yak");

		Map<Serializable, Serializable> journalSwaps = _swaps(
			article1.approved, article1.draft);

		journalSwaps.putAll(_swaps(article2.approved, article2.draft));

		long[] groupIds = {_group.getGroupId(), _group2.getGroupId()};

		_assertUIDs(_search(groupIds, "zebra", journalSwaps), article1.draft);
		_assertUIDs(_search(groupIds, "yak", journalSwaps), article2.draft);

		SearchResponse searchResponse = _search(groupIds, null, journalSwaps);

		Assert.assertTrue(
			"Group 1 draft present",
			_containsUID(searchResponse, _uidFactory.getUID(article1.draft)));
		Assert.assertTrue(
			"Group 2 draft present",
			_containsUID(searchResponse, _uidFactory.getUID(article2.draft)));
		Assert.assertFalse(
			"Group 1 approved excluded",
			_containsUID(searchResponse, _uidFactory.getUID(article1.approved)));
		Assert.assertFalse(
			"Group 2 approved excluded",
			_containsUID(searchResponse, _uidFactory.getUID(article2.approved)));
	}

	/**
	 * Scenario 9 - Preview context cleared. The preview id is set only for the
	 * duration of each preview search and cleared when it completes, so a
	 * subsequent search with no preview context returns live versions only.
	 */
	@Test
	public void testScenario9PreviewContextCleared() throws Exception {
		Article article = _addArticleWithDraft("alpaca", "zebra");

		Map<Serializable, Serializable> journalSwaps = _swaps(
			article.approved, article.draft);

		_assertUIDs(_search("zebra", journalSwaps), article.draft);

		_assertUIDs(_search("zebra", null));
		_assertUIDs(_search("alpaca", null), article.approved);
	}

	@Rule
	public SearchTestRule searchTestRule = new SearchTestRule();

	private Article _addArticleWithDraft(
			String approvedKeyword, String draftKeyword)
		throws Exception {

		return _addArticleWithDraft(
			_group.getGroupId(), _folder.getFolderId(), approvedKeyword,
			draftKeyword);
	}

	private Article _addArticleWithDraft(
			long groupId, long folderId, String approvedKeyword,
			String draftKeyword)
		throws Exception {

		JournalArticle approved = JournalTestUtil.addArticleWithWorkflow(
			groupId, folderId, "title", approvedKeyword, true);

		ServiceContext serviceContext =
			ServiceContextTestUtil.getServiceContext(groupId);

		serviceContext.setWorkflowAction(WorkflowConstants.ACTION_SAVE_DRAFT);

		JournalArticle draft = JournalTestUtil.updateArticle(
			approved, approved.getTitleMap(),
			DDMStructureTestUtil.getSampleStructuredContent(draftKeyword), false,
			true, serviceContext);

		Assert.assertNotEquals(
			"Approved and draft must be distinct version rows",
			(Long)approved.getId(), (Long)draft.getId());

		return new Article(approved, draft);
	}

	private void _assertUIDs(
		SearchResponse searchResponse, JournalArticle... expectedArticles) {

		List<String> expectedUIDs = new ArrayList<>();

		for (JournalArticle expectedArticle : expectedArticles) {
			expectedUIDs.add(_uidFactory.getUID(expectedArticle));
		}

		Assert.assertEquals(
			searchResponse.getRequestString(), new HashSet<>(expectedUIDs),
			new HashSet<>(_uids(searchResponse)));
	}

	private boolean _containsUID(SearchResponse searchResponse, String uid) {
		for (Document document : searchResponse.getDocuments()) {
			if (uid.equals(document.getString(Field.UID))) {
				return true;
			}
		}

		return false;
	}

	private void _runConcurrentSearch(
		long companyId, long groupId, String keywords,
		Map<Serializable, Serializable> journalSwaps, int iterations,
		CyclicBarrier cyclicBarrier, List<Throwable> throwables,
		Consumer<SearchResponse> searchResponseConsumer) {

		try {
			CompanyThreadLocal.setCompanyId(companyId);

			cyclicBarrier.await(60, TimeUnit.SECONDS);

			for (int i = 0; i < iterations; i++) {
				searchResponseConsumer.accept(
					_search(new long[] {groupId}, keywords, journalSwaps));
			}
		}
		catch (Throwable throwable) {
			throwables.add(throwable);
		}
	}

	private SearchResponse _search(
			long[] groupIds, String keywords,
			Map<Serializable, Serializable> journalSwaps)
		throws Exception {

		SearchContext searchContext = SearchContextTestUtil.getSearchContext(
			groupIds[0]);

		searchContext.setGroupIds(groupIds);
		searchContext.setUserId(0);

		if (keywords != null) {
			searchContext.setKeywords(keywords);
		}

		SearchRequestBuilder searchRequestBuilder =
			_searchRequestBuilderFactory.builder(
				searchContext
			).emptySearchEnabled(
				true
			).modelIndexerClasses(
				JournalArticle.class
			);

		return _searchWithPreviewContext(searchRequestBuilder, journalSwaps);
	}

	private SearchResponse _search(
			String keywords, Map<Serializable, Serializable> journalSwaps)
		throws Exception {

		return _search(
			new long[] {_group.getGroupId()}, keywords, journalSwaps);
	}

	private SearchResponse _searchWithPreviewContext(
			SearchRequestBuilder searchRequestBuilder,
			Map<Serializable, Serializable> journalSwaps)
		throws Exception {

		if (journalSwaps == null) {
			return _searcher.search(searchRequestBuilder.build());
		}

		Long previewId = PreviewableResolverUtil.addPreviewableMap(
			Collections.singletonMap(JournalArticle.class, journalSwaps));

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId)) {

			return _searcher.search(searchRequestBuilder.build());
		}
		finally {
			PreviewableResolverUtil.removePreviewableMap(previewId);
		}
	}

	private HashMap<String, Long> _statusAggregation(
			Map<Serializable, Serializable> journalSwaps)
		throws Exception {

		SearchContext searchContext = SearchContextTestUtil.getSearchContext(
			_group.getGroupId());

		searchContext.setGroupIds(new long[] {_group.getGroupId()});
		searchContext.setUserId(0);

		SearchRequestBuilder searchRequestBuilder =
			_searchRequestBuilderFactory.builder(
				searchContext
			).emptySearchEnabled(
				true
			).modelIndexerClasses(
				JournalArticle.class
			).addAggregation(
				_aggregations.terms("statusAggregation", Field.STATUS)
			);

		SearchResponse searchResponse = _searchWithPreviewContext(
			searchRequestBuilder, journalSwaps);

		HashMap<String, Long> termFrequencies = new HashMap<>();

		AggregationResult aggregationResult =
			searchResponse.getAggregationResult("statusAggregation");

		if (aggregationResult instanceof TermsAggregationResult) {
			TermsAggregationResult termsAggregationResult =
				(TermsAggregationResult)aggregationResult;

			for (Bucket bucket : termsAggregationResult.getBuckets()) {
				termFrequencies.put(bucket.getKey(), bucket.getDocCount());
			}
		}

		return termFrequencies;
	}

	private Map<Serializable, Serializable> _swaps(
		JournalArticle fromArticle, JournalArticle toArticle) {

		Map<Serializable, Serializable> swaps = new HashMap<>();

		swaps.put(fromArticle.getId(), toArticle.getId());

		return swaps;
	}

	private List<String> _uids(SearchResponse searchResponse) {
		List<String> uids = new ArrayList<>();

		for (Document document : searchResponse.getDocuments()) {
			uids.add(document.getString(Field.UID));
		}

		return uids;
	}

	@Inject
	private Aggregations _aggregations;

	@DeleteAfterTestRun
	private JournalFolder _folder;

	@DeleteAfterTestRun
	private JournalFolder _folder2;

	@DeleteAfterTestRun
	private Group _group;

	@DeleteAfterTestRun
	private Group _group2;

	private String _originalPortalPreferencesXML;

	@Inject
	private Searcher _searcher;

	@Inject
	private SearchRequestBuilderFactory _searchRequestBuilderFactory;

	@Inject
	private UIDFactory _uidFactory;

	private static class Article {

		private Article(JournalArticle approved, JournalArticle draft) {
			this.approved = approved;
			this.draft = draft;
		}

		private final JournalArticle approved;
		private final JournalArticle draft;

	}

}
