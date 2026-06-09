/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

import java.util.List;

/**
 * A provider that Elasticsearch can create an Inference Endpoint for, as
 * described by the {@code _inference/_services} API: its {@code service}
 * identifier (e.g. {@code openai}, {@code googlevertexai}), human-readable
 * {@code name}, the {@code taskTypes} it supports, and the {@code fields} the
 * admin form must collect to create the endpoint. The Semantic Search creation
 * form is rendered dynamically from this, rather than from a hardcoded provider
 * catalog.
 *
 * @author Rodrigo Guedes de Souza
 */
public class InferenceService {

	public InferenceService(
		String service, String name, List<String> taskTypes,
		List<InferenceServiceField> fields) {

		_service = service;
		_name = name;
		_taskTypes = taskTypes;
		_fields = fields;
	}

	public List<InferenceServiceField> getFields() {
		return _fields;
	}

	public String getName() {
		return _name;
	}

	public String getService() {
		return _service;
	}

	public List<String> getTaskTypes() {
		return _taskTypes;
	}

	private final List<InferenceServiceField> _fields;
	private final String _name;
	private final String _service;
	private final List<String> _taskTypes;

}