/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.query;

import java.util.List;

/**
 * Runs an approximate k-nearest-neighbor search against a {@code dense_vector}
 * field. The query vector can be supplied directly ({@link #setQueryVector})
 * or, for server-side embedding, derived from {@link #setQueryText} through the
 * Inference Endpoint named by {@link #setInferenceId} (translated to a {@code
 * query_vector_builder.text_embedding}).
 *
 * @author Rodrigo Guedes de Souza
 */
public class KnnQuery extends Query {

	public KnnQuery(String field) {
		_field = field;
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

	public Integer getK() {
		return _k;
	}

	public Integer getNumCandidates() {
		return _numCandidates;
	}

	public String getQueryText() {
		return _queryText;
	}

	public List<Float> getQueryVector() {
		return _queryVector;
	}

	public Float getSimilarity() {
		return _similarity;
	}

	public void setInferenceId(String inferenceId) {
		_inferenceId = inferenceId;
	}

	public void setK(Integer k) {
		_k = k;
	}

	public void setNumCandidates(Integer numCandidates) {
		_numCandidates = numCandidates;
	}

	public void setQueryText(String queryText) {
		_queryText = queryText;
	}

	public void setQueryVector(List<Float> queryVector) {
		_queryVector = queryVector;
	}

	public void setSimilarity(Float similarity) {
		_similarity = similarity;
	}

	private final String _field;
	private String _inferenceId;
	private Integer _k;
	private Integer _numCandidates;
	private String _queryText;
	private List<Float> _queryVector;
	private Float _similarity;

}