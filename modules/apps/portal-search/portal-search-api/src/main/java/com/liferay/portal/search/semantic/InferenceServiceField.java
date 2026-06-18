/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

import java.util.List;

/**
 * One configuration field a provider exposes, as described by the
 * Elasticsearch {@code _inference/_services} API, so the admin form can render
 * it dynamically. {@code sensitive} fields (such as an API key) should be
 * rendered as password inputs, and {@code supportedTaskTypes} can be narrower
 * than the service's task types (e.g. OpenAI's {@code dimensions} applies only
 * to {@code text_embedding}).
 *
 * @author Rodrigo Guedes de Souza
 */
public class InferenceServiceField {

	public InferenceServiceField(
		String key, String label, String description, boolean required,
		boolean sensitive, String type, List<String> supportedTaskTypes) {

		_key = key;
		_label = label;
		_description = description;
		_required = required;
		_sensitive = sensitive;
		_type = type;
		_supportedTaskTypes = supportedTaskTypes;
	}

	public String getDescription() {
		return _description;
	}

	public String getKey() {
		return _key;
	}

	public String getLabel() {
		return _label;
	}

	public List<String> getSupportedTaskTypes() {
		return _supportedTaskTypes;
	}

	public String getType() {
		return _type;
	}

	public boolean isRequired() {
		return _required;
	}

	public boolean isSensitive() {
		return _sensitive;
	}

	private final String _description;
	private final String _key;
	private final String _label;
	private final boolean _required;
	private final boolean _sensitive;
	private final List<String> _supportedTaskTypes;
	private final String _type;

}