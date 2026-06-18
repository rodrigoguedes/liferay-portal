/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.query;

import com.liferay.portal.search.elasticsearch8.internal.util.JsonpUtil;
import com.liferay.portal.search.query.KnnQuery;
import com.liferay.portal.search.query.Query;
import com.liferay.portal.search.query.SemanticQuery;
import com.liferay.portal.search.query.SparseVectorQuery;
import com.liferay.portal.test.rule.LiferayUnitTestRule;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that the inference-triggering query types translate to native
 * Elasticsearch JSON instead of being wrapped (and base64-encoded) as a
 * {@code query.wrapper}, which is what broke the discovery PoC against OpenAI.
 *
 * @author Rodrigo Guedes de Souza
 */
public class InferenceQueryTranslationTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Test
	public void testTranslateKnnQueryWithInferenceEndpoint() {
		KnnQuery knnQuery = new KnnQuery("content_en_US");

		knnQuery.setInferenceId("my-endpoint");
		knnQuery.setQueryText("hello world");

		String jsonp = _translate(knnQuery);

		Assert.assertTrue(jsonp, jsonp.contains("\"knn\""));
		Assert.assertTrue(jsonp, jsonp.contains("\"query_vector_builder\""));
		Assert.assertTrue(jsonp, jsonp.contains("\"text_embedding\""));
		Assert.assertTrue(
			jsonp, jsonp.contains("\"model_id\":\"my-endpoint\""));
		Assert.assertTrue(
			jsonp, jsonp.contains("\"model_text\":\"hello world\""));
	}

	@Test
	public void testTranslateKnnQueryWithQueryVector() {
		KnnQuery knnQuery = new KnnQuery("content_en_US");

		knnQuery.setK(5);
		knnQuery.setNumCandidates(50);
		knnQuery.setQueryVector(Arrays.asList(0.1F, 0.2F, 0.3F));

		String jsonp = _translate(knnQuery);

		Assert.assertTrue(jsonp, jsonp.contains("\"knn\""));
		Assert.assertTrue(jsonp, jsonp.contains("\"field\":\"content_en_US\""));
		Assert.assertTrue(jsonp, jsonp.contains("\"query_vector\""));
		Assert.assertTrue(jsonp, jsonp.contains("\"k\":5"));
		Assert.assertTrue(jsonp, jsonp.contains("\"num_candidates\":50"));
	}

	@Test
	public void testTranslateSemanticQuery() {
		String jsonp = _translate(
			new SemanticQuery("content_en_US_semantic", "hello world"));

		Assert.assertTrue(jsonp, jsonp.contains("\"semantic\""));
		Assert.assertTrue(
			jsonp, jsonp.contains("\"field\":\"content_en_US_semantic\""));
		Assert.assertTrue(jsonp, jsonp.contains("\"query\":\"hello world\""));
	}

	@Test
	public void testTranslateSemanticQueryIsNotWrapped() {
		String jsonp = _translate(
			new SemanticQuery("content_en_US_semantic", "hello world"));

		Assert.assertFalse(jsonp, jsonp.contains("\"wrapper\""));
	}

	@Test
	public void testTranslateSparseVectorQuery() {
		SparseVectorQuery sparseVectorQuery = new SparseVectorQuery(
			"content_en_US_sparse", "hello world");

		sparseVectorQuery.setInferenceId("my-endpoint");

		String jsonp = _translate(sparseVectorQuery);

		Assert.assertTrue(jsonp, jsonp.contains("\"sparse_vector\""));
		Assert.assertTrue(
			jsonp, jsonp.contains("\"field\":\"content_en_US_sparse\""));
		Assert.assertTrue(
			jsonp, jsonp.contains("\"inference_id\":\"my-endpoint\""));
		Assert.assertTrue(jsonp, jsonp.contains("\"query\":\"hello world\""));
	}

	private String _translate(Query query) {
		return JsonpUtil.toString(
			new co.elastic.clients.elasticsearch._types.query_dsl.Query(
				ElasticsearchQueryVisitor.INSTANCE.translate(query)));
	}

}