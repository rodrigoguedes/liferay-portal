/**
 * SPDX-FileCopyrightText: (c) 2024 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.search.experiences.internal.blueprint.parameter.contributor;

import com.liferay.petra.function.UnsafePredicate;
import com.liferay.portal.kernel.language.Language;
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.test.util.RandomTestUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.search.configuration.SemanticSearchConfiguration;
import com.liferay.portal.search.configuration.SemanticSearchConfigurationProvider;
import com.liferay.portal.search.ml.embedding.text.TextEmbeddingRetriever;
import com.liferay.portal.search.rest.dto.v1_0.EmbeddingProviderConfiguration;
import com.liferay.portal.test.rule.LiferayUnitTestRule;
import com.liferay.search.experiences.blueprint.parameter.SXPParameter;

import java.beans.ExceptionListener;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.Mockito;

/**
 * @author Rodrigo Guedes de Souza
 */
public class MLSXPParameterContributorTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@BeforeClass
	public static void setUpClass() {
		Mockito.doReturn(
			"en_US"
		).when(
			_language
		).getLanguageId(
			Mockito.any(Locale.class)
		);
	}

	@Test
	public void testContributeWithInferenceEndpoint() throws Exception {
		_searchContext.setCompanyId(RandomTestUtil.randomLong());
		_searchContext.setEntryClassNames(
			new String[] {"com.liferay.blogs.model.BlogsEntry"});
		_searchContext.setKeywords(RandomTestUtil.randomString());
		_searchContext.setLocale(LocaleUtil.US);

		_setUpSemanticSearchConfigurationProvider("inference-endpoint", 0);

		_contribute();

		Assert.assertTrue(
			_exists(
				"ml.text_embeddings.semantic_field_name",
				value -> value.equals("blogsentry_en_US_semantic")));

		Assert.assertFalse(_contains("ml.text_embeddings.keywords_embedding"));
		Assert.assertFalse(_contains("ml.text_embeddings.vector_dimensions"));

		Mockito.verify(
			_textEmbeddingRetriever, Mockito.never()
		).getTextEmbedding(
			Mockito.anyString(), Mockito.anyString()
		);
	}

	@Test
	public void testContributeWithLiferayProvider() throws Exception {
		_searchContext.setCompanyId(RandomTestUtil.randomLong());
		_searchContext.setKeywords(RandomTestUtil.randomString());
		_searchContext.setLocale(LocaleUtil.US);

		Mockito.when(
			_textEmbeddingRetriever.getTextEmbedding(
				Mockito.anyString(), Mockito.anyString())
		).thenReturn(
			new Double[768]
		);

		_setUpSemanticSearchConfigurationProvider("hugging-face", 768);

		_contribute();

		Assert.assertTrue(_contains("ml.text_embeddings.keywords_embedding"));

		Assert.assertTrue(
			_exists(
				"ml.text_embeddings.vector_dimensions",
				value -> (int)value == 768));

		Assert.assertTrue(
			_exists(
				"ml.text_embeddings.semantic_field_name",
				value -> value.equals("text_embedding_768_en_US")));
	}

	private boolean _contains(String name) {
		for (SXPParameter sxpParameter : _sxpParameters) {
			if (name.equals(sxpParameter.getName())) {
				return true;
			}
		}

		return false;
	}

	private void _contribute() {
		MLSXPParameterContributor mlSXPParameterContributor =
			new MLSXPParameterContributor(
				_language, _semanticSearchConfigurationProvider,
				_textEmbeddingRetriever);

		mlSXPParameterContributor.contribute(
			Mockito.mock(ExceptionListener.class), _searchContext,
			_sxpParameters);
	}

	private SemanticSearchConfiguration _createSemanticSearchConfiguration(
		String embeddingProviderName, int vectorDimensions) {

		SemanticSearchConfiguration semanticSearchConfiguration = Mockito.mock(
			SemanticSearchConfiguration.class);

		Mockito.when(
			semanticSearchConfiguration.textEmbeddingsEnabled()
		).thenReturn(
			true
		);

		Mockito.when(
			semanticSearchConfiguration.
				textEmbeddingProviderConfigurationJSONs()
		).thenReturn(
			new String[] {
				new EmbeddingProviderConfiguration(
				) {

					{
						setEmbeddingVectorDimensions(vectorDimensions);
						setProviderName(embeddingProviderName);
					}
				}.toString()
			}
		);

		return semanticSearchConfiguration;
	}

	private boolean _exists(
			String name, UnsafePredicate<Object, Exception> unsafePredicate)
		throws Exception {

		for (SXPParameter sxpParameter : _sxpParameters) {
			if (name.equals(sxpParameter.getName()) &&
				unsafePredicate.test(sxpParameter.getValue())) {

				return true;
			}
		}

		return false;
	}

	private void _setUpSemanticSearchConfigurationProvider(
		String embeddingProviderName, int vectorDimensions) {

		SemanticSearchConfiguration semanticSearchConfiguration =
			_createSemanticSearchConfiguration(
				embeddingProviderName, vectorDimensions);

		Mockito.when(
			_semanticSearchConfigurationProvider.getCompanyConfiguration(
				Mockito.anyLong())
		).thenReturn(
			semanticSearchConfiguration
		);
	}

	private static final Language _language = Mockito.mock(Language.class);

	private final SearchContext _searchContext = new SearchContext();
	private final SemanticSearchConfigurationProvider
		_semanticSearchConfigurationProvider = Mockito.mock(
			SemanticSearchConfigurationProvider.class);
	private final Set<SXPParameter> _sxpParameters = new HashSet<>();
	private final TextEmbeddingRetriever _textEmbeddingRetriever = Mockito.mock(
		TextEmbeddingRetriever.class);

}