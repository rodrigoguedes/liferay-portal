/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.semantic;

import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.search.semantic.SemanticFieldNames;
import com.liferay.portal.search.semantic.SemanticProviderType;

import java.util.Locale;

import org.osgi.service.component.annotations.Component;

/**
 * @author Rodrigo Guedes
 */
@Component(service = SemanticFieldNames.class)
public class SemanticFieldNamesImpl implements SemanticFieldNames {

	@Override
	public String fieldName(
		Locale locale, SemanticProviderType semanticProviderType,
		String assetType, int dimensions) {

		if (locale == null) {
			throw new IllegalArgumentException("Locale is null");
		}

		if (semanticProviderType == null) {
			throw new IllegalArgumentException(
				"Semantic provider type is null");
		}

		String languageId = LocaleUtil.toLanguageId(locale);

		String fieldName;

		if (semanticProviderType == SemanticProviderType.BYO_LLM) {
			if (Validator.isNull(assetType)) {
				throw new IllegalArgumentException(
					"Asset type is null or empty");
			}

			fieldName = StringBundler.concat(
				assetType, StringPool.UNDERLINE, languageId, "_semantic");
		}
		else {
			if (dimensions <= 0) {
				throw new IllegalArgumentException(
					"Dimensions must be positive: " + dimensions);
			}

			fieldName = StringBundler.concat(
				"text_embedding_", dimensions, StringPool.UNDERLINE,
				languageId);
		}

		return fieldName;
	}

}