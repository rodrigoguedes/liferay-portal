/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.ml.embedding.text.helper;

import com.liferay.portal.test.rule.LiferayUnitTestRule;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * @author Rodrigo Guedes de Souza
 */
public class SemanticTextContentBuilderTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Test
	public void testBuildEmptyWhenNoValues() {
		SemanticTextContentBuilder semanticTextContentBuilder =
			new SemanticTextContentBuilder();

		Assert.assertEquals("", semanticTextContentBuilder.build());
	}

	@Test
	public void testBuildLabeledContent() {
		SemanticTextContentBuilder semanticTextContentBuilder =
			new SemanticTextContentBuilder();

		semanticTextContentBuilder.append(
			"Title", "Liferay Search"
		).append(
			"Content/Body", "Semantic search with inference endpoints"
		);

		Assert.assertEquals(
			"Title: Liferay Search\nContent/Body: Semantic search with " +
				"inference endpoints",
			semanticTextContentBuilder.build());
	}

	@Test
	public void testBuildOmitsBlankValues() {
		SemanticTextContentBuilder semanticTextContentBuilder =
			new SemanticTextContentBuilder();

		semanticTextContentBuilder.append(
			"Title", "Liferay Search"
		).append(
			"Tags", null
		).append(
			"Categorization", ""
		).append(
			"Content/Body", "Semantic search"
		);

		Assert.assertEquals(
			"Title: Liferay Search\nContent/Body: Semantic search",
			semanticTextContentBuilder.build());
	}

}