/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.asset.list.asset.entry.provider.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.asset.kernel.model.AssetEntry;
import com.liferay.asset.list.asset.entry.provider.AssetListAssetEntryProvider;
import com.liferay.asset.list.constants.AssetListEntryTypeConstants;
import com.liferay.asset.list.model.AssetListEntry;
import com.liferay.asset.list.service.AssetListEntryLocalService;
import com.liferay.info.pagination.InfoPage;
import com.liferay.journal.constants.JournalFolderConstants;
import com.liferay.journal.model.JournalArticle;
import com.liferay.journal.test.util.JournalTestUtil;
import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.rule.DeleteAfterTestRun;
import com.liferay.portal.kernel.test.rule.SynchronousDestinationTestRule;
import com.liferay.portal.kernel.test.util.GroupTestUtil;
import com.liferay.portal.kernel.test.util.RandomTestUtil;
import com.liferay.portal.kernel.test.util.ServiceContextTestUtil;
import com.liferay.portal.kernel.test.util.TestPropsValues;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.kernel.util.UnicodeProperties;
import com.liferay.portal.kernel.util.UnicodePropertiesBuilder;
import com.liferay.portal.test.rule.Inject;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;
import com.liferay.portal.test.rule.PermissionCheckerMethodTestRule;
import com.liferay.segments.constants.SegmentsEntryConstants;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Request-size guard for the Asset Publisher / Collection Display Fragment (CDF)
 * dynamic-collection path (LPD-97915, Story 1). Both features fetch a dynamic
 * (search-backed) collection through
 * {@link AssetListAssetEntryProvider#getAssetEntriesInfoPage}, which forwards
 * the requested window to the search engine <b>without</b> applying the web
 * page-size cap (that cap lives in the caller/display layer, e.g.
 * {@code CollectionPaginationUtil} / the Asset Publisher {@code SearchContainer}).
 *
 * <p>
 * This test seeds journal articles, builds a dynamic asset list over them, asks
 * the provider to fetch <em>all</em> ({@link QueryUtil#ALL_POS}) - the common
 * out-of-the-box pattern that resolves to {@code index.search.limit} at the
 * engine - and guards that the number of returned items does not exceed the
 * configured maximum, while logging the baseline.
 * </p>
 *
 * <p>
 * The maximum is read from {@code search.request.size.guard.max.size} and
 * defaults to the technical ceiling (10000), so the guard is green on
 * {@code master} while capturing the baseline. Run with {@code =200} (web max)
 * or {@code =20} (default) to enforce; the right-sizing work (LPD-97919) is what
 * drives those values down.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@RunWith(Arquillian.class)
public class AssetListRequestSizeGuardTest {

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

		_serviceContext = ServiceContextTestUtil.getServiceContext(
			_group.getGroupId(), TestPropsValues.getUserId());

		for (int i = 0; i < _DATASET_SIZE; i++) {
			JournalTestUtil.addArticle(
				_group.getGroupId(),
				JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID,
				"guardrail asset " + i, RandomTestUtil.randomString());
		}
	}

	@Test
	public void testDynamicAssetListFetchAllSizeGuard() throws Exception {
		AssetListEntry assetListEntry =
			_assetListEntryLocalService.addAssetListEntry(
				RandomTestUtil.randomString(), TestPropsValues.getUserId(),
				_group.getGroupId(), RandomTestUtil.randomString(),
				AssetListEntryTypeConstants.TYPE_DYNAMIC,
				_createDynamicTypeSettings(), _serviceContext);

		InfoPage<AssetEntry> infoPage =
			_assetListAssetEntryProvider.getAssetEntriesInfoPage(
				assetListEntry,
				new long[] {SegmentsEntryConstants.ID_DEFAULT}, null, null,
				StringPool.BLANK,
				String.valueOf(TestPropsValues.getUserId()), QueryUtil.ALL_POS,
				QueryUtil.ALL_POS);

		int returnedSize = infoPage.getPageItems(
		).size();

		System.out.println(
			StringBundler.concat(
				"[LPD-97915][asset-list] scenario=dynamic-fetch-all ",
				"returnedSize=", returnedSize, " totalCount=",
				infoPage.getTotalCount(), " datasetSize=", _DATASET_SIZE));

		Assert.assertTrue(
			StringBundler.concat(
				"The Asset Publisher / CDF dynamic collection returned ",
				returnedSize, " items, exceeding the configured maximum ",
				_maxSize,
				". A fetch-all collection query must be capped before reaching ",
				"the search engine."),
			returnedSize <= _maxSize);
	}

	private String _createDynamicTypeSettings() {
		UnicodeProperties unicodeProperties = UnicodePropertiesBuilder.create(
			true
		).put(
			"anyAssetType",
			String.valueOf(_portal.getClassNameId(JournalArticle.class))
		).put(
			"classNameIds", JournalArticle.class.getName()
		).put(
			"groupIds", String.valueOf(_group.getGroupId())
		).build();

		return unicodeProperties.toString();
	}

	private static final int _DATASET_SIZE = 30;

	@Inject
	private AssetListAssetEntryProvider _assetListAssetEntryProvider;

	@Inject
	private AssetListEntryLocalService _assetListEntryLocalService;

	@DeleteAfterTestRun
	private Group _group;

	private final int _maxSize = GetterUtil.getInteger(
		System.getProperty("search.request.size.guard.max.size"), 10000);

	@Inject
	private Portal _portal;

	private ServiceContext _serviceContext;

}
