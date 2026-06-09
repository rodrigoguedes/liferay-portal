/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.rest.client.serdes.v1_0;

import com.liferay.portal.search.rest.client.dto.v1_0.InferenceEndpointConfiguration;
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
public class InferenceEndpointConfigurationSerDes {

	public static InferenceEndpointConfiguration toDTO(String json) {
		InferenceEndpointConfigurationJSONParser
			inferenceEndpointConfigurationJSONParser =
				new InferenceEndpointConfigurationJSONParser();

		return inferenceEndpointConfigurationJSONParser.parseToDTO(json);
	}

	public static InferenceEndpointConfiguration[] toDTOs(String json) {
		InferenceEndpointConfigurationJSONParser
			inferenceEndpointConfigurationJSONParser =
				new InferenceEndpointConfigurationJSONParser();

		return inferenceEndpointConfigurationJSONParser.parseToDTOs(json);
	}

	public static String toJSON(
		InferenceEndpointConfiguration inferenceEndpointConfiguration) {

		if (inferenceEndpointConfiguration == null) {
			return "null";
		}

		StringBuilder sb = new StringBuilder();

		sb.append("{");

		if (inferenceEndpointConfiguration.getInferenceId() != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"inferenceId\": ");

			sb.append("\"");

			sb.append(_escape(inferenceEndpointConfiguration.getInferenceId()));

			sb.append("\"");
		}

		if (inferenceEndpointConfiguration.getService() != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"service\": ");

			sb.append("\"");

			sb.append(_escape(inferenceEndpointConfiguration.getService()));

			sb.append("\"");
		}

		if (inferenceEndpointConfiguration.getServiceSettings() != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"serviceSettings\": ");

			if (inferenceEndpointConfiguration.getServiceSettings() instanceof
					String) {

				sb.append("\"");
				sb.append(
					(String)
						inferenceEndpointConfiguration.getServiceSettings());
				sb.append("\"");
			}
			else {
				sb.append(inferenceEndpointConfiguration.getServiceSettings());
			}
		}

		sb.append("}");

		return sb.toString();
	}

	public static Map<String, Object> toMap(String json) {
		InferenceEndpointConfigurationJSONParser
			inferenceEndpointConfigurationJSONParser =
				new InferenceEndpointConfigurationJSONParser();

		return inferenceEndpointConfigurationJSONParser.parseToMap(json);
	}

	public static Map<String, String> toMap(
		InferenceEndpointConfiguration inferenceEndpointConfiguration) {

		if (inferenceEndpointConfiguration == null) {
			return null;
		}

		Map<String, String> map = new TreeMap<>();

		if (inferenceEndpointConfiguration.getInferenceId() == null) {
			map.put("inferenceId", null);
		}
		else {
			map.put(
				"inferenceId",
				String.valueOf(
					inferenceEndpointConfiguration.getInferenceId()));
		}

		if (inferenceEndpointConfiguration.getService() == null) {
			map.put("service", null);
		}
		else {
			map.put(
				"service",
				String.valueOf(inferenceEndpointConfiguration.getService()));
		}

		if (inferenceEndpointConfiguration.getServiceSettings() == null) {
			map.put("serviceSettings", null);
		}
		else {
			map.put(
				"serviceSettings",
				String.valueOf(
					inferenceEndpointConfiguration.getServiceSettings()));
		}

		return map;
	}

	public static class InferenceEndpointConfigurationJSONParser
		extends BaseJSONParser<InferenceEndpointConfiguration> {

		@Override
		protected InferenceEndpointConfiguration createDTO() {
			return new InferenceEndpointConfiguration();
		}

		@Override
		protected InferenceEndpointConfiguration[] createDTOArray(int size) {
			return new InferenceEndpointConfiguration[size];
		}

		@Override
		protected boolean parseMaps(String jsonParserFieldName) {
			if (Objects.equals(jsonParserFieldName, "inferenceId")) {
				return false;
			}
			else if (Objects.equals(jsonParserFieldName, "service")) {
				return false;
			}
			else if (Objects.equals(jsonParserFieldName, "serviceSettings")) {
				return false;
			}

			return false;
		}

		@Override
		protected void setField(
			InferenceEndpointConfiguration inferenceEndpointConfiguration,
			String jsonParserFieldName, Object jsonParserFieldValue) {

			if (Objects.equals(jsonParserFieldName, "inferenceId")) {
				if (jsonParserFieldValue != null) {
					inferenceEndpointConfiguration.setInferenceId(
						(String)jsonParserFieldValue);
				}
			}
			else if (Objects.equals(jsonParserFieldName, "service")) {
				if (jsonParserFieldValue != null) {
					inferenceEndpointConfiguration.setService(
						(String)jsonParserFieldValue);
				}
			}
			else if (Objects.equals(jsonParserFieldName, "serviceSettings")) {
				if (jsonParserFieldValue != null) {
					inferenceEndpointConfiguration.setServiceSettings(
						(Object)jsonParserFieldValue);
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
// LIFERAY-REST-BUILDER-HASH:-1778772681