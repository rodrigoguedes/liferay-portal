/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingResponse;
import co.elastic.clients.json.JsonpMapper;

import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.search.elasticsearch8.internal.connection.ElasticsearchConnectionManager;
import com.liferay.portal.search.elasticsearch8.internal.index.util.SemanticTextMappingsUtil;
import com.liferay.portal.search.elasticsearch8.internal.util.JsonpUtil;
import com.liferay.portal.search.index.IndexNameBuilder;
import com.liferay.portal.search.semantic.InferenceIdResolver;
import com.liferay.portal.search.semantic.SemanticFieldNames;

import jakarta.json.spi.JsonProvider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import java.nio.charset.StandardCharsets;

import java.util.Locale;
import java.util.Set;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Rodrigo Guedes de Souza
 */
@Component(service = BYOLLMIndexMigrationHelper.class)
public class BYOLLMIndexMigrationHelper {

	public void enableSemanticTextOnExistingIndex(
		long companyId, Set<String> assetTypes, Set<Locale> locales) {

		if (assetTypes.isEmpty() || locales.isEmpty()) {
			return;
		}

		String inferenceId = _inferenceIdResolver.resolveInferenceId(companyId);

		if (Validator.isNull(inferenceId)) {
			return;
		}

		_putMapping(
			_indexNameBuilder.getIndexName(companyId),
			_buildSemanticTextMappings(assetTypes, inferenceId, locales));
	}

	private JSONObject _buildSemanticTextMappings(
		Set<String> assetTypes, String inferenceId, Set<Locale> locales) {

		JSONObject propertiesJSONObject = _jsonFactory.createJSONObject();

		SemanticTextMappingsUtil.putSemanticTextProperties(
			assetTypes, inferenceId, locales, _semanticFieldNames,
			propertiesJSONObject);

		return _jsonFactory.createJSONObject(
		).put(
			"properties", propertiesJSONObject
		);
	}

	private void _putMapping(String indexName, JSONObject mappingsJSONObject) {
		String mappings = String.valueOf(mappingsJSONObject);

		try {
			ElasticsearchClient elasticsearchClient =
				_elasticsearchConnectionManager.getElasticsearchClient();

			ElasticsearchIndicesClient elasticsearchIndicesClient =
				elasticsearchClient.indices();

			JsonpMapper jsonpMapper =
				_elasticsearchConnectionManager.getJsonpMapper(null);

			JsonProvider jsonProvider = jsonpMapper.jsonProvider();

			try (InputStream inputStream = new ByteArrayInputStream(
					mappings.getBytes(StandardCharsets.UTF_8))) {

				PutMappingRequest.Builder builder =
					new PutMappingRequest.Builder(
					).index(
						indexName
					).withJson(
						jsonProvider.createParser(inputStream), jsonpMapper
					);

				PutMappingResponse putMappingResponse =
					elasticsearchIndicesClient.putMapping(builder.build());

				JsonpUtil.logInfoResponse(putMappingResponse, _log);
			}
		}
		catch (Exception exception) {
			throw new RuntimeException(
				StringBundler.concat(
					"Unable to add semantic_text mappings to index ", indexName,
					": ", exception.getMessage()),
				exception);
		}
	}

	private static final Log _log = LogFactoryUtil.getLog(
		BYOLLMIndexMigrationHelper.class);

	@Reference
	private ElasticsearchConnectionManager _elasticsearchConnectionManager;

	@Reference
	private IndexNameBuilder _indexNameBuilder;

	@Reference
	private InferenceIdResolver _inferenceIdResolver;

	@Reference
	private JSONFactory _jsonFactory;

	@Reference
	private SemanticFieldNames _semanticFieldNames;

}