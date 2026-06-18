/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.test.rule.LiferayUnitTestRule;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * @author Rodrigo Guedes de Souza
 */
public class SemanticFieldNamesTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Test
	public void testAssetType() {
		Assert.assertEquals(
			"blogsentry",
			SemanticFieldNames.assetType("com.liferay.blogs.model.BlogsEntry"));
	}

	@Test
	public void testAssetTypeWithoutPackage() {
		Assert.assertEquals(
			"customobject", SemanticFieldNames.assetType("CustomObject"));
	}

	@Test
	public void testFieldNameWithElasticsearchProvided() {
		Assert.assertEquals(
			"blogsentry_en_US_semantic",
			SemanticFieldNames.fieldName(
				LocaleUtil.toLanguageId(LocaleUtil.US),
				SemanticTextEmbeddingProviderType.ELASTICSEARCH_PROVIDED,
				"blogsentry", 0));
	}

	@Test
	public void testFieldNameWithElasticsearchProvidedForEachLocale() {
		Assert.assertEquals(
			"blogsentry_en_US_semantic",
			SemanticFieldNames.fieldName(
				"en_US",
				SemanticTextEmbeddingProviderType.ELASTICSEARCH_PROVIDED,
				"blogsentry", 0));
		Assert.assertEquals(
			"blogsentry_ja_JP_semantic",
			SemanticFieldNames.fieldName(
				"ja_JP",
				SemanticTextEmbeddingProviderType.ELASTICSEARCH_PROVIDED,
				"blogsentry", 0));
		Assert.assertEquals(
			"blogsentry_pt_BR_semantic",
			SemanticFieldNames.fieldName(
				"pt_BR",
				SemanticTextEmbeddingProviderType.ELASTICSEARCH_PROVIDED,
				"blogsentry", 0));
	}

	@Test
	public void testFieldNameWithLiferayProvided() {
		Assert.assertEquals(
			"text_embedding_768_en_US",
			SemanticFieldNames.fieldName(
				LocaleUtil.US,
				SemanticTextEmbeddingProviderType.LIFERAY_PROVIDED,
				"blogsentry", 768));
	}

}