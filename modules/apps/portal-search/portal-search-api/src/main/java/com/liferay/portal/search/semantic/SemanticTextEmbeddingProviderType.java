/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

/**
 * Selects which embedding strategy is active, and therefore which
 * field-name shape {@link SemanticFieldNames} produces. The axis is who
 * provides the embedding:
 *
 * <ul>
 * <li>{@link #ELASTICSEARCH_PROVIDED} — Elasticsearch computes the embedding
 * from a {@code semantic_text} field bound to an Inference Endpoint (BYO-LLM).
 * </li>
 * <li>{@link #LIFERAY_PROVIDED} — Liferay calls the provider directly and
 * stores the vector in a {@code dense_vector} field (the integrated
 * pipeline).</li>
 * </ul>
 *
 * @author Rodrigo Guedes de Souza
 */
public enum SemanticTextEmbeddingProviderType {

	ELASTICSEARCH_PROVIDED, LIFERAY_PROVIDED

}