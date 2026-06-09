/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.semantic;

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
	}

	@Test
	public void testResolveInferenceId() {
		_setUpConfiguration(
			true, _inferenceEndpointConfigurationJSON("my-endpoint"));

		Assert.assertEquals(
			"my-endpoint",
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	@Test
	public void testResolveInferenceIdWhenNotInferenceEndpointProvider() {
		_setUpConfiguration(true, _openAIConfigurationJSON());

		Assert.assertNull(
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	@Test
	public void testResolveInferenceIdWhenTextEmbeddingsDisabled() {
		_setUpConfiguration(
			false, _inferenceEndpointConfigurationJSON("my-endpoint"));

		Assert.assertNull(
			_inferenceIdResolverImpl.resolveInferenceId(_COMPANY_ID));
	}

	private String _inferenceEndpointConfigurationJSON(String inferenceId) {
		EmbeddingProviderConfiguration embeddingProviderConfiguration =
			new EmbeddingProviderConfiguration();

		embeddingProviderConfiguration.setAttributes(
			HashMapBuilder.<String, Object>put(
				"inferenceId", inferenceId
			).build());
		embeddingProviderConfiguration.setProviderName("inference-endpoint");

		return embeddingProviderConfiguration.toString();
	}

	private String _openAIConfigurationJSON() {
		EmbeddingProviderConfiguration embeddingProviderConfiguration =
			new EmbeddingProviderConfiguration();

		embeddingProviderConfiguration.setAttributes(
			HashMapBuilder.<String, Object>put(
				"apiKey", "test"
			).build());
		embeddingProviderConfiguration.setProviderName("openai");

		return embeddingProviderConfiguration.toString();
	}

	private void _setUpConfiguration(
		boolean textEmbeddingsEnabled,
		String... textEmbeddingProviderConfigurationJSONs) {

		SemanticSearchConfiguration semanticSearchConfiguration = Mockito.mock(
			SemanticSearchConfiguration.class);

		Mockito.when(
			semanticSearchConfiguration.textEmbeddingsEnabled()
		).thenReturn(
			textEmbeddingsEnabled
		);

		Mockito.when(
			semanticSearchConfiguration.
				textEmbeddingProviderConfigurationJSONs()
		).thenReturn(
			textEmbeddingProviderConfigurationJSONs
		);

		Mockito.when(
			_semanticSearchConfigurationProvider.getCompanyConfiguration(
				_COMPANY_ID)
		).thenReturn(
			semanticSearchConfiguration
		);
	}

	private static final long _COMPANY_ID = 1L;

	private InferenceIdResolverImpl _inferenceIdResolverImpl;
	private final SemanticSearchConfigurationProvider
		_semanticSearchConfigurationProvider = Mockito.mock(
			SemanticSearchConfigurationProvider.class);

}