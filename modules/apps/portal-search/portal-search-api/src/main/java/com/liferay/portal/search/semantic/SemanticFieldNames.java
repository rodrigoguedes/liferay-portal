/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

import com.liferay.petra.string.CharPool;
import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.StringUtil;

import java.util.Locale;

/**
 * Single point that resolves the embedding field name for a given locale and
 * active strategy, so that the contributor, query builder, and Blueprint
 * context variable stay oblivious to which strategy is active.
 *
 * <ul>
 * <li>{@link SemanticTextEmbeddingProviderType#ELASTICSEARCH_PROVIDED} →
 * {@code <assetType>_<lang>_semantic} (a {@code semantic_text} field; the
 * dimensions are owned by Elasticsearch and are not part of the name).</li>
 * <li>{@link SemanticTextEmbeddingProviderType#LIFERAY_PROVIDED} →
 * {@code text_embedding_<dimensions>_<lang>} (the existing {@code
 * dense_vector} field).</li>
 * </ul>
 *
 * <p>
 * The field <em>types</em> differ between strategies and Elasticsearch does
 * not allow two types under one name, so this helper does not try to unify
 * them; switching strategies requires a full reindex by design.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
public class SemanticFieldNames {

	/**
	 * Derives the short, stable asset-type token used in a {@code
	 * <assetType>_<lang>_semantic} field name from a model class name. The same
	 * token must be produced both when the index mapping is built and when the
	 * document is contributed, so this is the single point that defines the
	 * convention (the lowercased simple class name, e.g. {@code
	 * com.liferay.blogs.model.BlogsEntry} → {@code blogsentry}).
	 */
	public static String assetType(String modelClassName) {
		return StringUtil.toLowerCase(
			modelClassName.substring(
				modelClassName.lastIndexOf(CharPool.PERIOD) + 1));
	}

	public static String fieldName(
		Locale locale,
		SemanticTextEmbeddingProviderType semanticTextEmbeddingProviderType,
		String assetType, int dimensions) {

		return fieldName(
			LocaleUtil.toLanguageId(locale), semanticTextEmbeddingProviderType,
			assetType, dimensions);
	}

	public static String fieldName(
		String languageId,
		SemanticTextEmbeddingProviderType semanticTextEmbeddingProviderType,
		String assetType, int dimensions) {

		if (semanticTextEmbeddingProviderType ==
				SemanticTextEmbeddingProviderType.ELASTICSEARCH_PROVIDED) {

			return StringBundler.concat(
				assetType, StringPool.UNDERLINE, languageId, "_semantic");
		}

		return StringBundler.concat(
			"text_embedding_", dimensions, StringPool.UNDERLINE, languageId);
	}

	private SemanticFieldNames() {
	}

}