/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.configuration.persistence.listener;

import com.liferay.portal.kernel.test.ReflectionTestUtil;
import com.liferay.portal.kernel.test.util.RandomTestUtil;
import com.liferay.portal.kernel.util.HashMapBuilder;
import com.liferay.portal.kernel.util.HashMapDictionaryBuilder;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.search.index.IndexNameBuilder;
import com.liferay.portal.search.rest.dto.v1_0.EmbeddingProviderConfiguration;
import com.liferay.portal.search.semantic.SemanticTextEmbeddingIndexMigrationHelper;
import com.liferay.portal.test.rule.LiferayUnitTestRule;

import java.util.Arrays;
import java.util.Dictionary;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.Mockito;

/**
 * @author Rodrigo Guedes de Souza
 */
public class SemanticSearchConfigurationModelListenerTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Before
	public void setUp() throws Exception {
		_semanticSearchConfigurationModelListener =
			new SemanticSearchConfigurationModelListener();

		Mockito.when(
			_indexNameBuilder.getIndexName(Mockito.anyLong())
		).thenReturn(
			"liferay-123"
		);

		ReflectionTestUtil.setFieldValue(
			_semanticSearchConfigurationModelListener, "_indexNameBuilder",
			_indexNameBuilder);
		ReflectionTestUtil.setFieldValue(
			_semanticSearchConfigurationModelListener,
			"_semanticTextEmbeddingIndexMigrationHelper",
			_semanticTextEmbeddingIndexMigrationHelper);
	}

	@Test
	public void testOnAfterSaveAddsSemanticTextFieldsForInferenceEndpoint()
		throws Exception {

		_semanticSearchConfigurationModelListener.onAfterSave(
			RandomTestUtil.randomString(),
			_properties(
				123L, true,
				_embeddingProviderConfigurationJSON(
					"inference-endpoint", "my-endpoint",
					"com.liferay.blogs.model.BlogsEntry", "en_US")));

		Mockito.verify(
			_semanticTextEmbeddingIndexMigrationHelper
		).addSemanticTextFields(
			"liferay-123", Arrays.asList("blogsentry"),
			Arrays.asList(LocaleUtil.fromLanguageId("en_US", false)),
			"my-endpoint"
		);
	}

	@Test
	public void testOnAfterSaveSkipsWhenCompanyIdIsZero() throws Exception {
		_semanticSearchConfigurationModelListener.onAfterSave(
			RandomTestUtil.randomString(),
			_properties(
				0L, true,
				_embeddingProviderConfigurationJSON(
					"inference-endpoint", "my-endpoint",
					"com.liferay.blogs.model.BlogsEntry", "en_US")));

		_verifyNoMigration();
	}

	@Test
	public void testOnAfterSaveSkipsWhenProviderIsNotInferenceEndpoint()
		throws Exception {

		_semanticSearchConfigurationModelListener.onAfterSave(
			RandomTestUtil.randomString(),
			_properties(
				123L, true,
				_embeddingProviderConfigurationJSON(
					"hugging-face", "my-endpoint",
					"com.liferay.blogs.model.BlogsEntry", "en_US")));

		_verifyNoMigration();
	}

	@Test
	public void testOnAfterSaveSkipsWhenTextEmbeddingsDisabled()
		throws Exception {

		_semanticSearchConfigurationModelListener.onAfterSave(
			RandomTestUtil.randomString(),
			_properties(
				123L, false,
				_embeddingProviderConfigurationJSON(
					"inference-endpoint", "my-endpoint",
					"com.liferay.blogs.model.BlogsEntry", "en_US")));

		_verifyNoMigration();
	}

	private String _embeddingProviderConfigurationJSON(
		String embeddingProviderName, String embeddingInferenceId,
		String embeddingModelClassName, String embeddingLanguageId) {

		return new EmbeddingProviderConfiguration(
		) {

			{
				setAttributes(
					HashMapBuilder.<String, Object>put(
						"inferenceId", embeddingInferenceId
					).build());
				setLanguageIds(new String[] {embeddingLanguageId});
				setModelClassNames(new String[] {embeddingModelClassName});
				setProviderName(embeddingProviderName);
			}
		}.toString();
	}

	private Dictionary<String, Object> _properties(
		long companyId, boolean textEmbeddingsEnabled,
		String textEmbeddingProviderConfigurationJSON) {

		return HashMapDictionaryBuilder.<String, Object>put(
			"companyId", companyId
		).put(
			"textEmbeddingProviderConfigurationJSONs",
			new String[] {textEmbeddingProviderConfigurationJSON}
		).put(
			"textEmbeddingsEnabled", textEmbeddingsEnabled
		).build();
	}

	private void _verifyNoMigration() throws Exception {
		Mockito.verify(
			_semanticTextEmbeddingIndexMigrationHelper, Mockito.never()
		).addSemanticTextFields(
			Mockito.anyString(), Mockito.anyList(), Mockito.anyList(),
			Mockito.anyString()
		);
	}

	private final IndexNameBuilder _indexNameBuilder = Mockito.mock(
		IndexNameBuilder.class);
	private SemanticSearchConfigurationModelListener
		_semanticSearchConfigurationModelListener;
	private final SemanticTextEmbeddingIndexMigrationHelper
		_semanticTextEmbeddingIndexMigrationHelper = Mockito.mock(
			SemanticTextEmbeddingIndexMigrationHelper.class);

}