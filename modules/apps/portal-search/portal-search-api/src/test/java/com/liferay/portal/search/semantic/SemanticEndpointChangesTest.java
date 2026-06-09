/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

import com.liferay.portal.test.rule.LiferayUnitTestRule;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * @author Rodrigo Guedes de Souza
 */
public class SemanticEndpointChangesTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Test
	public void testClassifyBreakingWhenCandidateIsNull() {
		Assert.assertEquals(
			SemanticEndpointChangeType.BREAKING,
			SemanticEndpointChanges.classify(
				_inferenceEndpoint(
					"endpoint-a", "openai", "text-embedding-3-small", 1536,
					"cosine"),
				null));
	}

	@Test
	public void testClassifyBreakingWhenCurrentIsNull() {
		Assert.assertEquals(
			SemanticEndpointChangeType.BREAKING,
			SemanticEndpointChanges.classify(
				null,
				_inferenceEndpoint(
					"endpoint-a", "openai", "text-embedding-3-small", 1536,
					"cosine")));
	}

	@Test
	public void testClassifyBreakingWhenDimensionsDiffer() {
		Assert.assertEquals(
			SemanticEndpointChangeType.BREAKING,
			SemanticEndpointChanges.classify(
				_inferenceEndpoint(
					"endpoint-a", "openai", "text-embedding-3-large", 3072,
					"cosine"),
				_inferenceEndpoint(
					"endpoint-b", "openai", "text-embedding-3-large", 1536,
					"cosine")));
	}

	@Test
	public void testClassifyBreakingWhenModelIdDiffers() {
		Assert.assertEquals(
			SemanticEndpointChangeType.BREAKING,
			SemanticEndpointChanges.classify(
				_inferenceEndpoint(
					"endpoint-a", "openai", "text-embedding-3-small", 1536,
					"cosine"),
				_inferenceEndpoint(
					"endpoint-b", "openai", "text-embedding-3-large", 1536,
					"cosine")));
	}

	@Test
	public void testClassifyBreakingWhenServiceDiffers() {
		Assert.assertEquals(
			SemanticEndpointChangeType.BREAKING,
			SemanticEndpointChanges.classify(
				_inferenceEndpoint(
					"endpoint-a", "openai", "text-embedding-3-small", 1536,
					"cosine"),
				_inferenceEndpoint(
					"endpoint-b", "hugging_face", "text-embedding-3-small",
					1536, "cosine")));
	}

	@Test
	public void testClassifyBreakingWhenSimilarityDiffers() {
		Assert.assertEquals(
			SemanticEndpointChangeType.BREAKING,
			SemanticEndpointChanges.classify(
				_inferenceEndpoint(
					"endpoint-a", "openai", "text-embedding-3-small", 1536,
					"cosine"),
				_inferenceEndpoint(
					"endpoint-b", "openai", "text-embedding-3-small", 1536,
					"dot_product")));
	}

	@Test
	public void testClassifyEquivalentWhenOnlyServiceCaseDiffers() {
		Assert.assertEquals(
			SemanticEndpointChangeType.EQUIVALENT,
			SemanticEndpointChanges.classify(
				_inferenceEndpoint(
					"endpoint-a", "OpenAI", "text-embedding-3-small", 1536,
					"Cosine"),
				_inferenceEndpoint(
					"endpoint-b", "openai", "text-embedding-3-small", 1536,
					"cosine")));
	}

	@Test
	public void testClassifyEquivalentWhenRenamedWithSameMetadata() {
		Assert.assertEquals(
			SemanticEndpointChangeType.EQUIVALENT,
			SemanticEndpointChanges.classify(
				_inferenceEndpoint(
					"endpoint-a", "openai", "text-embedding-3-small", 1536,
					"cosine"),
				_inferenceEndpoint(
					"endpoint-b", "openai", "text-embedding-3-small", 1536,
					"cosine")));
	}

	private InferenceEndpoint _inferenceEndpoint(
		String inferenceId, String service, String modelId, int dimensions,
		String similarity) {

		return new InferenceEndpoint(
			inferenceId, "text_embedding", service, modelId, dimensions,
			similarity);
	}

}