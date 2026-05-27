/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.semantic;

import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.test.ReflectionTestUtil;
import com.liferay.portal.kernel.util.HashMapBuilder;
import com.liferay.portal.search.configuration.SemanticSearchConfiguration;
import com.liferay.portal.search.configuration.SemanticSearchConfigurationProvider;
import com.liferay.portal.search.rest.dto.v1_0.EmbeddingProviderConfiguration;
import com.liferay.portal.test.rule.LiferayUnitTestRule;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.Mockito;

/**
 * @author Rodrigo Guedes de Souza
 */
public class InferenceIdResolverImplTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Before
	public void setUp() {
		_inferenceIdResolverImpl = new InferenceIdResolverImpl();

		ReflectionTestUtil.setFieldValue(
			_inferenceIdResolverImpl, "_semanticSearchConfigurationProvider",
			_semanticSearchConfigurationProvider);

		Mockito.when(
			_semanticSearchConfigurationProvider.getCompanyConfiguration(
				Mockito.anyLong())
		).thenReturn(
			_semanticSearchConfiguration
		);
	}

	@Test
	public void testResolveInferenceIdReturnsComposedNameForBYOLLM() {
		_setProviderConfigurationJSONs(_byoLLMJSON("hugging_face"));

		Assert.assertEquals(
			_inferenceId(_COMPANY_ID, "hugging_face"),
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	@Test
	public void testResolveInferenceIdReturnsNullWhenBYOLLMHasNoAttributes() {
		_setProviderConfigurationJSONs(
			new EmbeddingProviderConfiguration(
			) {

				{
					providerName = _BYO_LLM_PROVIDER_NAME;
				}
			}.toString());

		Assert.assertNull(
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	@Test
	public void testResolveInferenceIdReturnsNullWhenBYOLLMHasNoService() {
		_setProviderConfigurationJSONs(
			new EmbeddingProviderConfiguration(
			) {

				{
					attributes = HashMapBuilder.<String, Object>put(
						"model_id", "text-embedding-3-large"
					).build();
					providerName = _BYO_LLM_PROVIDER_NAME;
				}
			}.toString());

		Assert.assertNull(
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	@Test
	public void testResolveInferenceIdReturnsNullWhenJSONsAreNull() {
		Mockito.when(
			_semanticSearchConfiguration.
				textEmbeddingProviderConfigurationJSONs()
		).thenReturn(
			null
		);

		Assert.assertNull(
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	@Test
	public void testResolveInferenceIdReturnsNullWhenNoConfigurations() {
		_setProviderConfigurationJSONs();

		Assert.assertNull(
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	@Test
	public void testResolveInferenceIdReturnsNullWhenOnlyLiferayIntegrated() {
		_setProviderConfigurationJSONs(_liferayIntegratedJSON());

		Assert.assertNull(
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	@Test
	public void testResolveInferenceIdSkipsBlankAndMalformedJSONs() {
		_setProviderConfigurationJSONs(
			"", "not-valid-json", _byoLLMJSON("openai"));

		Assert.assertEquals(
			_inferenceId(_COMPANY_ID, "openai"),
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	@Test
	public void testResolveInferenceIdSkipsJSONWithNullProviderName() {
		_setProviderConfigurationJSONs(
			new EmbeddingProviderConfiguration(
			) {

				{
					languageIds = new String[] {"en_US"};
				}
			}.toString(),
			_byoLLMJSON("openai"));

		Assert.assertEquals(
			_inferenceId(_COMPANY_ID, "openai"),
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	private String _byoLLMJSON(String service) {
		return new EmbeddingProviderConfiguration(
		) {

			{
				attributes = HashMapBuilder.<String, Object>put(
					"service", service
				).build();
				providerName = _BYO_LLM_PROVIDER_NAME;
			}
		}.toString();
	}

	private String _inferenceId(long companyId, String service) {
		return StringBundler.concat(
			"liferay-", companyId, "-inference-", service);
	}

	private String _liferayIntegratedJSON() {
		return new EmbeddingProviderConfiguration(
		) {

			{
				providerName = "OpenAI";
			}
		}.toString();
	}

	private void _setProviderConfigurationJSONs(String... jsons) {
		Mockito.when(
			_semanticSearchConfiguration.
				textEmbeddingProviderConfigurationJSONs()
		).thenReturn(
			jsons
		);
	}

	private static final String _BYO_LLM_PROVIDER_NAME =
		"Elasticsearch Inference Endpoint";

	private static final long _COMPANY_ID = 42L;

	private InferenceIdResolverImpl _inferenceIdResolverImpl;
	private final SemanticSearchConfiguration _semanticSearchConfiguration =
		Mockito.mock(SemanticSearchConfiguration.class);
	private final SemanticSearchConfigurationProvider
		_semanticSearchConfigurationProvider = Mockito.mock(
			SemanticSearchConfigurationProvider.class);

}