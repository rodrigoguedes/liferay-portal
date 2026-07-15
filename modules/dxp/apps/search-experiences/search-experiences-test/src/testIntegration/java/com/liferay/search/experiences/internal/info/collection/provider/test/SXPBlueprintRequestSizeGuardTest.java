/**
 * SPDX-FileCopyrightText: (c) 2025 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.search.experiences.internal.info.collection.provider.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.info.collection.provider.CollectionQuery;
import com.liferay.info.collection.provider.InfoCollectionProvider;
import com.liferay.info.item.InfoItemServiceRegistry;
import com.liferay.info.pagination.InfoPage;
import com.liferay.info.pagination.Pagination;
import com.liferay.journal.constants.JournalFolderConstants;
import com.liferay.journal.model.JournalArticle;
import com.liferay.journal.test.util.JournalTestUtil;
import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceContextThreadLocal;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.rule.DeleteAfterTestRun;
import com.liferay.portal.kernel.test.rule.SynchronousDestinationTestRule;
import com.liferay.portal.kernel.test.util.GroupTestUtil;
import com.liferay.portal.kernel.test.util.RandomTestUtil;
import com.liferay.portal.kernel.test.util.ServiceContextTestUtil;
import com.liferay.portal.kernel.test.util.TestPropsValues;
import com.liferay.portal.kernel.theme.ThemeDisplay;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.test.rule.Inject;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;
import com.liferay.portal.test.rule.PermissionCheckerMethodTestRule;
import com.liferay.search.experiences.model.SXPBlueprint;
import com.liferay.search.experiences.service.SXPBlueprintLocalService;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Request-size guard for the Blueprint-backed collection path (LPD-97915,
 * Story 1 - the "Collection Display Fragment ... Blueprint-backed" scenario).
 *
 * <p>
 * A Blueprint Collection Provider ({@link
 * com.liferay.search.experiences.internal.info.collection.provider.SXPBlueprintInfoCollectionProvider})
 * turns the {@link CollectionQuery} pagination directly into a search-engine
 * request ({@code from}/{@code size}). This test seeds journal articles, builds
 * a blueprint-backed collection provider over them, requests an oversized page,
 * and guards that the number of returned items does not exceed the configured
 * maximum, while logging the baseline.
 * </p>
 *
 * <p>
 * The maximum is read from {@code search.request.size.guard.max.size} and
 * defaults to the technical ceiling (10000), so the guard is green on
 * {@code master} while capturing the baseline. Run with {@code =200} (web max)
 * or {@code =20} (default) to enforce.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@RunWith(Arquillian.class)
public class SXPBlueprintRequestSizeGuardTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new AggregateTestRule(
			new LiferayIntegrationTestRule(),
			PermissionCheckerMethodTestRule.INSTANCE,
			SynchronousDestinationTestRule.INSTANCE);

	@Before
	public void setUp() throws Exception {
		_group = GroupTestUtil.addGroup();

		for (int i = 0; i < _DATASET_SIZE; i++) {
			JournalTestUtil.addArticle(
				_group.getGroupId(),
				JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID);
		}

		_serviceContext = ServiceContextTestUtil.getServiceContext(
			_group.getGroupId(), TestPropsValues.getUserId());

		HttpServletRequest httpServletRequest = new MockHttpServletRequest();

		ThemeDisplay themeDisplay = new ThemeDisplay();

		themeDisplay.setScopeGroupId(_group.getGroupId());

		httpServletRequest.setAttribute(WebKeys.THEME_DISPLAY, themeDisplay);

		_serviceContext.setRequest(httpServletRequest);
	}

	@Test
	public void testBlueprintCollectionFetchAllSizeGuard() throws Exception {
		SXPBlueprint sxpBlueprint = _sxpBlueprintLocalService.addSXPBlueprint(
			RandomTestUtil.randomString(), TestPropsValues.getUserId(),
			_readJSON("configurationJSON"), null, null, StringPool.BLANK,
			Collections.singletonMap(
				LocaleUtil.US, RandomTestUtil.randomString()),
			_serviceContext);

		InfoCollectionProvider<JournalArticle> infoCollectionProvider =
			_infoItemServiceRegistry.getInfoItemService(
				InfoCollectionProvider.class,
				StringBundler.concat(
					SXPBlueprint.class.getName(), StringPool.UNDERLINE,
					sxpBlueprint.getCompanyId(), StringPool.UNDERLINE,
					sxpBlueprint.getExternalReferenceCode()));

		CollectionQuery collectionQuery = new CollectionQuery();

		collectionQuery.setPagination(Pagination.of(_ABUSE_END, 0));

		ServiceContextThreadLocal.pushServiceContext(_serviceContext);

		try {
			InfoPage<JournalArticle> infoPage =
				infoCollectionProvider.getCollectionInfoPage(collectionQuery);

			int returnedSize = infoPage.getPageItems(
			).size();

			System.out.println(
				StringBundler.concat(
					"[LPD-97915][blueprint] scenario=blueprint-fetch-all ",
					"returnedSize=", returnedSize, " totalCount=",
					infoPage.getTotalCount(), " datasetSize=", _DATASET_SIZE));

			Assert.assertTrue(
				StringBundler.concat(
					"The blueprint-backed collection returned ", returnedSize,
					" items, exceeding the configured maximum ", _maxSize,
					". A collection query must be capped before reaching the ",
					"search engine."),
				returnedSize <= _maxSize);
		}
		finally {
			ServiceContextThreadLocal.popServiceContext();
		}
	}

	private String _readJSON(String name) {
		return StringUtil.read(
			_clazz,
			StringBundler.concat(
				"dependencies/", _clazz.getSimpleName(), StringPool.PERIOD, name,
				".json"));
	}

	private static final int _ABUSE_END = 10000;

	private static final int _DATASET_SIZE = 30;

	private final Class<?> _clazz = getClass();

	@DeleteAfterTestRun
	private Group _group;

	@Inject
	private InfoItemServiceRegistry _infoItemServiceRegistry;

	private final int _maxSize = GetterUtil.getInteger(
		System.getProperty("search.request.size.guard.max.size"), 10000);

	private ServiceContext _serviceContext;

	@Inject
	private SXPBlueprintLocalService _sxpBlueprintLocalService;

}
