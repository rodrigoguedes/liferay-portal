/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.SemanticTextProperty;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingResponse;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;

import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.json.JSONFactoryImpl;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.test.ReflectionTestUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.SetUtil;
import com.liferay.portal.search.elasticsearch8.internal.connection.ElasticsearchConnectionManager;
import com.liferay.portal.search.index.IndexNameBuilder;
import com.liferay.portal.search.internal.semantic.SemanticFieldNamesImpl;
import com.liferay.portal.search.semantic.InferenceIdResolver;
import com.liferay.portal.search.semantic.SemanticFieldNames;
import com.liferay.portal.test.rule.LiferayUnitTestRule;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * @author Rodrigo Guedes de Souza
 */
public class BYOLLMIndexMigrationHelperTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Before
	public void setUp() {
		_byollmIndexMigrationHelper = new BYOLLMIndexMigrationHelper();

		Mockito.when(
			_elasticsearchClient.indices()
		).thenReturn(
			_elasticsearchIndicesClient
		);

		Mockito.when(
			_elasticsearchConnectionManager.getElasticsearchClient()
		).thenReturn(
			_elasticsearchClient
		);

		Mockito.when(
			_elasticsearchConnectionManager.getJsonpMapper(null)
		).thenReturn(
			_jsonpMapper
		);

		Mockito.when(
			_indexNameBuilder.getIndexName(_COMPANY_ID)
		).thenReturn(
			_INDEX_NAME
		);

		Mockito.when(
			_inferenceIdResolver.resolveInferenceId(_COMPANY_ID)
		).thenReturn(
			_INFERENCE_ID
		);

		ReflectionTestUtil.setFieldValue(
			_byollmIndexMigrationHelper, "_elasticsearchConnectionManager",
			_elasticsearchConnectionManager);
		ReflectionTestUtil.setFieldValue(
			_byollmIndexMigrationHelper, "_indexNameBuilder",
			_indexNameBuilder);
		ReflectionTestUtil.setFieldValue(
			_byollmIndexMigrationHelper, "_inferenceIdResolver",
			_inferenceIdResolver);
		ReflectionTestUtil.setFieldValue(
			_byollmIndexMigrationHelper, "_jsonFactory", _jsonFactory);
		ReflectionTestUtil.setFieldValue(
			_byollmIndexMigrationHelper, "_semanticFieldNames",
			_semanticFieldNames);
	}

	@Test
	public void testEnableSemanticTextOnExistingIndex() throws Exception {
		Set<String> assetTypes = SetUtil.fromArray(
			"blog_entry", "journal_article");
		Set<Locale> locales = SetUtil.fromArray(
			LocaleUtil.BRAZIL, LocaleUtil.US);

		Mockito.when(
			_elasticsearchIndicesClient.putMapping(
				Mockito.any(PutMappingRequest.class))
		).thenReturn(
			Mockito.mock(PutMappingResponse.class)
		);

		_byollmIndexMigrationHelper.enableSemanticTextOnExistingIndex(
			_COMPANY_ID, assetTypes, locales);

		PutMappingRequest putMappingRequest = _capturePutMappingRequest();

		Assert.assertEquals(
			Collections.singletonList(_INDEX_NAME), putMappingRequest.index());

		Map<String, Property> properties = putMappingRequest.properties();

		Assert.assertEquals(
			"Expected one semantic_text field per asset type per locale",
			assetTypes.size() * locales.size(), properties.size());

		for (String assetType : assetTypes) {
			for (Locale locale : locales) {
				String fieldName = StringBundler.concat(
					assetType, StringPool.UNDERLINE,
					LocaleUtil.toLanguageId(locale), "_semantic");

				Property property = properties.get(fieldName);

				Assert.assertNotNull(
					"Missing semantic_text field: " + fieldName, property);
				Assert.assertTrue(
					"Field " + fieldName + " is not semantic_text",
					property.isSemanticText());

				SemanticTextProperty semanticTextProperty =
					property.semanticText();

				Assert.assertEquals(
					_INFERENCE_ID, semanticTextProperty.inferenceId());
			}
		}
	}

	@Test
	public void testEnableSemanticTextOnExistingIndexEmptyAssetTypes() {
		_byollmIndexMigrationHelper.enableSemanticTextOnExistingIndex(
			_COMPANY_ID, Collections.emptySet(),
			SetUtil.fromArray(LocaleUtil.US));

		Mockito.verifyNoInteractions(_elasticsearchIndicesClient);
	}

	@Test
	public void testEnableSemanticTextOnExistingIndexEmptyLocales() {
		_byollmIndexMigrationHelper.enableSemanticTextOnExistingIndex(
			_COMPANY_ID, SetUtil.fromArray("journal_article"),
			Collections.emptySet());

		Mockito.verifyNoInteractions(_elasticsearchIndicesClient);
	}

	@Test
	public void testEnableSemanticTextOnExistingIndexNoInferenceId() {
		Mockito.when(
			_inferenceIdResolver.resolveInferenceId(_COMPANY_ID)
		).thenReturn(
			null
		);

		_byollmIndexMigrationHelper.enableSemanticTextOnExistingIndex(
			_COMPANY_ID, SetUtil.fromArray("journal_article"),
			SetUtil.fromArray(LocaleUtil.US));

		Mockito.verifyNoInteractions(_elasticsearchIndicesClient);
	}

	@Test(expected = RuntimeException.class)
	public void testEnableSemanticTextOnExistingIndexWrapsClientException()
		throws Exception {

		Mockito.when(
			_elasticsearchIndicesClient.putMapping(
				Mockito.any(PutMappingRequest.class))
		).thenThrow(
			new RuntimeException("boom")
		);

		_byollmIndexMigrationHelper.enableSemanticTextOnExistingIndex(
			_COMPANY_ID, SetUtil.fromArray("journal_article"),
			SetUtil.fromArray(LocaleUtil.US));
	}

	private PutMappingRequest _capturePutMappingRequest() throws Exception {
		ArgumentCaptor<PutMappingRequest> argumentCaptor =
			ArgumentCaptor.forClass(PutMappingRequest.class);

		Mockito.verify(
			_elasticsearchIndicesClient
		).putMapping(
			argumentCaptor.capture()
		);

		return argumentCaptor.getValue();
	}

	private static final long _COMPANY_ID = 42L;

	private static final String _INDEX_NAME = "liferay-42";

	private static final String _INFERENCE_ID = "liferay-active-provider";

	private BYOLLMIndexMigrationHelper _byollmIndexMigrationHelper;
	private final ElasticsearchClient _elasticsearchClient = Mockito.mock(
		ElasticsearchClient.class);
	private final ElasticsearchConnectionManager
		_elasticsearchConnectionManager = Mockito.mock(
			ElasticsearchConnectionManager.class);
	private final ElasticsearchIndicesClient _elasticsearchIndicesClient =
		Mockito.mock(ElasticsearchIndicesClient.class);
	private final IndexNameBuilder _indexNameBuilder = Mockito.mock(
		IndexNameBuilder.class);
	private final InferenceIdResolver _inferenceIdResolver = Mockito.mock(
		InferenceIdResolver.class);
	private final JSONFactory _jsonFactory = new JSONFactoryImpl();
	private final JsonpMapper _jsonpMapper = new JacksonJsonpMapper();
	private final SemanticFieldNames _semanticFieldNames =
		new SemanticFieldNamesImpl();

}