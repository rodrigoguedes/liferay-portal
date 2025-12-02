/**
 * SPDX-FileCopyrightText: (c) 2025 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2025-06
 */

package com.liferay.portal.search.ml.embedding.text.util;

import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.feature.flag.FeatureFlagManagerUtil;
import com.liferay.portal.kernel.model.BaseModel;
import com.liferay.portal.kernel.search.Document;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.search.ml.embedding.text.TextEmbeddingDocumentContributor;

import java.util.Map;
import java.util.TreeMap;

/**
 * @author Rodrigo Guedes de Souza
 */
public class TextEmbeddingContentHelper<T extends BaseModel<T>> {

	public TextEmbeddingContentHelper(
		long companyId, boolean localizationEnabled, T model, int size,
		boolean unlocalizedEnabled,
		TextEmbeddingDocumentContributor textEmbeddingDocumentContributor) {

		_textEmbeddingDocumentContributor = textEmbeddingDocumentContributor;

		_unlocalizedContentSB = new StringBundler(
			model.getModelAttributes(
			).size());

		if (!localizationEnabled ||
			!FeatureFlagManagerUtil.isEnabled(companyId, "LPS-122920")) {

			return;
		}

		for (String languageId :
				textEmbeddingDocumentContributor.getLanguageIds(model)) {

			_localizedContentSBMap.put(
				languageId,
				new StringBundler(
					model.getModelAttributes(
					).size()));
		}
	}

	public void appendToAll(StringBundler sb) {
		_unlocalizedContentSB.append(sb);

		for (StringBundler localizedContentSB :
				_localizedContentSBMap.values()) {

			localizedContentSB.append(sb);
		}
	}

	public void appendToLocale(String locale, String value) {
		appendToLocale(locale, new StringBundler(value));
	}

	public void appendToLocale(String locale, StringBundler sb) {
		StringBundler localizedContentSB = _localizedContentSBMap.get(locale);

		if (localizedContentSB != null) {
			localizedContentSB.append(sb);
		}
	}

	public void appendToLocaleAndUnlocalized(String locale, String value) {
		appendToLocaleAndUnlocalized(locale, new StringBundler(value));
	}

	public void appendToLocaleAndUnlocalized(String locale, StringBundler sb) {
		_unlocalizedContentSB.append(sb);

		StringBundler localizedContentSB = _localizedContentSBMap.get(locale);

		if (localizedContentSB != null) {
			localizedContentSB.append(sb);
		}
	}

	public void contribute(Document document) {
		for (Map.Entry<String, Field> entry : document.getFields().entrySet()) {
			//TODO
		}
	}

	public Map<String, String> getLocalizedContentMap() {
		Map<String, String> localizedContentMap = new TreeMap<>();

		if (_localizedContentSBMap.isEmpty()) {
			return localizedContentMap;
		}

		for (Map.Entry<String, StringBundler> entry :
				_localizedContentSBMap.entrySet()) {

			StringBundler localizedContentSB = entry.getValue();

			if ((localizedContentSB != null) &&
				(localizedContentSB.length() != 0)) {

				localizedContentMap.put(
					entry.getKey(), localizedContentSB.toString());
			}
		}

		return localizedContentMap;
	}

	public String getUnlocalizedContent() {
		return _unlocalizedContentSB.toString();
	}

	private final Map<String, StringBundler> _localizedContentSBMap =
		new TreeMap<>();
	private final TextEmbeddingDocumentContributor
		_textEmbeddingDocumentContributor;
	private final StringBundler _unlocalizedContentSB;

}