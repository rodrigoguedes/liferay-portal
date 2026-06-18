/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

import java.util.List;
import java.util.Map;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Reads Elasticsearch Inference Endpoints so the Semantic Search admin UI can
 * list the {@code text_embedding} endpoints available for BYO-LLM and so the
 * provider can validate a configured endpoint.
 *
 * @author Rodrigo Guedes de Souza
 */
@ProviderType
public interface InferenceEndpointRegistry {

	/**
	 * Creates a {@code text_embedding} Inference Endpoint named {@code
	 * inferenceId} for the given {@code service} (e.g. {@code openai}) with the
	 * provider settings the admin supplied, via {@code PUT _inference}. The
	 * endpoint name is dynamic — the administrator chooses it. Throws when
	 * Elasticsearch rejects the request so the caller can surface the error.
	 */
	public void createTextEmbeddingInferenceEndpoint(
			String inferenceId, String service,
			Map<String, Object> serviceSettings)
		throws Exception;

	/**
	 * Returns the endpoint with the given id, or {@code null} when it does not
	 * exist (or the call fails).
	 */
	public InferenceEndpoint getInferenceEndpoint(String inferenceId);

	/**
	 * Returns every Inference Endpoint whose task type is {@code
	 * text_embedding}; an empty list when none exist or the call fails.
	 */
	public List<InferenceEndpoint> getTextEmbeddingInferenceEndpoints();

	/**
	 * Returns the providers Elasticsearch can create {@code text_embedding}
	 * Inference Endpoints for, each with the configuration fields the admin
	 * creation form must render. Read from {@code GET
	 * _inference/_services/text_embedding}; an empty list when unavailable.
	 */
	public List<InferenceService> getTextEmbeddingInferenceServices()
		throws Exception;

	/**
	 * Sends a sample text to the endpoint via {@code POST _inference/<id>} and
	 * returns the number of dimensions in the resulting embedding, so the admin
	 * "Test configuration" action can confirm the endpoint actually works.
	 * Throws when the endpoint is unreachable or rejects the request (for
	 * example, an invalid API key) so the caller can surface the Elasticsearch
	 * error.
	 */
	public int testTextEmbeddingInferenceEndpoint(String inferenceId)
		throws Exception;

}