/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.configuration.persistence.listener;

import com.liferay.portal.configuration.persistence.listener.ConfigurationModelListener;
import com.liferay.portal.configuration.persistence.listener.ConfigurationModelListenerException;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.MapUtil;
import com.liferay.portal.search.configuration.SemanticSearchConfiguration;
import com.liferay.portal.search.index.IndexNameBuilder;
import com.liferay.portal.search.rest.dto.v1_0.EmbeddingProviderConfiguration;
import com.liferay.portal.search.semantic.SemanticFieldNames;
import com.liferay.portal.search.semantic.SemanticTextEmbeddingIndexMigrationHelper;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Maps the per-locale {@code <assetType>_<lang>_semantic} fields into the
 * company index when the Semantic Search configuration is saved with an
 * inference endpoint provider active. This is the trigger that connects the
 * BYO-LLM configuration to the Elasticsearch mapping: it resolves the {@code
 * inference_id}, asset types, and locales from each inference endpoint provider
 * configuration and delegates to {@link
 * SemanticTextEmbeddingIndexMigrationHelper} (a {@code PUT _mapping}). Until an
 * inference endpoint provider is configured, no {@code semantic_text} field is
 * added. A migration failure aborts the save so the Elasticsearch error
 * surfaces to the administrator.
 *
 * @author Rodrigo Guedes de Souza
 */
@Component(
	property = "model.class.name=com.liferay.portal.search.configuration.SemanticSearchConfiguration",
	service = ConfigurationModelListener.class
)
public class SemanticSearchConfigurationModelListener
	implements ConfigurationModelListener {

	@Override
	public void onAfterSave(String pid, Dictionary<String, Object> properties)
		throws ConfigurationModelListenerException {

		long companyId = GetterUtil.getLong(properties.get("companyId"));

		if ((companyId == 0) ||
			!GetterUtil.getBoolean(properties.get("textEmbeddingsEnabled"))) {

			return;
		}

		String indexName = _indexNameBuilder.getIndexName(companyId);

		for (String textEmbeddingProviderConfigurationJSON :
				GetterUtil.getStringValues(
					properties.get(
						"textEmbeddingProviderConfigurationJSONs"))) {

			_addSemanticTextFields(
				indexName, properties, textEmbeddingProviderConfigurationJSON);
		}
	}

	private void _addSemanticTextFields(
			String indexName, Dictionary<String, Object> properties,
			String textEmbeddingProviderConfigurationJSON)
		throws ConfigurationModelListenerException {

		try {
			EmbeddingProviderConfiguration embeddingProviderConfiguration =
				EmbeddingProviderConfiguration.unsafeToDTO(
					textEmbeddingProviderConfigurationJSON);

			if (!Objects.equals(
					embeddingProviderConfiguration.getProviderName(),
					"inference-endpoint")) {

				return;
			}

			String inferenceId = _getInferenceId(
				embeddingProviderConfiguration);

			if (inferenceId == null) {
				return;
			}

			_semanticTextEmbeddingIndexMigrationHelper.addSemanticTextFields(
				indexName,
				_toAssetTypes(
					embeddingProviderConfiguration.getModelClassNames()),
				_toLocales(embeddingProviderConfiguration.getLanguageIds()),
				inferenceId);
		}
		catch (Exception exception) {
			throw new ConfigurationModelListenerException(
				exception.getMessage(), SemanticSearchConfiguration.class,
				getClass(), properties);
		}
	}

	private String _getInferenceId(
		EmbeddingProviderConfiguration embeddingProviderConfiguration) {

		Map<String, Object> attributes =
			(Map<String, Object>)embeddingProviderConfiguration.getAttributes();

		if (attributes == null) {
			return null;
		}

		return MapUtil.getString(attributes, "inferenceId", null);
	}

	private List<String> _toAssetTypes(String[] modelClassNames) {
		List<String> assetTypes = new ArrayList<>();

		if (modelClassNames == null) {
			return assetTypes;
		}

		for (String modelClassName : modelClassNames) {
			assetTypes.add(SemanticFieldNames.assetType(modelClassName));
		}

		return assetTypes;
	}

	private List<Locale> _toLocales(String[] languageIds) {
		List<Locale> locales = new ArrayList<>();

		if (languageIds == null) {
			return locales;
		}

		for (String languageId : languageIds) {
			locales.add(LocaleUtil.fromLanguageId(languageId, false));
		}

		return locales;
	}

	@Reference
	private IndexNameBuilder _indexNameBuilder;

	@Reference
	private SemanticTextEmbeddingIndexMigrationHelper
		_semanticTextEmbeddingIndexMigrationHelper;

}