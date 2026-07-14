/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.rest.resource.v1_0.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.journal.test.util.JournalTestUtil;
import com.liferay.layout.test.util.LayoutTestUtil;
import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.model.Company;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.model.Layout;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.service.CompanyLocalServiceUtil;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.util.GroupTestUtil;
import com.liferay.portal.kernel.test.util.UserTestUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.portal.kernel.util.PropsValues;
import com.liferay.portal.search.rest.client.dto.v1_0.Suggestion;
import com.liferay.portal.search.rest.client.dto.v1_0.SuggestionsContributorConfiguration;
import com.liferay.portal.search.rest.client.dto.v1_0.SuggestionsContributorResults;
import com.liferay.portal.search.rest.client.pagination.Page;
import com.liferay.portal.search.rest.client.resource.v1_0.SuggestionResource;
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
 * Headless {@code /o/search/v1.0/suggestions} counterpart of the request-size
 * guard for LPD-97915 (Story 1 - Baseline Benchmark Suite).
 *
 * <p>
 * Posts a "basic" suggestions contributor and records how many suggestions come
 * back (baseline capture), guarding that the count does not exceed the
 * configured maximum. The suggestion {@code size} is set on the contributor
 * configuration; the default scenario leaves it unset (server default 5) and the
 * max scenario requests an oversized value.
 * </p>
 *
 * <p>
 * The maximum is read from the system property
 * {@code search.request.size.guard.max.suggestions.size} and defaults to a
 * lenient value so the guard is green on {@code master} while capturing the
 * baseline. Run with {@code =50} (the proposed suggestions maximum) or {@code =5}
 * (the default) to enforce; LPD-97917 is what drives those values down.
 * </p>
 *
 * <p>
 * Caveat: the effective suggestion count depends on how the "basic" contributor
 * derives suggestions from matching content, which is not directly observable
 * from the HTTP response. This test therefore captures the baseline and enforces
 * an upper bound; proving the exact cap may require confirming the contributor's
 * behavior on a live index.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@RunWith(Arquillian.class)
public class SuggestionRequestSizeHeadlessGuardTest {

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

		_suggestionResource = SuggestionResource.builder(
		).authentication(
			adminUser.getEmailAddress(), PropsValues.DEFAULT_ADMIN_PASSWORD
		).endpoint(
			company.getVirtualHostname(),
			PortalUtil.getPortalServerPort(false), "http"
		).locale(
			LocaleUtil.getDefault()
		).build();

		_layout = LayoutTestUtil.addTypePortletLayout(_group);

		for (int i = 0; i < _DATASET_SIZE; i++) {
			JournalTestUtil.addArticle(
				_group.getGroupId(), _TOKEN + " " + i, _TOKEN + " content " + i);
		}
	}

	@After
	public void tearDown() throws Exception {
		GroupTestUtil.deleteGroup(_group);
	}

	@Test
	public void testSuggestionsDefaultSizeGuard() throws Exception {
		_assertSuggestionsSizeGuard(
			"suggestions-default", _postSuggestions(null));
	}

	@Test
	public void testSuggestionsMaxSizeGuard() throws Exception {
		_assertSuggestionsSizeGuard(
			"suggestions-max-size", _postSuggestions(_ABUSE_SIZE));
	}

	private void _assertSuggestionsSizeGuard(
		String scenario, Page<SuggestionsContributorResults> page) {

		int suggestionsCount = _countSuggestions(page);

		System.out.println(
			StringBundler.concat(
				"[LPD-97915][headless] scenario=", scenario,
				" suggestionsCount=", suggestionsCount, " datasetSize=",
				_DATASET_SIZE));

		Assert.assertTrue(
			StringBundler.concat(
				"Suggestions scenario [", scenario, "] returned ",
				suggestionsCount, " suggestions, exceeding the configured ",
				"maximum ", _maxSize,
				". Guest-facing suggestions must be capped before reaching the ",
				"search engine."),
			suggestionsCount <= _maxSize);
	}

	private int _countSuggestions(Page<SuggestionsContributorResults> page) {
		int total = 0;

		for (SuggestionsContributorResults suggestionsContributorResults :
				page.getItems()) {

			Suggestion[] suggestions =
				suggestionsContributorResults.getSuggestions();

			if (suggestions != null) {
				total += suggestions.length;
			}
		}

		return total;
	}

	private SuggestionsContributorConfiguration
		_createBasicSuggestionsContributorConfiguration(Integer size) {

		SuggestionsContributorConfiguration
			suggestionsContributorConfiguration =
				new SuggestionsContributorConfiguration();

		suggestionsContributorConfiguration.setContributorName("basic");
		suggestionsContributorConfiguration.setDisplayGroupName("Suggestions");

		if (size != null) {
			suggestionsContributorConfiguration.setSize(size);
		}

		return suggestionsContributorConfiguration;
	}

	private Page<SuggestionsContributorResults> _postSuggestions(Integer size)
		throws Exception {

		return _suggestionResource.postSuggestionsPage(
			"http://localhost:" + PortalUtil.getPortalServerPort(false) +
				"/web/guest/home",
			"/search", null, "q", _layout.getPlid(), null, _TOKEN,
			new SuggestionsContributorConfiguration[] {
				_createBasicSuggestionsContributorConfiguration(size)
			});
	}

	private static final int _ABUSE_SIZE = 1000;

	private static final int _DATASET_SIZE = 60;

	private static final String _TOKEN = "guardrailsuggest";

	private Group _group;

	private Layout _layout;

	private final int _maxSize = GetterUtil.getInteger(
		System.getProperty("search.request.size.guard.max.suggestions.size"),
		1000);

	private SuggestionResource _suggestionResource;

}
