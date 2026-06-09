/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.query;

/**
 * Matches a {@code semantic_text} field by handing the raw query text to
 * Elasticsearch, which embeds it server-side using the field's Inference
 * Endpoint. No client-side embedding is performed.
 *
 * @author Rodrigo Guedes de Souza
 */
public class SemanticQuery extends Query {

	public SemanticQuery(String field, String query) {
		_field = field;
		_query = query;
	}

	@Override
	public <T> T accept(QueryVisitor<T> queryVisitor) {
		return queryVisitor.visit(this);
	}

	public String getField() {
		return _field;
	}

	public String getQuery() {
		return _query;
	}

	private final String _field;
	private final String _query;

}