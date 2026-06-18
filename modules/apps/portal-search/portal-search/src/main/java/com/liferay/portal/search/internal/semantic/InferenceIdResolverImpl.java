/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.semantic;

import com.liferay.portal.kernel.util.MapUtil;
import com.liferay.portal.search.configuration.SemanticSearchConfiguration;
import com.liferay.portal.search.configuration.SemanticSearchConfigurationProvider;
import com.liferay.portal.search.rest.dto.v1_0.EmbeddingProviderConfiguration;
import com.liferay.portal.search.semantic.InferenceIdResolver;

import java.util.Map;
import java.util.Objects;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Resolves the Inference Endpoint id from a company's Semantic Search
 * configuration. The endpoint name is dynamic — the administrator enters or
 * selects it when configuring the {@code inference-endpoint} provider — and is
 * stored in that provider configuration's {@code inferenceId} attribute.
 *
 * @author Rodrigo Guedes de Souza
 */
@Component(service = InferenceIdResolver.class)
public class InferenceIdResolverImpl implements InferenceIdResolver {

	@Override
	public String resolveInferenceId(long companyId) {
		SemanticSearchConfiguration semanticSearchConfiguration =
			_semanticSearchConfigurationProvider.getCompanyConfiguration(
				companyId);

		if (!semanticSearchConfiguration.textEmbeddingsEnabled()) {
			return null;
		}

		for (String textEmbeddingProviderConfigurationJSON :
				semanticSearchConfiguration.
					textEmbeddingProviderConfigurationJSONs()) {

			EmbeddingProviderConfiguration embeddingProviderConfiguration =
				EmbeddingProviderConfiguration.unsafeToDTO(
					textEmbeddingProviderConfigurationJSON);

			if (!Objects.equals(
					embeddingProviderConfiguration.getProviderName(),
					"inference-endpoint")) {

				continue;
			}

			Map<String, Object> attributes =
				(Map<String, Object>)
					embeddingProviderConfiguration.getAttributes();

			if (attributes == null) {
				return null;
			}

			return MapUtil.getString(attributes, "inferenceId", null);
		}

		return null;
	}

	@Reference
	private SemanticSearchConfigurationProvider
		_semanticSearchConfigurationProvider;

}