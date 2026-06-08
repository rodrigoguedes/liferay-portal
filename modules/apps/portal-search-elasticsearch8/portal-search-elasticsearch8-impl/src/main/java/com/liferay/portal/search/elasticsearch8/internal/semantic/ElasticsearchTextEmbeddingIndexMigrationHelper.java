/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.semantic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingResponse;
import co.elastic.clients.json.JsonpMapper;

import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.json.JSONUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.ListUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.search.capabilities.ExternalEmbeddingCapabilityGate;
import com.liferay.portal.search.capabilities.ExternalEmbeddingEligibility;
import com.liferay.portal.search.elasticsearch8.internal.connection.ElasticsearchConnectionManager;
import com.liferay.portal.search.semantic.SemanticFieldNames;
import com.liferay.portal.search.semantic.SemanticTextEmbeddingIndexMigrationHelper;
import com.liferay.portal.search.semantic.SemanticTextEmbeddingProviderType;

import jakarta.json.spi.JsonProvider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import java.nio.charset.StandardCharsets;

import java.util.List;
import java.util.Locale;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Adds {@code semantic_text} fields to an existing company index via {@code PUT
 * _mapping}. Consults the capability gate so it is never more permissive than
 * index creation, and fails fast (throws) on a client error or an
 * unacknowledged response — it runs as an explicit admin action, so failures
 * must surface rather than be swallowed. Missing prerequisites are logged
 * no-ops.
 *
 * @author Rodrigo Guedes de Souza
 */
@Component(service = SemanticTextEmbeddingIndexMigrationHelper.class)
public class ElasticsearchTextEmbeddingIndexMigrationHelper
	implements SemanticTextEmbeddingIndexMigrationHelper {

	@Override
	public void addSemanticTextFields(
		String indexName, List<String> assetTypes, List<Locale> locales,
		String inferenceId) {

		if (Validator.isNull(indexName) || ListUtil.isEmpty(assetTypes) ||
			ListUtil.isEmpty(locales) || Validator.isNull(inferenceId)) {

			if (_log.isWarnEnabled()) {
				_log.warn(
					"Skipping semantic_text migration because of missing " +
						"required inputs");
			}

			return;
		}

		ExternalEmbeddingEligibility externalEmbeddingEligibility =
			_externalEmbeddingCapabilityGate.check();

		if (!externalEmbeddingEligibility.isAvailable()) {
			if (_log.isDebugEnabled()) {
				_log.debug(
					"Skipping the semantic_text migration: " +
						externalEmbeddingEligibility.getReason());
			}

			return;
		}

		JSONObject propertiesJSONObject = _jsonFactory.createJSONObject();

		for (String assetType : assetTypes) {
			for (Locale locale : locales) {
				propertiesJSONObject.put(
					SemanticFieldNames.fieldName(
						locale,
						SemanticTextEmbeddingProviderType.
							ELASTICSEARCH_PROVIDED,
						assetType, 0),
					JSONUtil.put(
						"inference_id", inferenceId
					).put(
						"type", "semantic_text"
					));
			}
		}

		String mappings = JSONUtil.put(
			"properties", propertiesJSONObject
		).toString();

		boolean acknowledged = false;

		try (InputStream inputStream = new ByteArrayInputStream(
				mappings.getBytes(StandardCharsets.UTF_8))) {

			JsonpMapper jsonpMapper =
				_elasticsearchConnectionManager.getJsonpMapper(null);

			JsonProvider jsonProvider = jsonpMapper.jsonProvider();

			ElasticsearchClient elasticsearchClient =
				_elasticsearchConnectionManager.getElasticsearchClient();

			ElasticsearchIndicesClient elasticsearchIndicesClient =
				elasticsearchClient.indices();

			PutMappingRequest.Builder builder = new PutMappingRequest.Builder(
			).index(
				indexName
			).withJson(
				jsonProvider.createParser(inputStream), jsonpMapper
			);

			PutMappingResponse putMappingResponse =
				elasticsearchIndicesClient.putMapping(builder.build());

			acknowledged = putMappingResponse.acknowledged();
		}
		catch (Exception exception) {
			throw new RuntimeException(
				"Unable to add semantic_text fields to index " + indexName,
				exception);
		}

		if (!acknowledged) {
			throw new RuntimeException(
				StringBundler.concat(
					"Elasticsearch did not acknowledge the semantic_text ",
					"mapping migration for index ", indexName));
		}
	}

	private static final Log _log = LogFactoryUtil.getLog(
		ElasticsearchTextEmbeddingIndexMigrationHelper.class);

	@Reference
	private ElasticsearchConnectionManager _elasticsearchConnectionManager;

	@Reference
	private ExternalEmbeddingCapabilityGate _externalEmbeddingCapabilityGate;

	@Reference
	private JSONFactory _jsonFactory;

}