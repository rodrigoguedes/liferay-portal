/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

/**
 * Immutable view of an Elasticsearch Inference Endpoint as seen by Liferay:
 * the {@code inference_id} that mappings reference, the task type (only {@code
 * text_embedding} endpoints back BYO-LLM semantic search), and the provider
 * service (e.g. {@code openai}, {@code hugging_face}, {@code googlevertexai}).
 *
 * @author Rodrigo Guedes de Souza
 */
public class InferenceEndpoint {

	public InferenceEndpoint(
		String inferenceId, String taskType, String service) {

		_inferenceId = inferenceId;
		_taskType = taskType;
		_service = service;
	}

	public String getInferenceId() {
		return _inferenceId;
	}

	public String getService() {
		return _service;
	}

	public String getTaskType() {
		return _taskType;
	}

	private final String _inferenceId;
	private final String _service;
	private final String _taskType;

}