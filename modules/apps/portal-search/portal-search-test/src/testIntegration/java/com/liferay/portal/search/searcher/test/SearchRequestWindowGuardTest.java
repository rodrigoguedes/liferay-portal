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
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.search.SearchEngine;
import com.liferay.portal.kernel.security.permission.PermissionCheckerFactory;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.rule.DataGuard;
import com.liferay.portal.kernel.test.rule.DeleteAfterTestRun;
import com.liferay.portal.kernel.test.util.TestPropsValues;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.search.engine.SearchEngineInformation;
import com.liferay.portal.search.searcher.SearchRequestBuilder;
import com.liferay.portal.search.searcher.SearchRequestBuilderFactory;
import com.liferay.portal.search.searcher.SearchRequestWindowLimitExceededException;
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
import org.junit.runner.RunWith;

/**
 * Verifies the LPD-64988 deep-pagination guardrail: a request whose result
 * window ({@code from + size}) exceeds the engine's
 * {@code index.max_result_window} is rejected with
 * {@link SearchRequestWindowLimitExceededException}, while requests within the
 * window run normally.
 *
 * <p>
 * The guard runs at the entry of the permission filter, on the original
 * user-facing request, before the sliding window slices it into amplification
 * re-queries. So it targets the guest/attacker-reachable path (the epic scope)
 * and does not break permission filtering.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@DataGuard(scope = DataGuard.Scope.METHOD)
@RunWith(Arquillian.class)
public class SearchRequestWindowGuardTest {

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
	public void testDeepPaginationBeyondWindowRejected() throws Exception {
		if (!_isElasticsearch()) {
			return;
		}

		int maxResultWindow = _searchEngineInformation.getMaxResultWindow();

		try {
			_search(maxResultWindow, 20);

			Assert.fail(
				"A request with from=" + maxResultWindow + " size=20 (from + " +
					"size beyond index.max_result_window=" + maxResultWindow +
						") must be rejected, but no exception was thrown");
		}
		catch (SearchRequestWindowLimitExceededException
					searchRequestWindowLimitExceededException) {

			Assert.assertEquals(
				maxResultWindow,
				searchRequestWindowLimitExceededException.getMaxResultWindow());
		}
	}

	@Test
	public void testRequestAtWindowBoundaryAllowed() throws Exception {
		if (!_isElasticsearch()) {
			return;
		}

		int maxResultWindow = _searchEngineInformation.getMaxResultWindow();

		// from + size == window is exactly at the ceiling and must be allowed.

		_search(maxResultWindow - 20, 20);
	}

	@Test
	public void testShallowRequestAllowed() throws Exception {
		if (!_isElasticsearch()) {
			return;
		}

		SearchResponse searchResponse = _search(0, 20);

		Assert.assertNotNull(searchResponse);
	}

	@Rule
	public SearchTestRule searchTestRule = new SearchTestRule();

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

	private SearchResponse _search(int from, int size) {
		SearchRequestBuilder searchRequestBuilder =
			_searchRequestBuilderFactory.builder(
			).companyId(
				_group.getCompanyId()
			).from(
				from
			).groupIds(
				_group.getGroupId()
			).modelIndexerClassNames(
				JournalArticle.class.getCanonicalName()
			).queryString(
				_TOKEN
			).size(
				size
			);

		return _searcher.search(searchRequestBuilder.build());
	}

	private static final int _DATASET_SIZE = 5;

	private static final String _TOKEN = "lpd64988windowguard";

	@Inject
	private DDMStructureLocalService _ddmStructureLocalService;

	private Group _group;

	@DeleteAfterTestRun
	private List<Group> _groups;

	@Inject
	private JournalArticleLocalService _journalArticleLocalService;

	private JournalArticleSearchFixture _journalArticleSearchFixture;

	@Inject
	private PermissionCheckerFactory _permissionCheckerFactory;

	@Inject
	private Portal _portal;

	@Inject
	private SearchEngine _searchEngine;

	@Inject
	private SearchEngineInformation _searchEngineInformation;

	@Inject
	private Searcher _searcher;

	@Inject
	private SearchRequestBuilderFactory _searchRequestBuilderFactory;

	private User _user;

}
