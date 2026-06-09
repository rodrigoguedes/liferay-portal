/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.rest.client.serdes.v1_0;

import com.liferay.portal.search.rest.client.dto.v1_0.InferenceServiceField;
import com.liferay.portal.search.rest.client.json.BaseJSONParser;

import jakarta.annotation.Generated;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * @author Petteri Karttunen
 * @generated
 */
@Generated("")
public class InferenceServiceFieldSerDes {

	public static InferenceServiceField toDTO(String json) {
		InferenceServiceFieldJSONParser inferenceServiceFieldJSONParser =
			new InferenceServiceFieldJSONParser();

		return inferenceServiceFieldJSONParser.parseToDTO(json);
	}

	public static InferenceServiceField[] toDTOs(String json) {
		InferenceServiceFieldJSONParser inferenceServiceFieldJSONParser =
			new InferenceServiceFieldJSONParser();

		return inferenceServiceFieldJSONParser.parseToDTOs(json);
	}

	public static String toJSON(InferenceServiceField inferenceServiceField) {
		if (inferenceServiceField == null) {
			return "null";
		}

		StringBuilder sb = new StringBuilder();

		sb.append("{");

		if (inferenceServiceField.getDescription() != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"description\": ");

			sb.append("\"");

			sb.append(_escape(inferenceServiceField.getDescription()));

			sb.append("\"");
		}

		if (inferenceServiceField.getKey() != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"key\": ");

			sb.append("\"");

			sb.append(_escape(inferenceServiceField.getKey()));

			sb.append("\"");
		}

		if (inferenceServiceField.getLabel() != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"label\": ");

			sb.append("\"");

			sb.append(_escape(inferenceServiceField.getLabel()));

			sb.append("\"");
		}

		if (inferenceServiceField.getRequired() != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"required\": ");

			sb.append(inferenceServiceField.getRequired());
		}

		if (inferenceServiceField.getSensitive() != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"sensitive\": ");

			sb.append(inferenceServiceField.getSensitive());
		}

		if (inferenceServiceField.getSupportedTaskTypes() != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"supportedTaskTypes\": ");

			sb.append("[");

			for (int i = 0;
				 i < inferenceServiceField.getSupportedTaskTypes().length;
				 i++) {

				sb.append(
					_toJSON(inferenceServiceField.getSupportedTaskTypes()[i]));

				if ((i + 1) <
						inferenceServiceField.getSupportedTaskTypes().length) {

					sb.append(", ");
				}
			}

			sb.append("]");
		}

		if (inferenceServiceField.getType() != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"type\": ");

			sb.append("\"");

			sb.append(_escape(inferenceServiceField.getType()));

			sb.append("\"");
		}

		sb.append("}");

		return sb.toString();
	}

	public static Map<String, Object> toMap(String json) {
		InferenceServiceFieldJSONParser inferenceServiceFieldJSONParser =
			new InferenceServiceFieldJSONParser();

		return inferenceServiceFieldJSONParser.parseToMap(json);
	}

	public static Map<String, String> toMap(
		InferenceServiceField inferenceServiceField) {

		if (inferenceServiceField == null) {
			return null;
		}

		Map<String, String> map = new TreeMap<>();

		if (inferenceServiceField.getDescription() == null) {
			map.put("description", null);
		}
		else {
			map.put(
				"description",
				String.valueOf(inferenceServiceField.getDescription()));
		}

		if (inferenceServiceField.getKey() == null) {
			map.put("key", null);
		}
		else {
			map.put("key", String.valueOf(inferenceServiceField.getKey()));
		}

		if (inferenceServiceField.getLabel() == null) {
			map.put("label", null);
		}
		else {
			map.put("label", String.valueOf(inferenceServiceField.getLabel()));
		}

		if (inferenceServiceField.getRequired() == null) {
			map.put("required", null);
		}
		else {
			map.put(
				"required",
				String.valueOf(inferenceServiceField.getRequired()));
		}

		if (inferenceServiceField.getSensitive() == null) {
			map.put("sensitive", null);
		}
		else {
			map.put(
				"sensitive",
				String.valueOf(inferenceServiceField.getSensitive()));
		}

		if (inferenceServiceField.getSupportedTaskTypes() == null) {
			map.put("supportedTaskTypes", null);
		}
		else {
			map.put(
				"supportedTaskTypes",
				String.valueOf(inferenceServiceField.getSupportedTaskTypes()));
		}

		if (inferenceServiceField.getType() == null) {
			map.put("type", null);
		}
		else {
			map.put("type", String.valueOf(inferenceServiceField.getType()));
		}

		return map;
	}

	public static class InferenceServiceFieldJSONParser
		extends BaseJSONParser<InferenceServiceField> {

		@Override
		protected InferenceServiceField createDTO() {
			return new InferenceServiceField();
		}

		@Override
		protected InferenceServiceField[] createDTOArray(int size) {
			return new InferenceServiceField[size];
		}

		@Override
		protected boolean parseMaps(String jsonParserFieldName) {
			if (Objects.equals(jsonParserFieldName, "description")) {
				return false;
			}
			else if (Objects.equals(jsonParserFieldName, "key")) {
				return false;
			}
			else if (Objects.equals(jsonParserFieldName, "label")) {
				return false;
			}
			else if (Objects.equals(jsonParserFieldName, "required")) {
				return false;
			}
			else if (Objects.equals(jsonParserFieldName, "sensitive")) {
				return false;
			}
			else if (Objects.equals(
						jsonParserFieldName, "supportedTaskTypes")) {

				return false;
			}
			else if (Objects.equals(jsonParserFieldName, "type")) {
				return false;
			}

			return false;
		}

		@Override
		protected void setField(
			InferenceServiceField inferenceServiceField,
			String jsonParserFieldName, Object jsonParserFieldValue) {

			if (Objects.equals(jsonParserFieldName, "description")) {
				if (jsonParserFieldValue != null) {
					inferenceServiceField.setDescription(
						(String)jsonParserFieldValue);
				}
			}
			else if (Objects.equals(jsonParserFieldName, "key")) {
				if (jsonParserFieldValue != null) {
					inferenceServiceField.setKey((String)jsonParserFieldValue);
				}
			}
			else if (Objects.equals(jsonParserFieldName, "label")) {
				if (jsonParserFieldValue != null) {
					inferenceServiceField.setLabel(
						(String)jsonParserFieldValue);
				}
			}
			else if (Objects.equals(jsonParserFieldName, "required")) {
				if (jsonParserFieldValue != null) {
					inferenceServiceField.setRequired(
						(Boolean)jsonParserFieldValue);
				}
			}
			else if (Objects.equals(jsonParserFieldName, "sensitive")) {
				if (jsonParserFieldValue != null) {
					inferenceServiceField.setSensitive(
						(Boolean)jsonParserFieldValue);
				}
			}
			else if (Objects.equals(
						jsonParserFieldName, "supportedTaskTypes")) {

				if (jsonParserFieldValue != null) {
					inferenceServiceField.setSupportedTaskTypes(
						toStrings((Object[])jsonParserFieldValue));
				}
			}
			else if (Objects.equals(jsonParserFieldName, "type")) {
				if (jsonParserFieldValue != null) {
					inferenceServiceField.setType((String)jsonParserFieldValue);
				}
			}
		}

	}

	private static String _escape(Object object) {
		String string = String.valueOf(object);

		for (String[] strings : BaseJSONParser.JSON_ESCAPE_STRINGS) {
			string = string.replace(strings[0], strings[1]);
		}

		return string;
	}

	private static String _toJSON(Map<String, ?> map) {
		StringBuilder sb = new StringBuilder("{");

		@SuppressWarnings("unchecked")
		Set set = map.entrySet();

		@SuppressWarnings("unchecked")
		Iterator<Map.Entry<String, ?>> iterator = set.iterator();

		while (iterator.hasNext()) {
			Map.Entry<String, ?> entry = iterator.next();

			sb.append("\"");
			sb.append(entry.getKey());
			sb.append("\": ");

			Object value = entry.getValue();

			sb.append(_toJSON(value));

			if (iterator.hasNext()) {
				sb.append(", ");
			}
		}

		sb.append("}");

		return sb.toString();
	}

	private static String _toJSON(Object value) {
		if (value == null) {
			return "null";
		}

		if (value instanceof Map) {
			return _toJSON((Map)value);
		}

		Class<?> clazz = value.getClass();

		if (clazz.isArray()) {
			StringBuilder sb = new StringBuilder("[");

			Object[] values = (Object[])value;

			for (int i = 0; i < values.length; i++) {
				sb.append(_toJSON(values[i]));

				if ((i + 1) < values.length) {
					sb.append(", ");
				}
			}

			sb.append("]");

			return sb.toString();
		}

		if (value instanceof String) {
			return "\"" + _escape(value) + "\"";
		}

		return String.valueOf(value);
	}

}
// LIFERAY-REST-BUILDER-HASH:1610014168