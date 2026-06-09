/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.rest.dto.v1_0;

import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.liferay.petra.function.UnsafeSupplier;
import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.vulcan.graphql.annotation.GraphQLField;
import com.liferay.portal.vulcan.graphql.annotation.GraphQLName;
import com.liferay.portal.vulcan.util.ObjectMapperUtil;

import jakarta.annotation.Generated;

import jakarta.validation.Valid;

import jakarta.xml.bind.annotation.XmlRootElement;

import java.io.Serializable;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * @author Petteri Karttunen
 * @generated
 */
@Generated("")
@GraphQLName("InferenceService")
@JsonFilter("Liferay.Vulcan")
@XmlRootElement(name = "InferenceService")
public class InferenceService implements Serializable {

	public static InferenceService toDTO(String json) {
		return ObjectMapperUtil.readValue(InferenceService.class, json);
	}

	public static InferenceService unsafeToDTO(String json) {
		return ObjectMapperUtil.unsafeReadValue(InferenceService.class, json);
	}

	@io.swagger.v3.oas.annotations.media.Schema
	@Valid
	public InferenceServiceField[] getInferenceServiceFields() {
		if (_inferenceServiceFieldsSupplier != null) {
			inferenceServiceFields = _inferenceServiceFieldsSupplier.get();

			_inferenceServiceFieldsSupplier = null;
		}

		return inferenceServiceFields;
	}

	public void setInferenceServiceFields(
		InferenceServiceField[] inferenceServiceFields) {

		this.inferenceServiceFields = inferenceServiceFields;

		_inferenceServiceFieldsSupplier = null;
	}

	@JsonIgnore
	public void setInferenceServiceFields(
		UnsafeSupplier<InferenceServiceField[], Exception>
			inferenceServiceFieldsUnsafeSupplier) {

		_inferenceServiceFieldsSupplier = () -> {
			try {
				return inferenceServiceFieldsUnsafeSupplier.get();
			}
			catch (RuntimeException runtimeException) {
				throw runtimeException;
			}
			catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		};
	}

	@GraphQLField
	@JsonProperty(access = JsonProperty.Access.READ_WRITE)
	protected InferenceServiceField[] inferenceServiceFields;

	@JsonIgnore
	private Supplier<InferenceServiceField[]> _inferenceServiceFieldsSupplier;

	@io.swagger.v3.oas.annotations.media.Schema
	public String getName() {
		if (_nameSupplier != null) {
			name = _nameSupplier.get();

			_nameSupplier = null;
		}

		return name;
	}

	public void setName(String name) {
		this.name = name;

		_nameSupplier = null;
	}

	@JsonIgnore
	public void setName(UnsafeSupplier<String, Exception> nameUnsafeSupplier) {
		_nameSupplier = () -> {
			try {
				return nameUnsafeSupplier.get();
			}
			catch (RuntimeException runtimeException) {
				throw runtimeException;
			}
			catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		};
	}

	@GraphQLField
	@JsonProperty(access = JsonProperty.Access.READ_WRITE)
	protected String name;

	@JsonIgnore
	private Supplier<String> _nameSupplier;

	@io.swagger.v3.oas.annotations.media.Schema
	public String getService() {
		if (_serviceSupplier != null) {
			service = _serviceSupplier.get();

			_serviceSupplier = null;
		}

		return service;
	}

	public void setService(String service) {
		this.service = service;

		_serviceSupplier = null;
	}

	@JsonIgnore
	public void setService(
		UnsafeSupplier<String, Exception> serviceUnsafeSupplier) {

		_serviceSupplier = () -> {
			try {
				return serviceUnsafeSupplier.get();
			}
			catch (RuntimeException runtimeException) {
				throw runtimeException;
			}
			catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		};
	}

	@GraphQLField
	@JsonProperty(access = JsonProperty.Access.READ_WRITE)
	protected String service;

	@JsonIgnore
	private Supplier<String> _serviceSupplier;

	@io.swagger.v3.oas.annotations.media.Schema
	public String[] getTaskTypes() {
		if (_taskTypesSupplier != null) {
			taskTypes = _taskTypesSupplier.get();

			_taskTypesSupplier = null;
		}

		return taskTypes;
	}

	public void setTaskTypes(String[] taskTypes) {
		this.taskTypes = taskTypes;

		_taskTypesSupplier = null;
	}

	@JsonIgnore
	public void setTaskTypes(
		UnsafeSupplier<String[], Exception> taskTypesUnsafeSupplier) {

		_taskTypesSupplier = () -> {
			try {
				return taskTypesUnsafeSupplier.get();
			}
			catch (RuntimeException runtimeException) {
				throw runtimeException;
			}
			catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		};
	}

	@GraphQLField
	@JsonProperty(access = JsonProperty.Access.READ_WRITE)
	protected String[] taskTypes;

	@JsonIgnore
	private Supplier<String[]> _taskTypesSupplier;

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}

		if (!(object instanceof InferenceService)) {
			return false;
		}

		InferenceService inferenceService = (InferenceService)object;

		return Objects.equals(toString(), inferenceService.toString());
	}

	@Override
	public int hashCode() {
		String string = toString();

		return string.hashCode();
	}

	public String toString() {
		StringBundler sb = new StringBundler();

		sb.append("{");

		InferenceServiceField[] inferenceServiceFields =
			getInferenceServiceFields();

		if (inferenceServiceFields != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"inferenceServiceFields\": ");

			sb.append("[");

			for (int i = 0; i < inferenceServiceFields.length; i++) {
				sb.append(String.valueOf(inferenceServiceFields[i]));

				if ((i + 1) < inferenceServiceFields.length) {
					sb.append(", ");
				}
			}

			sb.append("]");
		}

		String name = getName();

		if (name != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"name\": ");

			sb.append("\"");

			sb.append(_escape(name));

			sb.append("\"");
		}

		String service = getService();

		if (service != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"service\": ");

			sb.append("\"");

			sb.append(_escape(service));

			sb.append("\"");
		}

		String[] taskTypes = getTaskTypes();

		if (taskTypes != null) {
			if (sb.length() > 1) {
				sb.append(", ");
			}

			sb.append("\"taskTypes\": ");

			sb.append("[");

			for (int i = 0; i < taskTypes.length; i++) {
				sb.append("\"");

				sb.append(_escape(taskTypes[i]));

				sb.append("\"");

				if ((i + 1) < taskTypes.length) {
					sb.append(", ");
				}
			}

			sb.append("]");
		}

		sb.append("}");

		return sb.toString();
	}

	@io.swagger.v3.oas.annotations.media.Schema(
		accessMode = io.swagger.v3.oas.annotations.media.Schema.AccessMode.READ_ONLY,
		defaultValue = "com.liferay.portal.search.rest.dto.v1_0.InferenceService",
		name = "x-class-name"
	)
	public String xClassName;

	private static String _escape(Object object) {
		return StringUtil.replace(
			String.valueOf(object), _JSON_ESCAPE_STRINGS[0],
			_JSON_ESCAPE_STRINGS[1]);
	}

	private static boolean _isArray(Object value) {
		if (value == null) {
			return false;
		}

		Class<?> clazz = value.getClass();

		return clazz.isArray();
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
			sb.append(_escape(entry.getKey()));
			sb.append("\": ");

			Object value = entry.getValue();

			if (_isArray(value)) {
				sb.append("[");

				Object[] valueArray = (Object[])value;

				for (int i = 0; i < valueArray.length; i++) {
					if (valueArray[i] instanceof Map) {
						sb.append(_toJSON((Map<String, ?>)valueArray[i]));
					}
					else if (valueArray[i] instanceof String) {
						sb.append("\"");
						sb.append(valueArray[i]);
						sb.append("\"");
					}
					else {
						sb.append(valueArray[i]);
					}

					if ((i + 1) < valueArray.length) {
						sb.append(", ");
					}
				}

				sb.append("]");
			}
			else if (value instanceof Map) {
				sb.append(_toJSON((Map<String, ?>)value));
			}
			else if (value instanceof String) {
				sb.append("\"");
				sb.append(_escape(value));
				sb.append("\"");
			}
			else {
				sb.append(value);
			}

			if (iterator.hasNext()) {
				sb.append(", ");
			}
		}

		sb.append("}");

		return sb.toString();
	}

	private static final String[][] _JSON_ESCAPE_STRINGS = {
		{"\\", "\"", "\b", "\f", "\n", "\r", "\t"},
		{"\\\\", "\\\"", "\\b", "\\f", "\\n", "\\r", "\\t"}
	};

	private Map<String, Serializable> _extendedProperties;

}
// LIFERAY-REST-BUILDER-HASH:728746939