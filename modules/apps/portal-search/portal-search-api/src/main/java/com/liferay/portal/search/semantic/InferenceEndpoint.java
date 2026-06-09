/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

/**
 * Immutable view of an Elasticsearch Inference Endpoint as seen by Liferay:
 * the {@code inference_id} that mappings reference, the task type (only {@code
 * text_embedding} endpoints back BYO-LLM semantic search), the provider
 * service (e.g. {@code openai}, {@code hugging_face}, {@code googlevertexai}),
 * and the model metadata ({@code model_id}, {@code dimensions}, {@code
 * similarity}) read from {@code service_settings}. The metadata is what {@link
 * SemanticEndpointChanges} compares to decide whether switching the configured
 * endpoint is an equivalent change (a rename or API key rotation for the same
 * model) or a breaking one that requires reembedding.
 *
 * @author Rodrigo Guedes de Souza
 */
public class InferenceEndpoint {

	public InferenceEndpoint(
		String inferenceId, String taskType, String service) {

		this(inferenceId, taskType, service, null, 0, null);
	}

	public InferenceEndpoint(
		String inferenceId, String taskType, String service, String modelId,
		int dimensions, String similarity) {

		_inferenceId = inferenceId;
		_taskType = taskType;
		_service = service;
		_modelId = modelId;
		_dimensions = dimensions;
		_similarity = similarity;
	}

	public int getDimensions() {
		return _dimensions;
	}

	public String getInferenceId() {
		return _inferenceId;
	}

	public String getModelId() {
		return _modelId;
	}

	public String getService() {
		return _service;
	}

	public String getSimilarity() {
		return _similarity;
	}

	public String getTaskType() {
		return _taskType;
	}

	private final int _dimensions;
	private final String _inferenceId;
	private final String _modelId;
	private final String _service;
	private final String _similarity;
	private final String _taskType;

}