/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Resolves the Elasticsearch Inference Endpoint id (the {@code inference_id}
 * written into {@code semantic_text} mappings) for a company from its active
 * Semantic Search configuration.
 *
 * <p>
 * The endpoint name is dynamic — chosen or entered by the administrator when
 * the Inference Endpoint provider is configured — rather than a fixed
 * Liferay-managed constant. Returns {@code null} when no Inference Endpoint
 * provider is active for the company.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@ProviderType
public interface InferenceIdResolver {

	public String resolveInferenceId(long companyId);

}