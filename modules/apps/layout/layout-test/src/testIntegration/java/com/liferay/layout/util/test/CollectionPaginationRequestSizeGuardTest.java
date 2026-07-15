/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.layout.util.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.info.pagination.Pagination;
import com.liferay.layout.util.CollectionPaginationUtil;
import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.util.PropsValues;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Display-layer request-size guard for the Collection Display Fragment (CDF)
 * page size (LPD-97915, Story 1). The CDF resolves its page size through
 * {@link CollectionPaginationUtil#getPagination}, which is where the web
 * page-size cap ({@code search.container.page.max.delta}, default 200) is
 * enforced - as opposed to the collection/asset-list provider, which forwards
 * the window uncapped (see {@code AssetListRequestSizeGuardTest} /
 * {@code SXPBlueprintRequestSizeGuardTest}).
 *
 * <p>
 * This is a fast, deterministic guard (no search engine): it asks for an
 * oversized page and asserts the resulting delta is clamped to
 * {@link PropsValues#SEARCH_CONTAINER_PAGE_MAX_DELTA}. It regression-guards that
 * the clamp is not removed.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@RunWith(Arquillian.class)
public class CollectionPaginationRequestSizeGuardTest {

	@ClassRule
	@Rule
	public static final LiferayIntegrationTestRule liferayIntegrationTestRule =
		new LiferayIntegrationTestRule();

	@Test
	public void testCollectionPaginationClampsPageSize() {
		Pagination pagination = CollectionPaginationUtil.getPagination(
			1, false, _TOTAL_ITEMS, _ABUSE_PAGE_SIZE,
			CollectionPaginationUtil.PAGINATION_TYPE_REGULAR);

		int delta = pagination.getDelta();

		System.out.println(
			StringBundler.concat(
				"[LPD-97915][cdf] scenario=collection-pagination ",
				"requestedPageSize=", _ABUSE_PAGE_SIZE, " effectiveDelta=", delta,
				" maxDelta=", PropsValues.SEARCH_CONTAINER_PAGE_MAX_DELTA));

		Assert.assertTrue(
			StringBundler.concat(
				"Collection Display Fragment page size resolved to delta ", delta,
				", exceeding search.container.page.max.delta ",
				PropsValues.SEARCH_CONTAINER_PAGE_MAX_DELTA,
				". The display-layer page size must be capped."),
			delta <= PropsValues.SEARCH_CONTAINER_PAGE_MAX_DELTA);
	}

	private static final int _ABUSE_PAGE_SIZE = 10000;

	private static final int _TOTAL_ITEMS = 1000;

}
