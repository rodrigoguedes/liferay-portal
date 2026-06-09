/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.semantic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.inference.GetInferenceResponse;
import co.elastic.clients.elasticsearch.inference.InferenceEndpointInfo;
import co.elastic.clients.elasticsearch.inference.InferenceResponse;
import co.elastic.clients.elasticsearch.inference.InferenceResult;
import co.elastic.clients.elasticsearch.inference.TaskType;
import co.elastic.clients.elasticsearch.inference.TextEmbeddingResult;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.transport.rest_client.RestClientTransport;

import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.search.elasticsearch8.internal.connection.ElasticsearchClientResolver;
import com.liferay.portal.search.elasticsearch8.internal.connection.ElasticsearchConnection;
import com.liferay.portal.search.elasticsearch8.internal.connection.ElasticsearchConnectionManager;
import com.liferay.portal.search.semantic.InferenceEndpoint;
import com.liferay.portal.search.semantic.InferenceEndpointRegistry;
import com.liferay.portal.search.semantic.InferenceService;
import com.liferay.portal.search.semantic.InferenceServiceField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Reads Inference Endpoints from the active Elasticsearch cluster through the
 * {@code _inference} APIs and exposes only the {@code text_embedding} ones to
 * the rest of Liferay.
 *
 * @author Rodrigo Guedes de Souza
 */
@Component(service = InferenceEndpointRegistry.class)
public class ElasticsearchInferenceEndpointRegistry
	implements InferenceEndpointRegistry {

	@Override
	public void createTextEmbeddingInferenceEndpoint(
			String inferenceId, String service,
			Map<String, Object> serviceSettings)
		throws Exception {

		ElasticsearchClient elasticsearchClient =
			_elasticsearchClientResolver.getElasticsearchClient();

		elasticsearchClient.inference(
		).put(
			putRequestBuilder -> putRequestBuilder.inferenceId(
				inferenceId
			).taskType(
				TaskType.TextEmbedding
			).inferenceConfig(
				inferenceEndpointBuilder -> inferenceEndpointBuilder.service(
					service
				).serviceSettings(
					JsonData.of(serviceSettings)
				)
			)
		);
	}

	@Override
	public InferenceEndpoint getInferenceEndpoint(String inferenceId) {
		if (Validator.isNull(inferenceId)) {
			return null;
		}

		try {
			ElasticsearchClient elasticsearchClient =
				_elasticsearchClientResolver.getElasticsearchClient();

			GetInferenceResponse getInferenceResponse =
				elasticsearchClient.inference(
				).get(
					getInferenceRequestBuilder ->
						getInferenceRequestBuilder.inferenceId(inferenceId)
				);

			for (InferenceEndpointInfo inferenceEndpointInfo :
					getInferenceResponse.endpoints()) {

				if (Objects.equals(
						inferenceEndpointInfo.inferenceId(), inferenceId)) {

					return _toInferenceEndpoint(inferenceEndpointInfo);
				}
			}

			return null;
		}
		catch (Exception exception) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"Unable to read inference endpoint " + inferenceId,
					exception);
			}

			return null;
		}
	}

	@Override
	public List<InferenceEndpoint> getTextEmbeddingInferenceEndpoints() {
		try {
			ElasticsearchClient elasticsearchClient =
				_elasticsearchClientResolver.getElasticsearchClient();

			GetInferenceResponse getInferenceResponse =
				elasticsearchClient.inference(
				).get();

			List<InferenceEndpoint> inferenceEndpoints = new ArrayList<>();

			for (InferenceEndpointInfo inferenceEndpointInfo :
					getInferenceResponse.endpoints()) {

				if (inferenceEndpointInfo.taskType() ==
						TaskType.TextEmbedding) {

					inferenceEndpoints.add(
						_toInferenceEndpoint(inferenceEndpointInfo));
				}
			}

			return inferenceEndpoints;
		}
		catch (Exception exception) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"Unable to list text_embedding inference endpoints",
					exception);
			}

			return Collections.emptyList();
		}
	}

	@Override
	public List<InferenceService> getTextEmbeddingInferenceServices()
		throws Exception {

		RestClient restClient = _getRestClient();

		if (restClient == null) {
			return Collections.emptyList();
		}

		Response response = restClient.performRequest(
			new Request("GET", "/_inference/_services/text_embedding"));

		String responseBody = StringUtil.read(
			response.getEntity(
			).getContent());

		return _toInferenceServices(_jsonFactory.createJSONArray(responseBody));
	}

	@Override
	public int testTextEmbeddingInferenceEndpoint(String inferenceId)
		throws Exception {

		ElasticsearchClient elasticsearchClient =
			_elasticsearchClientResolver.getElasticsearchClient();

		InferenceResponse inferenceResponse = elasticsearchClient.inference(
		).inference(
			inferenceRequestBuilder -> inferenceRequestBuilder.inferenceId(
				inferenceId
			).input(
				"Liferay BYO-LLM inference endpoint test"
			)
		);

		InferenceResult inferenceResult = inferenceResponse.valueBody();

		if (inferenceResult.isTextEmbedding()) {
			List<TextEmbeddingResult> textEmbeddingResults =
				inferenceResult.textEmbedding();

			if (!textEmbeddingResults.isEmpty()) {
				TextEmbeddingResult textEmbeddingResult =
					textEmbeddingResults.get(0);

				List<Float> embedding = textEmbeddingResult.embedding();

				return embedding.size();
			}
		}

		return 0;
	}

	private RestClient _getRestClient() {
		ElasticsearchConnection elasticsearchConnection =
			_elasticsearchConnectionManager.getElasticsearchConnection();

		if (elasticsearchConnection == null) {
			return null;
		}

		RestClientTransport restClientTransport =
			elasticsearchConnection.getRestClientTransport();

		return restClientTransport.restClient();
	}

	private InferenceEndpoint _toInferenceEndpoint(
		InferenceEndpointInfo inferenceEndpointInfo) {

		TaskType taskType = inferenceEndpointInfo.taskType();

		return new InferenceEndpoint(
			inferenceEndpointInfo.inferenceId(),
			(taskType == null) ? null : taskType.jsonValue(),
			inferenceEndpointInfo.service());
	}

	private List<InferenceService> _toInferenceServices(JSONArray jsonArray) {
		List<InferenceService> inferenceServices = new ArrayList<>();

		if (jsonArray == null) {
			return inferenceServices;
		}

		for (int i = 0; i < jsonArray.length(); i++) {
			JSONObject serviceJSONObject = jsonArray.getJSONObject(i);

			List<InferenceServiceField> inferenceServiceFields =
				new ArrayList<>();

			JSONObject configurationsJSONObject =
				serviceJSONObject.getJSONObject("configurations");

			if (configurationsJSONObject != null) {
				for (String key : configurationsJSONObject.keySet()) {
					JSONObject fieldJSONObject =
						configurationsJSONObject.getJSONObject(key);

					inferenceServiceFields.add(
						new InferenceServiceField(
							key, fieldJSONObject.getString("label"),
							fieldJSONObject.getString("description"),
							fieldJSONObject.getBoolean("required"),
							fieldJSONObject.getBoolean("sensitive"),
							fieldJSONObject.getString("type"),
							_toStringList(
								fieldJSONObject.getJSONArray(
									"supported_task_types"))));
				}
			}

			inferenceServices.add(
				new InferenceService(
					serviceJSONObject.getString("service"),
					serviceJSONObject.getString("name"),
					_toStringList(serviceJSONObject.getJSONArray("task_types")),
					inferenceServiceFields));
		}

		return inferenceServices;
	}

	private List<String> _toStringList(JSONArray jsonArray) {
		List<String> strings = new ArrayList<>();

		if (jsonArray == null) {
			return strings;
		}

		for (int i = 0; i < jsonArray.length(); i++) {
			strings.add(jsonArray.getString(i));
		}

		return strings;
	}

	private static final Log _log = LogFactoryUtil.getLog(
		ElasticsearchInferenceEndpointRegistry.class);

	@Reference
	private ElasticsearchClientResolver _elasticsearchClientResolver;

	@Reference
	private ElasticsearchConnectionManager _elasticsearchConnectionManager;

	@Reference
	private JSONFactory _jsonFactory;

}