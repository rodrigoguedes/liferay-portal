/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.index;

import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.json.JSONFactoryImpl;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.json.JSONUtil;
import com.liferay.portal.kernel.test.ReflectionTestUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.SetUtil;
import com.liferay.portal.search.capabilities.ExternalEmbeddingCapabilityGate;
import com.liferay.portal.search.capabilities.ExternalEmbeddingEligibility;
import com.liferay.portal.search.internal.semantic.SemanticFieldNamesImpl;
import com.liferay.portal.search.semantic.SemanticFieldNames;
import com.liferay.portal.test.rule.LiferayUnitTestRule;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.Mockito;

/**
 * @author Rodrigo Guedes
 */
public class MappingsHelperImplTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Test
	public void testAddSemanticTextMappingsInferenceIdMatches() {
		MappingsHelperImpl mappingsHelperImpl = _newMappingsHelperImpl(
			_availableChecker(), SetUtil.fromArray("journal_article"),
			SetUtil.fromArray(LocaleUtil.US));

		String mappings = _invokeAddSemanticTextMappings(
			mappingsHelperImpl, _baselineMappings());

		JSONObject jsonObject = _toJSONObject(mappings);

		JSONObject propertiesJSONObject = jsonObject.getJSONObject(
			"properties");

		JSONObject fieldJSONObject = propertiesJSONObject.getJSONObject(
			"journal_article_en_US_semantic");

		Assert.assertEquals(
			_INFERENCE_ID, fieldJSONObject.getString("inference_id"));
	}

	@Test
	public void testAddSemanticTextMappingsOneFieldPerAssetTypePerLocale() {
		Set<String> assetTypes = SetUtil.fromArray(
			"blog_entry", "journal_article");
		Set<Locale> locales = SetUtil.fromArray(
			LocaleUtil.BRAZIL, LocaleUtil.US);

		MappingsHelperImpl mappingsHelperImpl = _newMappingsHelperImpl(
			_availableChecker(), assetTypes, locales);

		String mappings = _invokeAddSemanticTextMappings(
			mappingsHelperImpl, _baselineMappings());

		JSONObject jsonObject = _toJSONObject(mappings);

		JSONObject propertiesJSONObject = jsonObject.getJSONObject(
			"properties");

		for (String assetType : assetTypes) {
			for (Locale locale : locales) {
				String fieldName = StringBundler.concat(
					assetType, StringPool.UNDERLINE,
					LocaleUtil.toLanguageId(locale), "_semantic");

				JSONObject fieldJSONObject = propertiesJSONObject.getJSONObject(
					fieldName);

				Assert.assertNotNull(
					"Missing semantic_text field: " + fieldName,
					fieldJSONObject);
				Assert.assertEquals(
					"semantic_text", fieldJSONObject.getString("type"));
				Assert.assertEquals(
					_INFERENCE_ID, fieldJSONObject.getString("inference_id"));
			}
		}
	}

	@Test
	public void testAddSemanticTextMappingsStaticPropertiesNotDynamicTemplates() {
		MappingsHelperImpl mappingsHelperImpl = _newMappingsHelperImpl(
			_availableChecker(), SetUtil.fromArray("journal_article"),
			SetUtil.fromArray(LocaleUtil.US));

		String mappings = _invokeAddSemanticTextMappings(
			mappingsHelperImpl, _baselineMappings());

		JSONObject jsonObject = _toJSONObject(mappings);

		String semanticFieldKey = "journal_article_en_US_semantic";

		JSONObject propertiesJSONObject = jsonObject.getJSONObject(
			"properties");

		Assert.assertTrue(
			"Expected static property " + semanticFieldKey,
			propertiesJSONObject.has(semanticFieldKey));

		Assert.assertFalse(
			"semantic_text fields must never appear in dynamic_templates",
			jsonObject.getJSONArray(
				"dynamic_templates"
			).toString(
			).contains(
				semanticFieldKey
			));
	}

	@Test
	public void testIsBYOLLMCapabilityAvailableEmptyAssetTypes() {
		MappingsHelperImpl mappingsHelperImpl = _newMappingsHelperImpl(
			_availableChecker(), Collections.emptySet(),
			SetUtil.fromArray(LocaleUtil.US));

		Assert.assertFalse(
			_invokeIsBYOLLMCapabilityAvailable(mappingsHelperImpl));
	}

	@Test
	public void testIsBYOLLMCapabilityAvailableEmptyLocales() {
		MappingsHelperImpl mappingsHelperImpl = _newMappingsHelperImpl(
			_availableChecker(), SetUtil.fromArray("journal_article"),
			Collections.emptySet());

		Assert.assertFalse(
			_invokeIsBYOLLMCapabilityAvailable(mappingsHelperImpl));
	}

	@Test
	public void testIsBYOLLMCapabilityAvailableFeatureFlagOff() {
		MappingsHelperImpl mappingsHelperImpl = _newMappingsHelperImpl(
			_unavailableChecker(
				"semantic-search.capability.feature-flag-disabled"),
			SetUtil.fromArray("journal_article"),
			SetUtil.fromArray(LocaleUtil.US));

		Assert.assertFalse(
			_invokeIsBYOLLMCapabilityAvailable(mappingsHelperImpl));
	}

	@Test
	public void testIsBYOLLMCapabilityAvailableHappyPath() {
		MappingsHelperImpl mappingsHelperImpl = _newMappingsHelperImpl(
			_availableChecker(), SetUtil.fromArray("journal_article"),
			SetUtil.fromArray(LocaleUtil.US));

		Assert.assertTrue(
			_invokeIsBYOLLMCapabilityAvailable(mappingsHelperImpl));
	}

	@Test
	public void testIsBYOLLMCapabilityAvailableLegacyConstructor() {
		MappingsHelperImpl mappingsHelperImpl = new MappingsHelperImpl(
			null, null, null, null, null, null);

		Assert.assertFalse(
			_invokeIsBYOLLMCapabilityAvailable(mappingsHelperImpl));
	}

	private ExternalEmbeddingCapabilityGate _availableChecker() {
		ExternalEmbeddingCapabilityGate externalEmbeddingCapabilityGate =
			Mockito.mock(ExternalEmbeddingCapabilityGate.class);

		Mockito.when(
			externalEmbeddingCapabilityGate.check()
		).thenReturn(
			ExternalEmbeddingEligibility.available()
		);

		return externalEmbeddingCapabilityGate;
	}

	private String _baselineMappings() {
		return JSONUtil.put(
			"dynamic_templates", JSONFactoryUtil.createJSONArray()
		).put(
			"properties", JSONFactoryUtil.createJSONObject()
		).toString();
	}

	private String _invokeAddSemanticTextMappings(
		MappingsHelperImpl mappingsHelperImpl, String mappings) {

		return ReflectionTestUtil.invoke(
			mappingsHelperImpl, "_addSemanticTextMappings",
			new Class<?>[] {String.class}, mappings);
	}

	private boolean _invokeIsBYOLLMCapabilityAvailable(
		MappingsHelperImpl mappingsHelperImpl) {

		return ReflectionTestUtil.invoke(
			mappingsHelperImpl, "_isBYOLLMCapabilityAvailable",
			new Class<?>[0]);
	}

	private MappingsHelperImpl _newMappingsHelperImpl(
		ExternalEmbeddingCapabilityGate externalEmbeddingCapabilityGate,
		Set<String> assetTypes, Set<Locale> locales) {

		return new MappingsHelperImpl(
			assetTypes, null, externalEmbeddingCapabilityGate, null,
			_INFERENCE_ID, _jsonFactory, null, locales, null, null,
			_semanticFieldNames);
	}

	private JSONObject _toJSONObject(String json) {
		try {
			return _jsonFactory.createJSONObject(json);
		}
		catch (Exception exception) {
			throw new RuntimeException(exception);
		}
	}

	private ExternalEmbeddingCapabilityGate _unavailableChecker(String reason) {
		ExternalEmbeddingCapabilityGate externalEmbeddingCapabilityGate =
			Mockito.mock(ExternalEmbeddingCapabilityGate.class);

		Mockito.when(
			externalEmbeddingCapabilityGate.check()
		).thenReturn(
			ExternalEmbeddingEligibility.unavailable(reason)
		);

		return externalEmbeddingCapabilityGate;
	}

	private static final String _INFERENCE_ID = "liferay-active-provider";

	private final JSONFactory _jsonFactory = new JSONFactoryImpl();
	private final SemanticFieldNames _semanticFieldNames =
		new SemanticFieldNamesImpl();

}