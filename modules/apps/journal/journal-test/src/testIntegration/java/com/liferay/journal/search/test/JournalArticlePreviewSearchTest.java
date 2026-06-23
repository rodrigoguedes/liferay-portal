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
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.portlet.PortalPreferences;
import com.liferay.portal.kernel.portlet.PortletPreferencesFactoryUtil;
import com.liferay.portal.kernel.search.Document;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.Hits;
import com.liferay.portal.kernel.search.Indexer;
import com.liferay.portal.kernel.search.IndexerRegistryUtil;
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.search.facet.MultiValueFacet;
import com.liferay.portal.kernel.search.facet.collector.FacetCollector;
import com.liferay.portal.kernel.search.facet.collector.TermCollector;
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
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.Arrays;
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
 * The preview context is mocked as a {@link SearchContext} attribute
 * ({@value #_PREVIEW_SWAP_MAP_ATTRIBUTE_NAME}) shaped as
 * {@code Map<entryClassName, Map<fromClassPK, toClassPK>>}, where the PKs are
 * the per-version primary keys ({@link JournalArticle#getId()}). This mirrors
 * the Confluence "Preview Context Model" (fromClassPK = live, toClassPK =
 * preview/draft version).
 * </p>
 *
 * <p>
 * Covers scenarios 1-9 of the Confluence "Search POC: Preview Framework Test
 * Scenarios" page, adapted to JournalArticle. Scenarios 4 (Headless) and 5
 * (FreeMarker) are documented as {@link Ignore}d: see their Javadoc.
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

		// Index every version (including drafts) as its own document. This is
		// the product default, but we set it explicitly to make the test
		// independent of company configuration.

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

		_assertUIDs(_search("alpaca", null), _uid(article.approved));
		_assertUIDs(_search("zebra", null));
	}

	/**
	 * Scenario 2 - Single asset preview (ad-hoc). A one-entry swap map replaces
	 * the approved version with its draft version in the search results.
	 */
	@Test
	public void testScenario2SingleAssetPreview() throws Exception {
		Article article = _addArticleWithDraft("alpaca", "zebra");
		Article unmapped = _addArticleWithDraft("cobra", "ocelot");

		Serializable previewSwapMap = _previewSwapMap(
			_swaps(article.approved, article.draft));

		_assertUIDs(_search("zebra", previewSwapMap), _uid(article.draft));
		_assertUIDs(_search("alpaca", previewSwapMap));
		_assertUIDs(
			_search(null, previewSwapMap), _uid(article.draft),
			_uid(unmapped.approved));
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

		HashMap<Long, Long> swaps = _swaps(article1.approved, article1.draft);

		swaps.putAll(_swaps(article2.approved, article2.draft));

		Serializable previewSwapMap = _previewSwapMap(swaps);

		_assertUIDs(_search("zebra", previewSwapMap), _uid(article1.draft));
		_assertUIDs(_search("yak", previewSwapMap), _uid(article2.draft));
		_assertUIDs(
			_search(null, previewSwapMap), _uid(article1.draft),
			_uid(article2.draft), _uid(unmapped.approved));
	}

	/**
	 * Scenario 4 - Headless APIs. OUT OF SCOPE for the search-query POC.
	 *
	 * <p>
	 * The swap is implemented inside the model pre-filter contributor, which is
	 * applied for every search execution path (headless backend, FreeMarker
	 * restClient, search bar) since they all converge on the same query
	 * building. So the swap works for headless <em>if</em> the headless
	 * resource layer propagates the preview signal into the
	 * {@code SearchContext} ({@value #_PREVIEW_SWAP_MAP_ATTRIBUTE_NAME}). That
	 * propagation (translating a request header/param or a thread-local into
	 * the attribute) is product wiring that does not exist yet, and exercising
	 * the full HTTP stack is beyond the search-query POC. This is exactly the
	 * "Concern" the Confluence page raises for this scenario.
	 * </p>
	 */
	@Ignore
	@Test
	public void testScenario4HeadlessApis() {
	}

	/**
	 * Scenario 5 - FreeMarker. OUT OF SCOPE for the search-query POC, for the
	 * same reason as scenario 4: a FreeMarker template invoking search (via the
	 * search taglib or restClient) reaches the same query path, so the swap
	 * applies once the preview signal is present in the {@code SearchContext}.
	 * Rendering a template and wiring the signal is product integration, not a
	 * search-query concern.
	 */
	@Ignore
	@Test
	public void testScenario5FreeMarker() {
	}

	/**
	 * Scenario 6 - Faceting and aggregations reflect the preview version.
	 *
	 * <p>
	 * The Confluence scenario tags the live entry {@code red} and the draft
	 * {@code blue} and asserts the tag facet counts {@code blue}. Asset tags in
	 * a JournalArticle are per-asset (per {@code resourcePrimKey}), not
	 * per-version, so they cannot differ between the approved and draft docs.
	 * We therefore facet on the per-version {@link Field#STATUS} field: the
	 * previewed entry must contribute its draft's status ({@code 2}) to the
	 * facet instead of the live status ({@code 0}). This proves aggregations
	 * are computed over the previewed (swapped) result set, not the live one.
	 * </p>
	 */
	@Test
	public void testScenario6FacetsReflectPreview() throws Exception {
		Article article = _addArticleWithDraft("alpaca", "zebra");
		Article unmapped = _addArticleWithDraft("cobra", "ocelot");

		Map<String, Integer> baselineFacet = _statusFacet(null);

		Assert.assertEquals(
			"Baseline: both approved heads counted",
			Integer.valueOf(2),
			baselineFacet.getOrDefault(
				String.valueOf(WorkflowConstants.STATUS_APPROVED), 0));
		Assert.assertEquals(
			"Baseline: no draft status in the facet",
			Integer.valueOf(0),
			baselineFacet.getOrDefault(
				String.valueOf(WorkflowConstants.STATUS_DRAFT), 0));

		Serializable previewSwapMap = _previewSwapMap(
			_swaps(article.approved, article.draft));

		Map<String, Integer> previewFacet = _statusFacet(previewSwapMap);

		Assert.assertEquals(
			"Preview: the previewed entry contributes its draft status",
			Integer.valueOf(1),
			previewFacet.getOrDefault(
				String.valueOf(WorkflowConstants.STATUS_DRAFT), 0));
		Assert.assertEquals(
			"Preview: only the unmapped entry remains approved",
			Integer.valueOf(1),
			previewFacet.getOrDefault(
				String.valueOf(WorkflowConstants.STATUS_APPROVED), 0));

		// Unmapped entry is untouched in both cases.

		Assert.assertNotNull(unmapped);
	}

	/**
	 * Scenario 7 - Concurrent previews are isolated. Two threads search the
	 * same entry at the same time: one with a preview swap map, one without.
	 * Because the swap is read from the per-call {@code SearchContext} (no
	 * thread-local or shared mutable state in the contributor), neither thread
	 * leaks into the other.
	 */
	@Test
	public void testScenario7ConcurrentPreviewsAreIsolated() throws Exception {
		Article article = _addArticleWithDraft("alpaca", "zebra");

		Serializable previewSwapMap = _previewSwapMap(
			_swaps(article.approved, article.draft));

		String draftUID = _uid(article.draft);
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
				companyId, groupId, "zebra", previewSwapMap, iterations,
				cyclicBarrier, throwables,
				hits -> {
					if (_containsUID(hits, draftUID)) {
						previewerSawDraft.incrementAndGet();
					}
				}));
		Thread baselinerThread = new Thread(
			() -> _runConcurrentSearch(
				companyId, groupId, "zebra", null, iterations, cyclicBarrier,
				throwables,
				hits -> {
					if (hits.getLength() > 0) {
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
	 * keyed by {@link Field#UID} (primary key), with no {@code groupId} filter,
	 * so a cross-group preview is not broken.
	 */
	@Test
	public void testScenario8CrossGroupLaunch() throws Exception {
		Article article1 = _addArticleWithDraft("alpaca", "zebra");
		Article article2 = _addArticleWithDraft(
			_group2.getGroupId(), _folder2.getFolderId(), "beaver", "yak");

		HashMap<Long, Long> swaps = _swaps(article1.approved, article1.draft);

		swaps.putAll(_swaps(article2.approved, article2.draft));

		Serializable previewSwapMap = _previewSwapMap(swaps);

		long[] groupIds = {_group.getGroupId(), _group2.getGroupId()};

		_assertUIDs(
			_searchGroups(groupIds, "zebra", previewSwapMap),
			_uid(article1.draft));
		_assertUIDs(
			_searchGroups(groupIds, "yak", previewSwapMap),
			_uid(article2.draft));

		Hits hits = _searchGroups(groupIds, null, previewSwapMap);

		Assert.assertTrue(
			"Group 1 draft present", _containsUID(hits, _uid(article1.draft)));
		Assert.assertTrue(
			"Group 2 draft present", _containsUID(hits, _uid(article2.draft)));
		Assert.assertFalse(
			"Group 1 approved excluded",
			_containsUID(hits, _uid(article1.approved)));
		Assert.assertFalse(
			"Group 2 approved excluded",
			_containsUID(hits, _uid(article2.approved)));
	}

	/**
	 * Scenario 9 - Preview context cleared. After a preview search, a
	 * subsequent search on the same thread that does not carry the preview
	 * attribute returns live versions only. There is no thread-local to leak,
	 * so clearing is simply "do not set the attribute".
	 */
	@Test
	public void testScenario9PreviewContextCleared() throws Exception {
		Article article = _addArticleWithDraft("alpaca", "zebra");

		Serializable previewSwapMap = _previewSwapMap(
			_swaps(article.approved, article.draft));

		// Preview search sees the draft...

		_assertUIDs(_search("zebra", previewSwapMap), _uid(article.draft));

		// ...the next search without the attribute is back to live only.

		_assertUIDs(_search("zebra", null));
		_assertUIDs(_search("alpaca", null), _uid(article.approved));
	}

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

	private void _assertUIDs(Hits hits, String... expectedUIDs) {
		List<String> actualUIDs = new ArrayList<>();

		for (Document document : hits.getDocs()) {
			actualUIDs.add(document.get(Field.UID));
		}

		Assert.assertEquals(
			hits.toString(), new HashSet<>(Arrays.asList(expectedUIDs)),
			new HashSet<>(actualUIDs));
	}

	private boolean _containsUID(Hits hits, String uid) {
		for (Document document : hits.getDocs()) {
			if (uid.equals(document.get(Field.UID))) {
				return true;
			}
		}

		return false;
	}

	private Serializable _previewSwapMap(HashMap<Long, Long> journalSwaps) {
		HashMap<String, Serializable> previewSwapMap = new HashMap<>();

		previewSwapMap.put(JournalArticle.class.getName(), journalSwaps);

		return previewSwapMap;
	}

	private void _runConcurrentSearch(
		long companyId, long groupId, String keywords,
		Serializable previewSwapMap, int iterations, CyclicBarrier cyclicBarrier,
		List<Throwable> throwables, Consumer<Hits> hitsConsumer) {

		try {
			CompanyThreadLocal.setCompanyId(companyId);

			cyclicBarrier.await(60, TimeUnit.SECONDS);

			Indexer<JournalArticle> indexer = IndexerRegistryUtil.getIndexer(
				JournalArticle.class);

			for (int i = 0; i < iterations; i++) {
				SearchContext searchContext =
					SearchContextTestUtil.getSearchContext(groupId);

				searchContext.setCompanyId(companyId);
				searchContext.setGroupIds(new long[] {groupId});
				searchContext.setKeywords(keywords);
				searchContext.setUserId(0);

				if (previewSwapMap != null) {
					searchContext.setAttribute(
						_PREVIEW_SWAP_MAP_ATTRIBUTE_NAME, previewSwapMap);
				}

				hitsConsumer.accept(indexer.search(searchContext));
			}
		}
		catch (Throwable throwable) {
			throwables.add(throwable);
		}
	}

	private Hits _search(String keywords, Serializable previewSwapMap)
		throws Exception {

		return _searchGroups(
			new long[] {_group.getGroupId()}, keywords, previewSwapMap);
	}

	private Hits _searchGroups(
			long[] groupIds, String keywords, Serializable previewSwapMap)
		throws Exception {

		SearchContext searchContext = SearchContextTestUtil.getSearchContext(
			groupIds[0]);

		searchContext.setGroupIds(groupIds);
		searchContext.setUserId(0);

		if (keywords != null) {
			searchContext.setKeywords(keywords);
		}

		if (previewSwapMap != null) {
			searchContext.setAttribute(
				_PREVIEW_SWAP_MAP_ATTRIBUTE_NAME, previewSwapMap);
		}

		Indexer<JournalArticle> indexer = IndexerRegistryUtil.getIndexer(
			JournalArticle.class);

		return indexer.search(searchContext);
	}

	private Map<String, Integer> _statusFacet(Serializable previewSwapMap)
		throws Exception {

		SearchContext searchContext = SearchContextTestUtil.getSearchContext(
			_group.getGroupId());

		searchContext.setGroupIds(new long[] {_group.getGroupId()});
		searchContext.setUserId(0);

		if (previewSwapMap != null) {
			searchContext.setAttribute(
				_PREVIEW_SWAP_MAP_ATTRIBUTE_NAME, previewSwapMap);
		}

		MultiValueFacet multiValueFacet = new MultiValueFacet(searchContext);

		multiValueFacet.setFieldName(Field.STATUS);

		searchContext.addFacet(multiValueFacet);

		Indexer<JournalArticle> indexer = IndexerRegistryUtil.getIndexer(
			JournalArticle.class);

		indexer.search(searchContext);

		Map<String, Integer> termFrequencies = new HashMap<>();

		FacetCollector facetCollector = multiValueFacet.getFacetCollector();

		if (facetCollector != null) {
			for (TermCollector termCollector :
					facetCollector.getTermCollectors()) {

				termFrequencies.put(
					termCollector.getTerm(), termCollector.getFrequency());
			}
		}

		return termFrequencies;
	}

	private HashMap<Long, Long> _swaps(
		JournalArticle fromArticle, JournalArticle toArticle) {

		HashMap<Long, Long> swaps = new HashMap<>();

		swaps.put(fromArticle.getId(), toArticle.getId());

		return swaps;
	}

	private String _uid(JournalArticle journalArticle) {

		// Mirrors UIDFactoryImpl production UID format
		// (modelClassName + "_PORTLET_" + primaryKey).

		return JournalArticle.class.getName() + "_PORTLET_" +
			journalArticle.getId();
	}

	private static final String _PREVIEW_SWAP_MAP_ATTRIBUTE_NAME =
		"preview.swap.map";

	@DeleteAfterTestRun
	private JournalFolder _folder;

	@DeleteAfterTestRun
	private JournalFolder _folder2;

	@DeleteAfterTestRun
	private Group _group;

	@DeleteAfterTestRun
	private Group _group2;

	private String _originalPortalPreferencesXML;

	private static class Article {

		private Article(JournalArticle approved, JournalArticle draft) {
			this.approved = approved;
			this.draft = draft;
		}

		private final JournalArticle approved;
		private final JournalArticle draft;

	}

}
