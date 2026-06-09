/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.query;

/**
 * Matches a {@code sparse_vector} field. When an {@code inferenceId} is set,
 * Elasticsearch expands the query text into weighted tokens server-side
 * (ELSER/E5 forward compatibility); otherwise the field's own inference
 * configuration is used.
 *
 * @author Rodrigo Guedes de Souza
 */
public class SparseVectorQuery extends Query {

	public SparseVectorQuery(String field, String query) {
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

	public String getInferenceId() {
		return _inferenceId;
	}

	public Boolean getPrune() {
		return _prune;
	}

	public String getQuery() {
		return _query;
	}

	public void setInferenceId(String inferenceId) {
		_inferenceId = inferenceId;
	}

	public void setPrune(Boolean prune) {
		_prune = prune;
	}

	private final String _field;
	private String _inferenceId;
	private Boolean _prune;
	private final String _query;

}