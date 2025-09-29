/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.object.internal.search.spi.model.index.contributor;

import com.liferay.account.model.AccountEntryOrganizationRel;
import com.liferay.account.service.AccountEntryOrganizationRelLocalService;
import com.liferay.object.constants.ObjectEntryFolderConstants;
import com.liferay.object.constants.ObjectFieldConstants;
import com.liferay.object.entry.util.ObjectEntryValuesUtil;
import com.liferay.object.model.ObjectDefinition;
import com.liferay.object.model.ObjectEntry;
import com.liferay.object.model.ObjectEntryFolder;
import com.liferay.object.model.ObjectField;
import com.liferay.object.model.ObjectFolder;
import com.liferay.object.rest.dto.v1_0.ListEntry;
import com.liferay.object.service.ObjectDefinitionLocalService;
import com.liferay.object.service.ObjectEntryFolderLocalService;
import com.liferay.object.service.ObjectEntryLocalService;
import com.liferay.object.service.ObjectFieldLocalService;
import com.liferay.object.service.ObjectFolderLocalService;
import com.liferay.petra.function.transform.TransformUtil;
import com.liferay.petra.string.CharPool;
import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.feature.flag.FeatureFlagManagerUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.search.Document;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.FieldArray;
import com.liferay.portal.kernel.util.Base64;
import com.liferay.portal.kernel.util.BigDecimalUtil;
import com.liferay.portal.kernel.util.FastDateFormatFactoryUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.ListUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.MapUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.search.spi.model.index.contributor.ModelDocumentContributor;

import java.io.Serializable;

import java.math.BigDecimal;

import java.text.Format;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * @author Marco Leo
 * @author Brian Wing Shun Chan
 */
public class ObjectEntryModelDocumentContributor
	implements ModelDocumentContributor<ObjectEntry> {

	public ObjectEntryModelDocumentContributor(
		AccountEntryOrganizationRelLocalService
			accountEntryOrganizationRelLocalService,
		String className,
		ObjectDefinitionLocalService objectDefinitionLocalService,
		ObjectEntryFolderLocalService objectEntryFolderLocalService,
		ObjectEntryLocalService objectEntryLocalService,
		ObjectFieldLocalService objectFieldLocalService,
		ObjectFolderLocalService objectFolderLocalService) {

		_accountEntryOrganizationRelLocalService =
			accountEntryOrganizationRelLocalService;
		_className = className;
		_objectDefinitionLocalService = objectDefinitionLocalService;
		_objectEntryFolderLocalService = objectEntryFolderLocalService;
		_objectEntryLocalService = objectEntryLocalService;
		_objectFieldLocalService = objectFieldLocalService;
		_objectFolderLocalService = objectFolderLocalService;
	}

	@Override
	public void contribute(Document document, ObjectEntry objectEntry) {
		try {
			_contribute(document, objectEntry);
		}
		catch (Exception exception) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"Unable to index object entry " +
						objectEntry.getObjectEntryId(),
					exception);
			}
		}
	}

	private void _addField(
		FieldArray fieldArray, String fieldName, String valueFieldName,
		String value) {

		Field field = new Field("");

		field.addField(new Field("fieldName", fieldName));
		field.addField(new Field("valueFieldName", valueFieldName));
		field.addField(new Field(valueFieldName, value));

		fieldArray.addField(field);
	}

	private void _appendToContent(
		StringBundler sb, String objectFieldName, String valueString) {

		sb.append(objectFieldName);
		sb.append(": ");
		sb.append(valueString);
		sb.append(StringPool.COMMA_AND_SPACE);
	}

	private String _contribute(
		Document document, FieldArray fieldArray, String fieldName,
		Object fieldValue, String locale, ObjectDefinition objectDefinition,
		ObjectEntry objectEntry, ObjectField objectField,
		Map<String, Serializable> values) {

		if (!objectField.isIndexed()) {
			return null;
		}

		if (fieldValue == null) {
			if (_log.isDebugEnabled()) {
				_log.debug(
					StringBundler.concat(
						"Object entry ", objectEntry.getObjectEntryId(),
						" has object field \"", objectField.getName(),
						"\" with a null value"));
			}

			return null;
		}

		if (StringUtil.equals(
				objectField.getBusinessType(),
				ObjectFieldConstants.BUSINESS_TYPE_ATTACHMENT) ||
			StringUtil.equals(
				objectField.getBusinessType(),
				ObjectFieldConstants.BUSINESS_TYPE_RICH_TEXT)) {

			fieldValue = ObjectEntryValuesUtil.getValueString(
				objectField, values);
		}
		else if (StringUtil.equals(
					objectField.getBusinessType(),
					ObjectFieldConstants.BUSINESS_TYPE_MULTISELECT_PICKLIST) &&
				 (fieldValue instanceof List)) {

			fieldValue = ListUtil.toString(
				(List)fieldValue, (String)null, StringPool.COMMA_AND_SPACE);
		}
		else if (StringUtil.equals(
					objectField.getBusinessType(),
					ObjectFieldConstants.BUSINESS_TYPE_PICKLIST) &&
				 (fieldValue instanceof ListEntry)) {

			ListEntry listEntry = (ListEntry)fieldValue;

			fieldValue = listEntry.getKey();
		}
		else if (StringUtil.equals(
					objectField.getBusinessType(),
					ObjectFieldConstants.BUSINESS_TYPE_PRECISION_DECIMAL)) {

			fieldValue = BigDecimalUtil.stripTrailingZeros(
				(BigDecimal)fieldValue);
		}
		else if (Objects.equals(
					objectDefinition.getAccountEntryRestrictedObjectFieldId(),
					objectField.getObjectFieldId())) {

			Long accountEntryId = (Long)fieldValue;

			document.addKeyword(
				"accountEntryRestrictedObjectFieldValue", accountEntryId);

			document.addKeyword(
				"accountEntryRestrictedOrganizationIds",
				TransformUtil.transformToArray(
					_accountEntryOrganizationRelLocalService.
						getAccountEntryOrganizationRels(accountEntryId),
					AccountEntryOrganizationRel::getOrganizationId,
					Long.class));
		}

		String valueString = String.valueOf(fieldValue);
		String contentValue = null;

		if (objectField.isIndexedAsKeyword()) {
			_addField(
				fieldArray, fieldName, "value_keyword",
				StringUtil.lowerCase(valueString));

			contentValue = valueString;
		}
		else if (fieldValue instanceof BigDecimal) {
			_addField(fieldArray, fieldName, "value_double", valueString);

			contentValue = valueString;
		}
		else if (fieldValue instanceof Boolean) {
			_addField(fieldArray, fieldName, "value_boolean", valueString);
			_addField(
				fieldArray, fieldName, "value_keyword",
				_translate((Boolean)fieldValue));

			contentValue = valueString;
		}
		else if (fieldValue instanceof Date) {
			String dateString = _getDateString(fieldValue);

			_addField(fieldArray, fieldName, "value_date", dateString);

			contentValue = dateString;
		}
		else if (fieldValue instanceof Double) {
			_addField(fieldArray, fieldName, "value_double", valueString);

			contentValue = valueString;
		}
		else if (fieldValue instanceof Integer) {
			_addField(fieldArray, fieldName, "value_integer", valueString);

			contentValue = valueString;
		}
		else if (fieldValue instanceof Long) {
			_addField(fieldArray, fieldName, "value_long", valueString);

			contentValue = valueString;
		}
		else if (fieldValue instanceof String) {
			if (Validator.isBlank(objectField.getIndexedLanguageId())) {
				_addField(fieldArray, fieldName, "value_text", valueString);
			}
			else if (objectField.isLocalized()) {
				_addField(
					fieldArray, fieldName, "value_" + locale, valueString);
			}
			else {
				_addField(
					fieldArray, fieldName,
					"value_" + objectField.getIndexedLanguageId(), valueString);
			}

			_addField(
				fieldArray, fieldName, "value_keyword_lowercase",
				_getSortableValue(valueString));

			contentValue = valueString;
		}
		else if (fieldValue instanceof byte[]) {
			_addField(
				fieldArray, fieldName, "value_binary",
				Base64.encode((byte[])fieldValue));
		}
		else {
			if (_log.isWarnEnabled()) {
				_log.warn(
					StringBundler.concat(
						"Object entry ", objectEntry.getObjectEntryId(),
						" has object field \"", fieldName,
						"\" with unsupported value ", fieldValue));
			}
		}

		return contentValue;
	}

	private void _contribute(Document document, ObjectEntry objectEntry)
		throws Exception {

		if (_log.isDebugEnabled()) {
			_log.debug("Document " + document);
			_log.debug("Object entry " + objectEntry);
		}

		document.add(
			new Field(
				Field.getSortableFieldName(Field.ENTRY_CLASS_PK),
				document.get(Field.ENTRY_CLASS_PK)));

		FieldArray fieldArray = (FieldArray)document.getField(
			"nestedFieldArray");

		if (fieldArray == null) {
			fieldArray = new FieldArray("nestedFieldArray");

			document.add(fieldArray);
		}

		document.addKeyword(
			"objectDefinitionId", objectEntry.getObjectDefinitionId());

		ObjectDefinition objectDefinition =
			_objectDefinitionLocalService.fetchObjectDefinition(
				objectEntry.getObjectDefinitionId());

		document.addKeyword(
			"objectDefinitionName", objectDefinition.getShortName());

		Map<String, Serializable> values = objectEntry.getValues();

		List<ObjectField> objectFields =
			_objectFieldLocalService.getObjectFields(
				objectEntry.getObjectDefinitionId(), false);

		String defaultLanguageId = GetterUtil.getString(
			objectEntry.getDefaultLanguageId(),
			objectDefinition.getDefaultLanguageId());

		if (Validator.isNull(defaultLanguageId)) {
			defaultLanguageId = LocaleUtil.toLanguageId(
				LocaleUtil.getDefault());
		}

		Set<String> availableLanguageIds = new TreeSet<>();

		if (Validator.isNotNull(defaultLanguageId)) {
			availableLanguageIds.add(defaultLanguageId);
		}

		for (ObjectField objectField : objectFields) {
			if (!objectField.isLocalized()) {
				continue;
			}

			Map<String, Object> localizedValues =
				(Map<String, Object>)values.get(
					objectField.getI18nObjectFieldName());

			if (MapUtil.isEmpty(localizedValues)) {
				continue;
			}

			availableLanguageIds.addAll(localizedValues.keySet());
		}

		if (availableLanguageIds.isEmpty()) {
			availableLanguageIds.add(defaultLanguageId);
		}

		Map<String, StringBundler> contentStringBundlers = new TreeMap<>();

		for (String languageId : availableLanguageIds) {
			contentStringBundlers.put(
				languageId, new StringBundler(objectFields.size() * 4));
		}

		StringBundler sb = new StringBundler(objectFields.size() * 4);

		for (ObjectField objectField : objectFields) {
			if (objectField.isLocalized()) {
				Map<String, Object> localizedValues =
					(Map<String, Object>)values.get(
						objectField.getI18nObjectFieldName());

				if (MapUtil.isEmpty(localizedValues)) {
					continue;
				}

				Map<String, String> localizedContentValues = new HashMap<>();

				for (Map.Entry<String, Object> localeEntry :
						localizedValues.entrySet()) {

					String languageId = localeEntry.getKey();

					String contentValue = _contribute(
						document, fieldArray, objectField.getName(),
						localeEntry.getValue(),
						LocaleUtil.fromLanguageId(
							languageId, true, false
						).toString(),
						objectDefinition, objectEntry, objectField, values);

					if (contentValue != null) {
						localizedContentValues.put(languageId, contentValue);
					}

					_appendToContent(sb, objectField.getName(), contentValue);
				}

				String defaultContentValue = localizedContentValues.get(
					defaultLanguageId);

				if (defaultContentValue == null) {
					for (String value : localizedContentValues.values()) {
						if (value != null) {
							defaultContentValue = value;

							break;
						}
					}
				}

				for (Map.Entry<String, StringBundler> entry :
						contentStringBundlers.entrySet()) {

					String languageId = entry.getKey();

					String contentValue = localizedContentValues.get(
						languageId);

					if (contentValue == null) {
						contentValue = defaultContentValue;
					}

					if (contentValue == null) {
						continue;
					}

					_appendToContent(
						entry.getValue(), objectField.getName(), contentValue);
				}

				continue;
			}

			String contentValue = _contribute(
				document, fieldArray, objectField.getName(),
				values.get(objectField.getName()), null, objectDefinition,
				objectEntry, objectField, values);

			if (contentValue == null) {
				continue;
			}

			_appendToContent(sb, objectField.getName(), contentValue);

			for (StringBundler contentSB : contentStringBundlers.values()) {
				_appendToContent(
					contentSB, objectField.getName(), contentValue);
			}
		}

		document.add(new Field("objectEntryContent", sb.toString()));

		Map<String, String> objectEntryContents = new TreeMap<>();

		for (Map.Entry<String, StringBundler> entry :
				contentStringBundlers.entrySet()) {

			StringBundler contentSB = entry.getValue();

			if (contentSB.index() > 0) {
				contentSB.setIndex(contentSB.index() - 1);
			}

			objectEntryContents.put(entry.getKey(), contentSB.toString());
		}

		for (Map.Entry<String, String> entry : objectEntryContents.entrySet()) {
			document.add(
				new Field(
					"objectEntryContent_" + entry.getKey(), entry.getValue()));
		}

		document.addKeyword("objectEntryId", objectEntry.getObjectEntryId());
		document.add(
			new Field("objectEntryTitle", objectEntry.getTitleValue()));

		ObjectFolder objectFolder = _objectFolderLocalService.getObjectFolder(
			objectDefinition.getObjectFolderId());

		document.addKeyword(
			"objectFolderExternalReferenceCode",
			objectFolder.getExternalReferenceCode(), true);

		if (FeatureFlagManagerUtil.isEnabled(
				objectEntry.getCompanyId(), "LPD-17564")) {

			_contributeObjectEntryFolder(
				document, objectEntry.getObjectEntryFolderId());
		}
	}

	private void _contributeObjectEntryFolder(
		Document document, long objectEntryFolderId) {

		document.addKeyword(Field.FOLDER_ID, objectEntryFolderId);

		ObjectEntryFolder objectEntryFolder =
			_objectEntryFolderLocalService.fetchObjectEntryFolder(
				objectEntryFolderId);

		if (objectEntryFolder == null) {
			return;
		}

		ObjectEntryFolder rootObjectEntryFolder = _getRootObjectEntryFolder(
			objectEntryFolder);

		if (rootObjectEntryFolder == null) {
			return;
		}

		String cmsSection = _getCMSSection(
			rootObjectEntryFolder.getExternalReferenceCode());

		if (cmsSection == null) {
			return;
		}

		document.addKeyword("cms_kind", "object");
		document.addKeyword(
			"cms_root",
			rootObjectEntryFolder.getObjectEntryFolderId() ==
				objectEntryFolderId);
		document.addKeyword("cms_section", cmsSection);
	}

	private String _getCMSSection(String externalReferenceCode) {
		if (externalReferenceCode.equals(
				ObjectEntryFolderConstants.EXTERNAL_REFERENCE_CODE_CONTENTS)) {

			return "contents";
		}

		if (externalReferenceCode.equals(
				ObjectEntryFolderConstants.EXTERNAL_REFERENCE_CODE_FILES)) {

			return "files";
		}

		return null;
	}

	private String _getDateString(Object value) {
		return _format.format(value);
	}

	private ObjectEntryFolder _getRootObjectEntryFolder(
		ObjectEntryFolder objectEntryFolder) {

		if (objectEntryFolder == null) {
			return null;
		}

		if (Objects.equals(
				objectEntryFolder.getExternalReferenceCode(),
				ObjectEntryFolderConstants.EXTERNAL_REFERENCE_CODE_CONTENTS) ||
			Objects.equals(
				objectEntryFolder.getExternalReferenceCode(),
				ObjectEntryFolderConstants.EXTERNAL_REFERENCE_CODE_FILES)) {

			return objectEntryFolder;
		}

		String[] parts = StringUtil.split(
			objectEntryFolder.getTreePath(), CharPool.SLASH);

		if (parts.length <= 2) {
			return null;
		}

		return _objectEntryFolderLocalService.fetchObjectEntryFolder(
			GetterUtil.getLong(parts[1]));
	}

	private String _getSortableValue(String value) {
		if (value.length() > 256) {
			return value.substring(0, 256);
		}

		return value;
	}

	private String _translate(Boolean value) {
		if (value.booleanValue()) {
			return "yes";
		}

		return "no";
	}

	private static final Log _log = LogFactoryUtil.getLog(
		ObjectEntryModelDocumentContributor.class);

	private static final Format _format =
		FastDateFormatFactoryUtil.getSimpleDateFormat("yyyyMMddHHmmss");

	private final AccountEntryOrganizationRelLocalService
		_accountEntryOrganizationRelLocalService;
	private final String _className;
	private final ObjectDefinitionLocalService _objectDefinitionLocalService;
	private final ObjectEntryFolderLocalService _objectEntryFolderLocalService;
	private final ObjectEntryLocalService _objectEntryLocalService;
	private final ObjectFieldLocalService _objectFieldLocalService;
	private final ObjectFolderLocalService _objectFolderLocalService;

}