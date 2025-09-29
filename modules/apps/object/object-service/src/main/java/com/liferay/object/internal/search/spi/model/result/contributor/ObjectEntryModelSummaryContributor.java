/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.object.internal.search.spi.model.result.contributor;

import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.language.LanguageUtil;
import com.liferay.portal.kernel.search.Document;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.Summary;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.search.spi.model.result.contributor.ModelSummaryContributor;

import java.util.Locale;
import java.util.Map;

/**
 * @author Bryan Engler
 */
public class ObjectEntryModelSummaryContributor
	implements ModelSummaryContributor {

	@Override
	public Summary getSummary(
		Document document, Locale locale, String snippet) {

		return new Summary(
			locale, _getTitle(document, locale), _getContent(document, locale));
	}

	private String _getContent(Document document, Locale locale) {
		StringBundler sb = new StringBundler();

		Map<String, Field> fields = document.getFields();

		for (Map.Entry<String, Field> entry : fields.entrySet()) {
			String fieldName = entry.getKey();

			if (fieldName.startsWith("snippet_nestedFieldArray.value")) {
				Field field = entry.getValue();

				sb.append(
					StringUtil.merge(
						field.getValues(), StringPool.TRIPLE_PERIOD));

				sb.append(StringPool.TRIPLE_PERIOD);
			}
		}

		if (sb.index() > 0) {
			sb.setIndex(sb.index() - 1);
		}

		String content = sb.toString();

		if (Validator.isBlank(content)) {
			Locale defaultLocale = LocaleUtil.fromLanguageId(
				GetterUtil.getString(
					document.get("defaultLanguageId"),
					LanguageUtil.getLanguageId(LocaleUtil.getSiteDefault())));

			content = _getLocalizedObjectEntryContent(
				document, locale, defaultLocale);
		}

		return content;
	}

	private String _getLocalizedObjectEntryContent(
		Document document, Locale locale) {

		String languageId = LanguageUtil.getLanguageId(locale);

		String content = document.get("objectEntryContent_" + languageId);

		if (!Validator.isBlank(content)) {
			return content;
		}

		return StringPool.BLANK;
	}

	private String _getLocalizedObjectEntryContent(
		Document document, Locale locale, Locale defaultLocale) {

		String content = _getLocalizedObjectEntryContent(document, locale);

		if (Validator.isBlank(content) && !locale.equals(defaultLocale)) {
			content = _getLocalizedObjectEntryContent(document, defaultLocale);
		}

		if (Validator.isBlank(content)) {
			content = document.get("objectEntryContent");
		}

		if (Validator.isBlank(content)) {
			return StringPool.BLANK;
		}

		return StringUtil.shorten(content, 300, StringPool.TRIPLE_PERIOD);
	}

	private String _getTitle(Document document, Locale locale) {
		String title = document.get(
			"snippet_objectEntryTitle_" + LanguageUtil.getLanguageId(locale));

		if (Validator.isBlank(title)) {
			title = document.get(
				"objectEntryTitle_" + LanguageUtil.getLanguageId(locale));
		}

		if (Validator.isBlank(title)) {
			title = document.get("snippet_objectEntryTitle");
		}

		if (Validator.isBlank(title)) {
			title = document.get("objectEntryTitle");
		}

		if (Validator.isBlank(title)) {
			title = document.get("snippet_" + Field.ENTRY_CLASS_PK);
		}

		if (Validator.isBlank(title)) {
			title = document.get(Field.ENTRY_CLASS_PK);
		}

		return title;
	}

}