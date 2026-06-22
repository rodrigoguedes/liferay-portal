/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.preview;

import com.liferay.portal.kernel.search.SearchContext;

import java.io.Serializable;

import java.util.Collections;
import java.util.Map;

/**
 * Preview Framework POC (LPD-92301): mock of the Preview Context Model on the
 * search side.
 *
 * <p>
 * The swap map is carried as a {@link SearchContext} attribute keyed by {@link
 * #ATTRIBUTE}. Its shape mirrors the Preview Context Model:
 * </p>
 *
 * <pre>
 * Map&lt;String entryClassName, Map&lt;Long fromClassPK, Long toClassPK&gt;&gt;
 * </pre>
 *
 * <p>
 * <code>fromClassPK</code> is the live (approved) primary key and
 * <code>toClassPK</code> is the previewed (draft) primary key. The presence of
 * a non-empty map for an entry class name means preview mode is active for that
 * class.
 * </p>
 *
 * <p>
 * In the actual implementation this attribute would be replaced by a read
 * against the shared preview context model (a thread local promoted to
 * portal-kernel) so that the local service AOP advice and the search layer
 * resolve the same source of truth.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
public class PreviewSearchContext {

	public static final String ATTRIBUTE = "preview.swap.map";

	@SuppressWarnings("unchecked")
	public static Map<Long, Long> getSwapMap(
		SearchContext searchContext, String entryClassName) {

		Serializable attribute = searchContext.getAttribute(ATTRIBUTE);

		if (!(attribute instanceof Map)) {
			return Collections.emptyMap();
		}

		Map<String, Map<Long, Long>> swapMaps =
			(Map<String, Map<Long, Long>>)attribute;

		Map<Long, Long> swapMap = swapMaps.get(entryClassName);

		if (swapMap == null) {
			return Collections.emptyMap();
		}

		return swapMap;
	}

}
