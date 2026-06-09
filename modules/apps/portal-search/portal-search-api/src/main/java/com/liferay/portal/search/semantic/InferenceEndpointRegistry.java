/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

import java.util.List;

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
	 * Returns the endpoint with the given id, or {@code null} when it does not
	 * exist (or the call fails).
	 */
	public InferenceEndpoint getInferenceEndpoint(String inferenceId);

	/**
	 * Returns every Inference Endpoint whose task type is {@code
	 * text_embedding}; an empty list when none exist or the call fails.
	 */
	public List<InferenceEndpoint> getTextEmbeddingInferenceEndpoints();

}