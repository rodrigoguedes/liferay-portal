/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.rest.resource.v1_0.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.journal.test.util.JournalTestUtil;
import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.model.Company;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.service.CompanyLocalServiceUtil;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.util.GroupTestUtil;
import com.liferay.portal.kernel.test.util.UserTestUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.portal.kernel.util.PropsValues;
import com.liferay.portal.search.rest.client.dto.v1_0.SearchResult;
import com.liferay.portal.search.rest.client.pagination.Page;
import com.liferay.portal.search.rest.client.pagination.Pagination;
import com.liferay.portal.search.rest.client.resource.v1_0.SearchResultResource;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;
import com.liferay.portal.test.rule.PermissionCheckerMethodTestRule;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Headless counterpart of {@code SearchRequestSizeGuardTest} for LPD-97915
 * (Story 1 - Baseline Benchmark Suite).
 *
 * <p>
 * Exercises the Search headless API ({@code /o/search/v1.0/search}) and guards
 * that a guest-facing request cannot obtain an oversized page. For each scenario
 * it records the returned item count, the effective page size echoed by the
 * response, and the total count (baseline capture), and asserts that neither the
 * page size nor the returned items exceed the configured maximum.
 * </p>
 *
 * <p>
 * The maximum is read from the system property
 * {@code search.request.size.guard.max.page.size} and defaults to the technical
 * ceiling (10000), so the guard is green on {@code master} today while still
 * capturing the baseline. Run with
 * {@code -Dsearch.request.size.guard.max.page.size=500} to enforce the headless
 * maximum, or {@code =20} to enforce the default; the right-sizing work
 * (LPD-97917) is what drives those values down.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@RunWith(Arquillian.class)
public class SearchRequestSizeHeadlessGuardTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new AggregateTestRule(
			new LiferayIntegrationTestRule(),
			PermissionCheckerMethodTestRule.INSTANCE);

	@Before
	public void setUp() throws Exception {
		_group = GroupTestUtil.addGroup();

		Company company = CompanyLocalServiceUtil.getCompany(
			_group.getCompanyId());

		User adminUser = UserTestUtil.getAdminUser(company.getCompanyId());

		_searchResultResource = SearchResultResource.builder(
		).authentication(
			adminUser.getEmailAddress(), PropsValues.DEFAULT_ADMIN_PASSWORD
		).endpoint(
			company.getVirtualHostname(),
			PortalUtil.getPortalServerPort(false), "http"
		).locale(
			LocaleUtil.getDefault()
		).build();

		for (int i = 0; i < _DATASET_SIZE; i++) {
			JournalTestUtil.addArticle(
				_group.getGroupId(), _TOKEN, _TOKEN + " content " + i);
		}
	}

	@After
	public void tearDown() throws Exception {
		GroupTestUtil.deleteGroup(_group);
	}

	@Test
	public void testSearchDefaultPageSizeGuard() throws Exception {
		Page<SearchResult> page = _searchResultResource.getSearchPage(
			null, Boolean.TRUE, null, String.valueOf(_group.getGroupId()), null,
			null, null, null);

		_assertPageSizeGuard("search-default", page);
	}

	@Test
	public void testSearchMaxPageSizeGuard() throws Exception {
		Page<SearchResult> page = _searchResultResource.getSearchPage(
			null, Boolean.TRUE, null, String.valueOf(_group.getGroupId()), null,
			null, Pagination.of(1, _ABUSE_PAGE_SIZE), null);

		_assertPageSizeGuard("search-max-page-size", page);
	}

	private void _assertPageSizeGuard(String scenario, Page<SearchResult> page) {
		int itemsCount = page.getItems(
		).size();

		long pageSize = page.getPageSize();

		System.out.println(
			StringBundler.concat(
				"[LPD-97915][headless] scenario=", scenario, " itemsCount=",
				itemsCount, " pageSize=", pageSize, " totalCount=",
				page.getTotalCount()));

		Assert.assertTrue(
			StringBundler.concat(
				"Headless scenario [", scenario, "] resolved to page size ",
				pageSize, " and returned ", itemsCount,
				" items, exceeding the configured maximum ", _maxPageSize,
				". Guest-facing search requests must be capped before reaching ",
				"the search engine."),
			(pageSize <= _maxPageSize) && (itemsCount <= _maxPageSize));
	}

	private static final int _ABUSE_PAGE_SIZE = 10000;

	private static final int _DATASET_SIZE = 30;

	private static final String _TOKEN = "lpd97915headless";

	private Group _group;

	private final int _maxPageSize = GetterUtil.getInteger(
		System.getProperty("search.request.size.guard.max.page.size"), 10000);

	private SearchResultResource _searchResultResource;

}
