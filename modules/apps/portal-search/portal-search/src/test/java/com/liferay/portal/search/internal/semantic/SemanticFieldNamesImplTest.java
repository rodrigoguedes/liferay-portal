/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.semantic;

import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.search.semantic.SemanticFieldNames;
import com.liferay.portal.search.semantic.SemanticProviderType;
import com.liferay.portal.test.rule.LiferayUnitTestRule;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * @author Rodrigo Guedes
 */
public class SemanticFieldNamesImplTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Test
	public void testBYOLLMAndLiferayIntegratedNamesDoNotCollide() {
		String byollmFieldName = _semanticFieldNames.fieldName(
			LocaleUtil.US, SemanticProviderType.BYO_LLM, "journal_article",
			1536);

		String liferayIntegratedFieldName = _semanticFieldNames.fieldName(
			LocaleUtil.US, SemanticProviderType.LIFERAY_INTEGRATED,
			"journal_article", 1536);

		Assert.assertNotEquals(byollmFieldName, liferayIntegratedFieldName);
	}

	@Test
	public void testBYOLLMFieldNameForUSLocale() {
		Assert.assertEquals(
			"journal_article_en_US_semantic",
			_semanticFieldNames.fieldName(
				LocaleUtil.US, SemanticProviderType.BYO_LLM, "journal_article",
				0));
	}

	@Test
	public void testBYOLLMFieldNameIsDeterministic() {
		String fieldName1 = _semanticFieldNames.fieldName(
			LocaleUtil.BRAZIL, SemanticProviderType.BYO_LLM, "blog_entry", 0);

		String fieldName2 = _semanticFieldNames.fieldName(
			LocaleUtil.BRAZIL, SemanticProviderType.BYO_LLM, "blog_entry", 0);

		Assert.assertEquals(fieldName1, fieldName2);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testBYOLLMWithEmptyAssetTypeThrows() {
		_semanticFieldNames.fieldName(
			LocaleUtil.US, SemanticProviderType.BYO_LLM, "", 0);
	}

	@Test
	public void testBYOLLMWithMultipleLocales() {
		Assert.assertEquals(
			"blog_entry_pt_BR_semantic",
			_semanticFieldNames.fieldName(
				LocaleUtil.BRAZIL, SemanticProviderType.BYO_LLM, "blog_entry",
				0));
		Assert.assertEquals(
			"blog_entry_ja_JP_semantic",
			_semanticFieldNames.fieldName(
				LocaleUtil.JAPAN, SemanticProviderType.BYO_LLM, "blog_entry",
				0));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testBYOLLMWithNullAssetTypeThrows() {
		_semanticFieldNames.fieldName(
			LocaleUtil.US, SemanticProviderType.BYO_LLM, null, 0);
	}

	@Test
	public void testLiferayIntegratedFieldNameForUSLocale() {
		Assert.assertEquals(
			"text_embedding_1536_en_US",
			_semanticFieldNames.fieldName(
				LocaleUtil.US, SemanticProviderType.LIFERAY_INTEGRATED, null,
				1536));
	}

	@Test
	public void testLiferayIntegratedFieldNameIsDeterministic() {
		String fieldName1 = _semanticFieldNames.fieldName(
			LocaleUtil.US, SemanticProviderType.LIFERAY_INTEGRATED, null, 768);

		String fieldName2 = _semanticFieldNames.fieldName(
			LocaleUtil.US, SemanticProviderType.LIFERAY_INTEGRATED, null, 768);

		Assert.assertEquals(fieldName1, fieldName2);
	}

	@Test
	public void testLiferayIntegratedWithMultipleDimensions() {
		Assert.assertEquals(
			"text_embedding_768_en_US",
			_semanticFieldNames.fieldName(
				LocaleUtil.US, SemanticProviderType.LIFERAY_INTEGRATED, null,
				768));
		Assert.assertEquals(
			"text_embedding_3072_pt_BR",
			_semanticFieldNames.fieldName(
				LocaleUtil.BRAZIL, SemanticProviderType.LIFERAY_INTEGRATED,
				null, 3072));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testLiferayIntegratedWithZeroDimensionsThrows() {
		_semanticFieldNames.fieldName(
			LocaleUtil.US, SemanticProviderType.LIFERAY_INTEGRATED, null, 0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testNullLocaleThrows() {
		_semanticFieldNames.fieldName(
			null, SemanticProviderType.BYO_LLM, "journal_article", 0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testNullSemanticProviderTypeThrows() {
		_semanticFieldNames.fieldName(
			LocaleUtil.US, null, "journal_article", 1536);
	}

	private final SemanticFieldNames _semanticFieldNames =
		new SemanticFieldNamesImpl();

}