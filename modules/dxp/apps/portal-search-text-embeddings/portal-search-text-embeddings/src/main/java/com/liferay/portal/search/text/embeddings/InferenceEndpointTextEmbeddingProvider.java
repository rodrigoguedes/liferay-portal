/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.text.embeddings;

import com.liferay.portal.search.rest.dto.v1_0.EmbeddingProviderConfiguration;
import com.liferay.portal.search.rest.text.embeddings.configuration.TextEmbeddingProvider;

import org.osgi.service.component.annotations.Component;

/**
 * Marks the BYO-LLM strategy where Elasticsearch owns the embedding: the
 * {@code semantic_text} field's Inference Endpoint embeds the content at index
 * time and the query keyword at search time. Unlike the other providers, this
 * one never calls an external LLM from Liferay, so {@link #getEmbedding} returns
 * no client-side vector.
 *
 * @author Rodrigo Guedes de Souza
 */
@Component(enabled = false, service = TextEmbeddingProvider.class)
public class InferenceEndpointTextEmbeddingProvider
	implements TextEmbeddingProvider {

	@Override
	public Double[] getEmbedding(
		EmbeddingProviderConfiguration embeddingProviderConfiguration,
		String text) {

		return new Double[0];
	}

	@Override
	public String getProviderName() {
		return "inference-endpoint";
	}

}