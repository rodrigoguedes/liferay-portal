/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.searcher.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.dynamic.data.mapping.service.DDMStructureLocalService;
import com.liferay.journal.model.JournalArticle;
import com.liferay.journal.service.JournalArticleLocalService;
import com.liferay.journal.test.util.search.JournalArticleBlueprint;
import com.liferay.journal.test.util.search.JournalArticleContent;
import com.liferay.journal.test.util.search.JournalArticleDescription;
import com.liferay.journal.test.util.search.JournalArticleSearchFixture;
import com.liferay.journal.test.util.search.JournalArticleTitle;
import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.search.SearchEngine;
import com.liferay.portal.kernel.security.permission.PermissionCheckerFactory;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.rule.DataGuard;
import com.liferay.portal.kernel.test.rule.DeleteAfterTestRun;
import com.liferay.portal.kernel.test.util.TestPropsValues;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.search.document.Document;
import com.liferay.portal.search.searcher.SearchRequestBuilder;
import com.liferay.portal.search.searcher.SearchRequestBuilderFactory;
import com.liferay.portal.search.searcher.SearchResponse;
import com.liferay.portal.search.searcher.Searcher;
import com.liferay.portal.search.test.rule.SearchTestRule;
import com.liferay.portal.test.rule.Inject;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;
import com.liferay.portal.test.rule.PermissionCheckerMethodTestRule;
import com.liferay.users.admin.test.util.search.GroupBlueprint;
import com.liferay.users.admin.test.util.search.GroupSearchFixture;

import java.util.List;
import java.util.Objects;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 * Baseline benchmark + request-size guardrail for LPD-97915 (Story 1 -
 * "Foundation: Baseline Benchmark Suite for Right-Sizing Request Size").
 *
 * <p>
 * For each scenario this test executes a real search against the live search
 * engine, measures the effective number of documents returned (a proxy for the
 * "hits requested" window) and the search engine time, and guards that a search
 * issued without an explicit size does not return more than the configured
 * maximum window.
 * </p>
 *
 * <p>
 * The maximum is read from the system property
 * {@code search.request.size.guard.max.size} and defaults to the current
 * technical ceiling ({@link #_ABSOLUTE_MAX_SIZE}), so the guard is green on
 * {@code master} today while still capturing the baseline. The right-sizing
 * work (LPD-97916 / LPD-97917 / LPD-97919) lowers the enforced default to
 * {@link #_TARGET_DEFAULT_SIZE}; running the search performance job with
 * {@code -Dsearch.request.size.guard.max.size=20} turns this into the gate that
 * those stories must satisfy (and demonstrates the current violation before
 * they land).
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@DataGuard(scope = DataGuard.Scope.METHOD)
@RunWith(Arquillian.class)
public class SearchRequestSizeGuardTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new AggregateTestRule(
			new LiferayIntegrationTestRule(),
			PermissionCheckerMethodTestRule.INSTANCE);

	@Before
	public void setUp() throws Exception {
		_journalArticleSearchFixture = new JournalArticleSearchFixture(
			_ddmStructureLocalService, _journalArticleLocalService, _portal);

		_journalArticleSearchFixture.setUp();

		_addGroupAndUser();

		for (int i = 0; i < _DATASET_SIZE; i++) {
			_addJournalArticle(i);
		}
	}

	@Test
	public void testEmptySearchDefaultSizeGuard() throws Exception {
		if (!_isElasticsearch()) {
			return;
		}

		SearchRequestBuilder searchRequestBuilder =
			_searchRequestBuilderFactory.builder(
			).companyId(
				_group.getCompanyId()
			).emptySearchEnabled(
				true
			).groupIds(
				_group.getGroupId()
			).modelIndexerClassNames(
				JournalArticle.class.getCanonicalName()
			);

		_assertSizeGuard("empty-search", searchRequestBuilder);
	}

	@Test
	public void testExplicitSizeIsHonored() throws Exception {
		if (!_isElasticsearch()) {
			return;
		}

		SearchRequestBuilder searchRequestBuilder =
			_searchRequestBuilderFactory.builder(
			).companyId(
				_group.getCompanyId()
			).groupIds(
				_group.getGroupId()
			).modelIndexerClassNames(
				JournalArticle.class.getCanonicalName()
			).queryString(
				_TOKEN
			).size(
				_TARGET_DEFAULT_SIZE
			);

		int returnedSize = _measure("explicit-size", searchRequestBuilder);

		Assert.assertTrue(
			StringBundler.concat(
				"An explicit size must always be honored, but the scenario ",
				"returned ", returnedSize, " documents for a requested size of ",
				_TARGET_DEFAULT_SIZE),
			returnedSize <= _TARGET_DEFAULT_SIZE);
	}

	@Test
	public void testKeywordSearchDefaultSizeGuard() throws Exception {
		if (!_isElasticsearch()) {
			return;
		}

		SearchRequestBuilder searchRequestBuilder =
			_searchRequestBuilderFactory.builder(
			).companyId(
				_group.getCompanyId()
			).groupIds(
				_group.getGroupId()
			).modelIndexerClassNames(
				JournalArticle.class.getCanonicalName()
			).queryString(
				_TOKEN
			);

		_assertSizeGuard("keyword-search", searchRequestBuilder);
	}

	@Rule
	public SearchTestRule searchTestRule = new SearchTestRule();

	@Rule
	public TestName testName = new TestName();

	protected int _assertSizeGuard(
		String scenario, SearchRequestBuilder searchRequestBuilder) {

		int returnedSize = _measure(scenario, searchRequestBuilder);

		Assert.assertTrue(
			StringBundler.concat(
				"Scenario [", scenario, "] returned ", returnedSize,
				" documents, exceeding the configured maximum ", _maxSize,
				". A search issued without an explicit size must be capped ",
				"before it reaches the search engine."),
			returnedSize <= _maxSize);

		return returnedSize;
	}

	private void _addGroupAndUser() throws Exception {
		GroupSearchFixture groupSearchFixture = new GroupSearchFixture();

		_group = groupSearchFixture.addGroup(new GroupBlueprint());

		_groups = groupSearchFixture.getGroups();

		_user = TestPropsValues.getUser();

		PermissionThreadLocal.setPermissionChecker(
			_permissionCheckerFactory.create(_user));
	}

	private void _addJournalArticle(int index) {
		_journalArticleSearchFixture.addArticle(
			new JournalArticleBlueprint() {
				{
					setGroupId(_group.getGroupId());
					setJournalArticleContent(
						new JournalArticleContent() {
							{
								put(LocaleUtil.US, _TOKEN + " content " + index);

								setDefaultLocale(LocaleUtil.US);
								setName("content");
							}
						});
					setJournalArticleDescription(
						new JournalArticleDescription() {
							{
								put(LocaleUtil.US, _TOKEN);
							}
						});
					setJournalArticleTitle(
						new JournalArticleTitle() {
							{
								put(LocaleUtil.US, _TOKEN);
							}
						});
				}
			});
	}

	private boolean _isElasticsearch() {
		return Objects.equals(_searchEngine.getVendor(), "Elasticsearch");
	}

	private int _measure(
		String scenario, SearchRequestBuilder searchRequestBuilder) {

		SearchResponse searchResponse = _searcher.search(
			searchRequestBuilder.build());

		List<Document> documents = searchResponse.getDocuments();

		int returnedSize = documents.size();

		System.out.println(
			StringBundler.concat(
				"[LPD-97915] scenario=", scenario, " returnedSize=",
				returnedSize, " datasetSize=", _DATASET_SIZE, " searchTime=",
				String.valueOf(searchResponse.getSearchTimeValue())));

		return returnedSize;
	}

	private static final int _ABSOLUTE_MAX_SIZE = 10000;

	private static final int _DATASET_SIZE = 30;

	private static final int _TARGET_DEFAULT_SIZE = 20;

	private static final String _TOKEN = "lpd97915guardrail";

	@Inject
	private DDMStructureLocalService _ddmStructureLocalService;

	private Group _group;

	@DeleteAfterTestRun
	private List<Group> _groups;

	@Inject
	private JournalArticleLocalService _journalArticleLocalService;

	private JournalArticleSearchFixture _journalArticleSearchFixture;

	private final int _maxSize = GetterUtil.getInteger(
		System.getProperty("search.request.size.guard.max.size"),
		_ABSOLUTE_MAX_SIZE);

	@Inject
	private PermissionCheckerFactory _permissionCheckerFactory;

	@Inject
	private Portal _portal;

	@Inject
	private SearchEngine _searchEngine;

	@Inject
	private Searcher _searcher;

	@Inject
	private SearchRequestBuilderFactory _searchRequestBuilderFactory;

	private User _user;

}
