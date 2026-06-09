/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.semantic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.inference.ElasticsearchInferenceClient;
import co.elastic.clients.elasticsearch.inference.GetInferenceResponse;
import co.elastic.clients.elasticsearch.inference.InferenceEndpointInfo;
import co.elastic.clients.elasticsearch.inference.TaskType;
import co.elastic.clients.json.JsonData;

import com.liferay.portal.kernel.test.ReflectionTestUtil;
import com.liferay.portal.search.elasticsearch8.internal.connection.ElasticsearchClientResolver;
import com.liferay.portal.search.semantic.InferenceEndpoint;
import com.liferay.portal.test.rule.LiferayUnitTestRule;

import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.Mockito;

/**
 * @author Rodrigo Guedes de Souza
 */
public class ElasticsearchInferenceEndpointRegistryTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Before
	public void setUp() {
		_elasticsearchInferenceEndpointRegistry =
			new ElasticsearchInferenceEndpointRegistry();

		ReflectionTestUtil.setFieldValue(
			_elasticsearchInferenceEndpointRegistry,
			"_elasticsearchClientResolver", _elasticsearchClientResolver);
	}

	@Test
	public void testGetTextEmbeddingInferenceEndpointsFiltersByTaskType()
		throws Exception {

		_setUpInferenceEndpoints(
			_inferenceEndpointInfo(
				"openai-embeddings", "openai", TaskType.TextEmbedding),
			_inferenceEndpointInfo(
				"openai-chat", "openai", TaskType.Completion));

		List<InferenceEndpoint> inferenceEndpoints =
			_elasticsearchInferenceEndpointRegistry.
				getTextEmbeddingInferenceEndpoints();

		Assert.assertEquals(
			inferenceEndpoints.toString(), 1, inferenceEndpoints.size());

		InferenceEndpoint inferenceEndpoint = inferenceEndpoints.get(0);

		Assert.assertEquals(
			"openai-embeddings", inferenceEndpoint.getInferenceId());
		Assert.assertEquals("openai", inferenceEndpoint.getService());
		Assert.assertEquals("text_embedding", inferenceEndpoint.getTaskType());
	}

	@Test
	public void testGetTextEmbeddingInferenceEndpointsWhenNone()
		throws Exception {

		_setUpInferenceEndpoints(
			_inferenceEndpointInfo(
				"openai-chat", "openai", TaskType.Completion));

		List<InferenceEndpoint> inferenceEndpoints =
			_elasticsearchInferenceEndpointRegistry.
				getTextEmbeddingInferenceEndpoints();

		Assert.assertTrue(
			inferenceEndpoints.toString(), inferenceEndpoints.isEmpty());
	}

	private InferenceEndpointInfo _inferenceEndpointInfo(
		String inferenceId, String service, TaskType taskType) {

		InferenceEndpointInfo.Builder builder =
			new InferenceEndpointInfo.Builder();

		builder.inferenceId(inferenceId);
		builder.service(service);
		builder.serviceSettings(JsonData.of(Collections.emptyMap()));
		builder.taskType(taskType);

		return builder.build();
	}

	private void _setUpInferenceEndpoints(
			InferenceEndpointInfo... inferenceEndpointInfos)
		throws Exception {

		GetInferenceResponse getInferenceResponse = GetInferenceResponse.of(
			builder -> builder.endpoints(List.of(inferenceEndpointInfos)));

		ElasticsearchInferenceClient elasticsearchInferenceClient =
			Mockito.mock(ElasticsearchInferenceClient.class);

		Mockito.when(
			elasticsearchInferenceClient.get()
		).thenReturn(
			getInferenceResponse
		);

		ElasticsearchClient elasticsearchClient = Mockito.mock(
			ElasticsearchClient.class);

		Mockito.when(
			elasticsearchClient.inference()
		).thenReturn(
			elasticsearchInferenceClient
		);

		Mockito.when(
			_elasticsearchClientResolver.getElasticsearchClient()
		).thenReturn(
			elasticsearchClient
		);
	}

	private final ElasticsearchClientResolver _elasticsearchClientResolver =
		Mockito.mock(ElasticsearchClientResolver.class);
	private ElasticsearchInferenceEndpointRegistry
		_elasticsearchInferenceEndpointRegistry;

}