/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.semantic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.inference.GetInferenceResponse;
import co.elastic.clients.elasticsearch.inference.InferenceEndpointInfo;
import co.elastic.clients.elasticsearch.inference.TaskType;

import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.search.elasticsearch8.internal.connection.ElasticsearchClientResolver;
import com.liferay.portal.search.semantic.InferenceEndpoint;
import com.liferay.portal.search.semantic.InferenceEndpointRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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

	private InferenceEndpoint _toInferenceEndpoint(
		InferenceEndpointInfo inferenceEndpointInfo) {

		TaskType taskType = inferenceEndpointInfo.taskType();

		return new InferenceEndpoint(
			inferenceEndpointInfo.inferenceId(),
			(taskType == null) ? null : taskType.jsonValue(),
			inferenceEndpointInfo.service());
	}

	private static final Log _log = LogFactoryUtil.getLog(
		ElasticsearchInferenceEndpointRegistry.class);

	@Reference
	private ElasticsearchClientResolver _elasticsearchClientResolver;

}