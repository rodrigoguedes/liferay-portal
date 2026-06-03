/**
 * SPDX-FileCopyrightText: (c) 2024 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.ml.embedding.text;

import com.liferay.object.model.ObjectEntry;
import com.liferay.petra.string.CharPool;
import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.feature.flag.FeatureFlagManagerUtil;
import com.liferay.portal.kernel.language.Language;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.AuditedModel;
import com.liferay.portal.kernel.model.BaseModel;
import com.liferay.portal.kernel.model.GroupedModel;
import com.liferay.portal.kernel.model.ShardedModel;
import com.liferay.portal.kernel.model.StagedModel;
import com.liferay.portal.kernel.model.WorkflowedModel;
import com.liferay.portal.kernel.search.Document;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.security.auth.CompanyThreadLocal;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.CamelCaseUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.KeyValuePair;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.search.capabilities.ExternalEmbeddingCapabilityGate;
import com.liferay.portal.search.capabilities.ExternalEmbeddingEligibility;
import com.liferay.portal.search.configuration.SemanticSearchConfiguration;
import com.liferay.portal.search.configuration.SemanticSearchConfigurationProvider;
import com.liferay.portal.search.engine.SearchEngineInformation;
import com.liferay.portal.search.ml.embedding.text.TextEmbeddingDocumentContributor;
import com.liferay.portal.search.ml.embedding.text.TextEmbeddingRetriever;
import com.liferay.portal.search.rest.dto.v1_0.EmbeddingProviderConfiguration;
import com.liferay.portal.search.semantic.SemanticFieldNames;
import com.liferay.portal.search.semantic.SemanticProviderType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Petteri Karttunen
 */
@Component(
	property = "semantic.field.groups=Title:title|Content/Body:content|Categorization:assetCategoryTitles|Tags:assetTagNames",
	service = TextEmbeddingDocumentContributor.class
)
public class TextEmbeddingDocumentContributorImpl
	implements TextEmbeddingDocumentContributor {

	@Override
	public <T extends BaseModel<T>> void contribute(
		Document document, String languageId, T model, String text) {

		if (Validator.isBlank(text)) {
			return;
		}

		EmbeddingProviderConfiguration embeddingProviderConfiguration =
			getEmbeddingProviderConfiguration(model);

		if (embeddingProviderConfiguration == null) {
			return;
		}

		List<String> languageIds = Arrays.asList(
			embeddingProviderConfiguration.getLanguageIds());

		if (!languageIds.contains(languageId)) {
			return;
		}

		if (_isBYOLLMEnabled(embeddingProviderConfiguration, model)) {

			// Elasticsearch generates the embeddings server-side from the
			// semantic_text fields

			_addSemanticTextField(
				_getAssetType(model), document, languageId,
				LocaleUtil.fromLanguageId(languageId));

			return;
		}

		Double[] textEmbedding = _textEmbeddingRetriever.getTextEmbedding(
			embeddingProviderConfiguration.getProviderName(), text);

		if (textEmbedding.length == 0) {
			return;
		}

		_addTextEmbeddingField(document, languageId, textEmbedding);
	}

	@Override
	public <T extends BaseModel<T>> void contribute(
		Document document, T model, String text) {

		if (Validator.isBlank(text)) {
			return;
		}

		EmbeddingProviderConfiguration embeddingProviderConfiguration =
			getEmbeddingProviderConfiguration(model);

		if (embeddingProviderConfiguration == null) {
			return;
		}

		if (_isBYOLLMEnabled(embeddingProviderConfiguration, model)) {

			// Elasticsearch generates the embeddings server-side from the
			// semantic_text fields

			String assetType = _getAssetType(model);

			List<String> languageIds = Arrays.asList(
				embeddingProviderConfiguration.getLanguageIds());

			for (Locale locale :
					_language.getAvailableLocales(_getGroupId(model))) {

				String languageId = LocaleUtil.toLanguageId(locale);

				if (!languageIds.contains(languageId)) {
					continue;
				}

				_addSemanticTextField(assetType, document, languageId, locale);
			}

			return;
		}

		Double[] textEmbedding = _textEmbeddingRetriever.getTextEmbedding(
			embeddingProviderConfiguration.getProviderName(), text);

		if (textEmbedding.length == 0) {
			return;
		}

		List<String> languageIds = Arrays.asList(
			embeddingProviderConfiguration.getLanguageIds());

		for (Locale locale :
				_language.getAvailableLocales(_getGroupId(model))) {

			String languageId = LocaleUtil.toLanguageId(locale);

			if (!languageIds.contains(languageId)) {
				continue;
			}

			_addTextEmbeddingField(document, languageId, textEmbedding);
		}
	}

	@Override
	public <T extends BaseModel<T>> List<String> getLanguageIds(T model) {
		EmbeddingProviderConfiguration embeddingProviderConfiguration =
			getEmbeddingProviderConfiguration(model);

		if (embeddingProviderConfiguration == null) {
			return Collections.emptyList();
		}

		return Arrays.asList(embeddingProviderConfiguration.getLanguageIds());
	}

	@Activate
	@Modified
	protected void activate(Map<String, Object> properties) {
		_semanticFieldGroups = _parseSemanticFieldGroups(
			GetterUtil.getString(properties.get("semantic.field.groups")));

		Map<String, List<KeyValuePair>> semanticFieldGroupsMap =
			new HashMap<>();

		for (Map.Entry<String, Object> entry : properties.entrySet()) {
			String key = entry.getKey();

			if (!key.startsWith("semantic.field.groups.")) {
				continue;
			}

			semanticFieldGroupsMap.put(
				key.substring("semantic.field.groups.".length()),
				_parseSemanticFieldGroups(
					GetterUtil.getString(entry.getValue())));
		}

		_semanticFieldGroupsMap = semanticFieldGroupsMap;
	}

	protected <T extends BaseModel<T>> EmbeddingProviderConfiguration
		getEmbeddingProviderConfiguration(T model) {

		if (!_isSupportedSearchEngine() || !isIndexableStatus(model)) {
			return null;
		}

		long companyId = _getCompanyId(model);

		if (companyId == 0) {
			return null;
		}

		SemanticSearchConfiguration semanticSearchConfiguration =
			semanticSearchConfigurationProvider.getCompanyConfiguration(
				companyId);

		if (!semanticSearchConfiguration.textEmbeddingsEnabled()) {
			return null;
		}

		Class<?> clazz = model.getModelClass();

		String modelClassName = clazz.getName();

		if (model instanceof ObjectEntry) {
			ObjectEntry objectEntry = (ObjectEntry)model;

			modelClassName = objectEntry.getModelClassName();
		}

		try {
			for (String textEmbeddingProviderConfigurationJSON :
					semanticSearchConfiguration.
						textEmbeddingProviderConfigurationJSONs()) {

				EmbeddingProviderConfiguration embeddingProviderConfiguration =
					EmbeddingProviderConfiguration.unsafeToDTO(
						textEmbeddingProviderConfigurationJSON);

				if (!ArrayUtil.contains(
						embeddingProviderConfiguration.getModelClassNames(),
						modelClassName)) {

					continue;
				}

				if (ArrayUtil.isNotEmpty(
						embeddingProviderConfiguration.getLanguageIds())) {

					return embeddingProviderConfiguration;
				}
			}
		}
		catch (Exception exception) {
			_log.error(exception);
		}

		return null;
	}

	protected String getTextEmbeddingFieldName(
		int dimensions, String languageId) {

		return StringBundler.concat(
			"text_embedding_", dimensions, StringPool.UNDERLINE, languageId);
	}

	protected <T extends BaseModel<T>> boolean isIndexableStatus(T model) {
		if (model instanceof WorkflowedModel) {
			WorkflowedModel workflowedModel = (WorkflowedModel)model;

			if (workflowedModel.getStatus() ==
					WorkflowConstants.STATUS_APPROVED) {

				return true;
			}

			return false;
		}

		return true;
	}

	@Reference
	protected SemanticSearchConfigurationProvider
		semanticSearchConfigurationProvider;

	private void _addSemanticTextField(
		String assetType, Document document, String languageId, Locale locale) {

		String semanticText = _getSemanticText(assetType, document, languageId);

		if (Validator.isBlank(semanticText)) {
			return;
		}

		document.addText(
			_semanticFieldNames.fieldName(
				locale, SemanticProviderType.BYO_LLM, assetType, 0),
			semanticText);
	}

	private void _addTextEmbeddingField(
		Document document, String languageId, Double[] textEmbedding) {

		Field field = new Field(
			getTextEmbeddingFieldName(textEmbedding.length, languageId));

		field.setNumeric(true);
		field.setNumericClass(Double.class);
		field.setTokenized(false);
		field.setValues(ArrayUtil.toStringArray(textEmbedding));

		document.add(field);
	}

	private <T extends BaseModel<T>> String _getAssetType(T model) {
		Class<?> clazz = model.getModelClass();

		return StringUtil.toLowerCase(
			CamelCaseUtil.fromCamelCase(
				clazz.getSimpleName(), CharPool.UNDERLINE));
	}

	private <T extends BaseModel<T>> long _getCompanyId(T model) {
		if (model instanceof AuditedModel) {
			AuditedModel companyModel = (AuditedModel)model;

			return companyModel.getCompanyId();
		}
		else if (model instanceof ShardedModel) {
			ShardedModel shardedModel = (ShardedModel)model;

			return shardedModel.getCompanyId();
		}
		else if (model instanceof StagedModel) {
			StagedModel stagedModel = (StagedModel)model;

			return stagedModel.getCompanyId();
		}

		return CompanyThreadLocal.getCompanyId();
	}

	private String _getFieldValuesText(
		Document document, String fieldName, String languageId) {

		Field field = document.getField(
			StringBundler.concat(fieldName, StringPool.UNDERLINE, languageId));

		if (field == null) {
			field = document.getField(fieldName);
		}

		if (field == null) {
			return StringPool.BLANK;
		}

		String[] values = field.getValues();

		StringBundler sb = new StringBundler(values.length * 2);

		for (String value : values) {
			if (Validator.isBlank(value)) {
				continue;
			}

			sb.append(value);
			sb.append(StringPool.COMMA_AND_SPACE);
		}

		if (sb.index() == 0) {
			return StringPool.BLANK;
		}

		sb.setIndex(sb.index() - 1);

		return sb.toString();
	}

	private <T extends BaseModel<T>> long _getGroupId(T model) {
		if (model instanceof GroupedModel) {
			GroupedModel groupedModel = (GroupedModel)model;

			return groupedModel.getGroupId();
		}

		return 0;
	}

	private String _getSemanticText(
		String assetType, Document document, String languageId) {

		List<KeyValuePair> keyValuePairs = _semanticFieldGroupsMap.getOrDefault(
			assetType, _semanticFieldGroups);

		StringBundler sb = new StringBundler(keyValuePairs.size() * 5);

		for (KeyValuePair keyValuePair : keyValuePairs) {
			String fieldValuesText = _getFieldValuesText(
				document, keyValuePair.getValue(), languageId);

			if (Validator.isBlank(fieldValuesText)) {
				continue;
			}

			sb.append(keyValuePair.getKey());
			sb.append(StringPool.COLON);
			sb.append(StringPool.SPACE);
			sb.append(fieldValuesText);
			sb.append(StringPool.NEW_LINE);
		}

		if (sb.index() == 0) {
			return StringPool.BLANK;
		}

		sb.setIndex(sb.index() - 1);

		return sb.toString();
	}

	private <T extends BaseModel<T>> boolean _isBYOLLMEnabled(
		EmbeddingProviderConfiguration embeddingProviderConfiguration,
		T model) {

		if (!Objects.equals(
				embeddingProviderConfiguration.getProviderName(),
				_BYO_LLM_PROVIDER_NAME) ||
			!FeatureFlagManagerUtil.isEnabled(
				_getCompanyId(model), "LPD-11319")) {

			return false;
		}

		ExternalEmbeddingEligibility externalEmbeddingEligibility =
			_externalEmbeddingCapabilityGate.check();

		return externalEmbeddingEligibility.isAvailable();
	}

	private boolean _isSupportedSearchEngine() {
		return !Objects.equals(
			_searchEngineInformation.getVendorString(), "Solr");
	}

	private List<KeyValuePair> _parseSemanticFieldGroups(String value) {
		List<KeyValuePair> keyValuePairs = new ArrayList<>();

		for (String semanticFieldGroup :
				StringUtil.split(value, CharPool.PIPE)) {

			int index = semanticFieldGroup.indexOf(CharPool.COLON);

			if (index <= 0) {
				continue;
			}

			keyValuePairs.add(
				new KeyValuePair(
					semanticFieldGroup.substring(0, index),
					semanticFieldGroup.substring(index + 1)));
		}

		return keyValuePairs;
	}

	private static final String _BYO_LLM_PROVIDER_NAME =
		"Elasticsearch Inference Endpoint";

	private static final Log _log = LogFactoryUtil.getLog(
		TextEmbeddingDocumentContributorImpl.class);

	@Reference
	private ExternalEmbeddingCapabilityGate _externalEmbeddingCapabilityGate;

	@Reference
	private Language _language;

	@Reference
	private SearchEngineInformation _searchEngineInformation;

	private volatile List<KeyValuePair> _semanticFieldGroups;
	private volatile Map<String, List<KeyValuePair>> _semanticFieldGroupsMap;

	@Reference
	private SemanticFieldNames _semanticFieldNames;

	@Reference
	private TextEmbeddingRetriever _textEmbeddingRetriever;

}