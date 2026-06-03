/**
 * SPDX-FileCopyrightText: (c) 2024 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.ml.embedding.text;

import com.liferay.blogs.model.BlogsEntry;
import com.liferay.document.library.kernel.model.DLFileEntry;
import com.liferay.object.model.ObjectEntry;
import com.liferay.portal.kernel.feature.flag.FeatureFlagManagerUtil;
import com.liferay.portal.kernel.language.Language;
import com.liferay.portal.kernel.language.LanguageUtil;
import com.liferay.portal.kernel.search.Document;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.test.ReflectionTestUtil;
import com.liferay.portal.kernel.test.util.RandomTestUtil;
import com.liferay.portal.kernel.util.HashMapBuilder;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.SetUtil;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.search.capabilities.ExternalEmbeddingCapabilityGate;
import com.liferay.portal.search.capabilities.ExternalEmbeddingEligibility;
import com.liferay.portal.search.configuration.SemanticSearchConfiguration;
import com.liferay.portal.search.configuration.SemanticSearchConfigurationProvider;
import com.liferay.portal.search.engine.SearchEngineInformation;
import com.liferay.portal.search.internal.semantic.SemanticFieldNamesImpl;
import com.liferay.portal.search.ml.embedding.text.TextEmbeddingRetriever;
import com.liferay.portal.search.rest.dto.v1_0.EmbeddingProviderConfiguration;
import com.liferay.portal.test.rule.LiferayUnitTestRule;

import java.util.Locale;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * @author Petteri Karttunen
 */
public class TextEmbeddingDocumentContributorImplTest {

	@ClassRule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Before
	public void setUp() throws Exception {
		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {BlogsEntry.class.getName()}, true);
		_setUpTextEmbeddingDocumentContributorImpl();
	}

	@Test
	public void testContribute() throws Exception {
		Document document = Mockito.mock(Document.class);

		_textEmbeddingDocumentContributorImpl.contribute(
			document, _getBlogsEntry(), RandomTestUtil.randomString());

		Mockito.verify(
			document
		).add(
			Mockito.any()
		);
	}

	@Test
	public void testContributeWithBYOLLMProvider() throws Exception {
		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {BlogsEntry.class.getName()}, _BYO_LLM_PROVIDER_NAME,
			true);

		Document document = Mockito.mock(Document.class);

		_addDocumentField(
			document, "title_en_US", "How to configure the firewall");
		_addDocumentField(document, "content", "Open the admin panel");
		_addDocumentField(
			document, "assetCategoryTitles_en_US", "Networking", "Security");
		_addDocumentField(
			document, "assetTagNames", "firewall", "security", "configuration");

		_contributeWithBYOLLMProvider(
			document, null, RandomTestUtil.randomString());

		Mockito.verify(
			_textEmbeddingRetriever, Mockito.never()
		).getTextEmbedding(
			Mockito.anyString(), Mockito.anyString()
		);

		Mockito.verify(
			document
		).addText(
			"blogs_entry_en_US_semantic",
			"Title: How to configure the firewall\nContent/Body: Open the " +
				"admin panel\nCategorization: Networking, Security\nTags: " +
					"firewall, security, configuration"
		);
	}

	@Test
	public void testContributeWithBYOLLMProviderWithLanguageId()
		throws Exception {

		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {BlogsEntry.class.getName()}, _BYO_LLM_PROVIDER_NAME,
			true);

		Document document = Mockito.mock(Document.class);

		_addDocumentField(
			document, "title_en_US", "How to configure the firewall");
		_addDocumentField(document, "content", "Open the admin panel");

		_contributeWithBYOLLMProvider(
			document, LocaleUtil.toLanguageId(LocaleUtil.US),
			RandomTestUtil.randomString());

		Mockito.verify(
			_textEmbeddingRetriever, Mockito.never()
		).getTextEmbedding(
			Mockito.anyString(), Mockito.anyString()
		);

		Mockito.verify(
			document
		).addText(
			"blogs_entry_en_US_semantic",
			"Title: How to configure the firewall\nContent/Body: Open the " +
				"admin panel"
		);
	}

	@Test
	public void testContributeWithBYOLLMProviderWithMultipleLocales()
		throws Exception {

		_setSemanticSearchConfiguration(
			new String[] {
				LocaleUtil.toLanguageId(LocaleUtil.GERMAN),
				LocaleUtil.toLanguageId(LocaleUtil.US)
			},
			new String[] {BlogsEntry.class.getName()}, _BYO_LLM_PROVIDER_NAME,
			true);

		Document document = Mockito.mock(Document.class);

		_addDocumentField(document, "title_de", "Konfigurieren der Firewall");
		_addDocumentField(
			document, "title_en_US", "How to configure the firewall");

		_contributeWithBYOLLMProvider(
			document, null, RandomTestUtil.randomString());

		Mockito.verify(
			document
		).addText(
			"blogs_entry_de_semantic", "Title: Konfigurieren der Firewall"
		);

		Mockito.verify(
			document
		).addText(
			"blogs_entry_en_US_semantic", "Title: How to configure the firewall"
		);
	}

	@Test
	public void testContributeWithBYOLLMProviderWithoutCapability()
		throws Exception {

		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {BlogsEntry.class.getName()}, _BYO_LLM_PROVIDER_NAME,
			true);

		Mockito.when(
			_externalEmbeddingCapabilityGate.check()
		).thenReturn(
			ExternalEmbeddingEligibility.unavailable(
				RandomTestUtil.randomString())
		);

		Document document = Mockito.mock(Document.class);

		try (MockedStatic<FeatureFlagManagerUtil>
				featureFlagManagerUtilMockedStatic = Mockito.mockStatic(
					FeatureFlagManagerUtil.class)) {

			featureFlagManagerUtilMockedStatic.when(
				() -> FeatureFlagManagerUtil.isEnabled(
					Mockito.anyLong(), Mockito.eq("LPD-11319"))
			).thenReturn(
				true
			);

			_textEmbeddingDocumentContributorImpl.contribute(
				document, _getBlogsEntry(), RandomTestUtil.randomString());
		}

		Mockito.verify(
			document
		).add(
			Mockito.any()
		);
	}

	@Test
	public void testContributeWithBYOLLMProviderWithoutFeatureFlag()
		throws Exception {

		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {BlogsEntry.class.getName()}, _BYO_LLM_PROVIDER_NAME,
			true);

		Document document = Mockito.mock(Document.class);

		try (MockedStatic<FeatureFlagManagerUtil>
				featureFlagManagerUtilMockedStatic = Mockito.mockStatic(
					FeatureFlagManagerUtil.class)) {

			featureFlagManagerUtilMockedStatic.when(
				() -> FeatureFlagManagerUtil.isEnabled(
					Mockito.anyLong(), Mockito.eq("LPD-11319"))
			).thenReturn(
				false
			);

			_textEmbeddingDocumentContributorImpl.contribute(
				document, _getBlogsEntry(), RandomTestUtil.randomString());
		}

		Mockito.verifyNoInteractions(_externalEmbeddingCapabilityGate);

		Mockito.verify(
			document
		).add(
			Mockito.any()
		);
	}

	@Test
	public void testContributeWithBYOLLMProviderWithoutFields()
		throws Exception {

		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {BlogsEntry.class.getName()}, _BYO_LLM_PROVIDER_NAME,
			true);

		Document document = Mockito.mock(Document.class);

		_contributeWithBYOLLMProvider(
			document, null, RandomTestUtil.randomString());

		Mockito.verify(
			document, Mockito.never()
		).addText(
			Mockito.anyString(), Mockito.anyString()
		);
	}

	@Test
	public void testContributeWithBYOLLMProviderWithoutTags() throws Exception {
		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {BlogsEntry.class.getName()}, _BYO_LLM_PROVIDER_NAME,
			true);

		Document document = Mockito.mock(Document.class);

		_addDocumentField(document, "title_en_US", "Q3 Revenue Report");
		_addDocumentField(
			document, "content_en_US", "Total revenue for Q3 was $42M");
		_addDocumentField(
			document, "assetCategoryTitles_en_US", "Finance", "Reports");

		_contributeWithBYOLLMProvider(
			document, null, RandomTestUtil.randomString());

		Mockito.verify(
			document
		).addText(
			"blogs_entry_en_US_semantic",
			"Title: Q3 Revenue Report\nContent/Body: Total revenue for Q3 " +
				"was $42M\nCategorization: Finance, Reports"
		);
	}

	@Test
	public void testContributeWithBYOLLMProviderWithSemanticFieldGroupsOverride()
		throws Exception {

		_textEmbeddingDocumentContributorImpl.activate(
			HashMapBuilder.<String, Object>put(
				"semantic.field.groups", _SEMANTIC_FIELD_GROUPS
			).put(
				"semantic.field.groups.blogs_entry", "Title:title"
			).build());

		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {BlogsEntry.class.getName()}, _BYO_LLM_PROVIDER_NAME,
			true);

		Document document = Mockito.mock(Document.class);

		_addDocumentField(
			document, "title_en_US", "How to configure the firewall");
		_addDocumentField(document, "assetTagNames", "firewall");

		_contributeWithBYOLLMProvider(
			document, null, RandomTestUtil.randomString());

		Mockito.verify(
			document
		).addText(
			"blogs_entry_en_US_semantic", "Title: How to configure the firewall"
		);
	}

	@Test
	public void testContributeWithLanguageId() throws Exception {
		Document document = Mockito.mock(Document.class);

		_textEmbeddingDocumentContributorImpl.contribute(
			document, LocaleUtil.toLanguageId(LocaleUtil.US), _getBlogsEntry(),
			RandomTestUtil.randomString());

		Mockito.verify(
			document
		).add(
			Mockito.any()
		);
	}

	@Test
	public void testContributeWithNotConfiguredLanguage() throws Exception {
		Document document = Mockito.mock(Document.class);

		_textEmbeddingDocumentContributorImpl.contribute(
			document, LocaleUtil.toLanguageId(LocaleUtil.FRENCH),
			_getBlogsEntry(), RandomTestUtil.randomString());

		Mockito.verifyNoInteractions(document);
	}

	@Test
	public void testGetEmbeddingProviderConfiguration() throws Exception {
		Assert.assertNotNull(
			_textEmbeddingDocumentContributorImpl.
				getEmbeddingProviderConfiguration(_getBlogsEntry()));
	}

	@Test
	public void testGetEmbeddingProviderConfigurationWithNotConfiguredModelClass()
		throws Exception {

		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {DLFileEntry.class.getName()}, false);

		Assert.assertNull(
			_textEmbeddingDocumentContributorImpl.
				getEmbeddingProviderConfiguration(_getBlogsEntry()));
	}

	@Test
	public void testGetEmbeddingProviderConfigurationWithObjectEntry()
		throws Exception {

		String objectDefinitionClassName1 = RandomTestUtil.randomString();

		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {objectDefinitionClassName1}, true);

		String objectDefinitionClassName2 = RandomTestUtil.randomString();

		Assert.assertNotNull(
			_textEmbeddingDocumentContributorImpl.
				getEmbeddingProviderConfiguration(
					_getObjectEntry(objectDefinitionClassName1)));

		Assert.assertNull(
			_textEmbeddingDocumentContributorImpl.
				getEmbeddingProviderConfiguration(
					_getObjectEntry(objectDefinitionClassName2)));
	}

	@Test
	public void testGetEmbeddingProviderConfigurationWithTextEmbeddingsDisabled()
		throws Exception {

		_setSemanticSearchConfiguration(
			new String[] {LocaleUtil.toLanguageId(LocaleUtil.US)},
			new String[] {DLFileEntry.class.getName()}, false);

		Assert.assertNull(
			_textEmbeddingDocumentContributorImpl.
				getEmbeddingProviderConfiguration(_getBlogsEntry()));
	}

	@Test
	public void testGetTextEmbeddingFieldName() throws Exception {
		int dimensions = RandomTestUtil.randomInt();

		Assert.assertEquals(
			"text_embedding_" + dimensions + "_en_US",
			_textEmbeddingDocumentContributorImpl.getTextEmbeddingFieldName(
				dimensions, LocaleUtil.toLanguageId(LocaleUtil.US)));
	}

	@Test
	public void testIsIndexableStatus() throws Exception {
		BlogsEntry blogsEntry = _getBlogsEntry();

		Assert.assertTrue(
			_textEmbeddingDocumentContributorImpl.isIndexableStatus(
				blogsEntry));

		Mockito.when(
			blogsEntry.getStatus()
		).thenReturn(
			WorkflowConstants.STATUS_IN_TRASH
		);

		Assert.assertFalse(
			_textEmbeddingDocumentContributorImpl.isIndexableStatus(
				blogsEntry));
	}

	@Test
	public void testIsIndexableStatusWithNotWorkflowableModel()
		throws Exception {

		Assert.assertTrue(
			_textEmbeddingDocumentContributorImpl.isIndexableStatus(
				Mockito.mock(DLFileEntry.class)));
	}

	private void _addDocumentField(
		Document document, String fieldName, String... values) {

		Mockito.when(
			document.getField(fieldName)
		).thenReturn(
			new Field(fieldName, values)
		);
	}

	private void _contributeWithBYOLLMProvider(
		Document document, String languageId, String text) {

		try (MockedStatic<FeatureFlagManagerUtil>
				featureFlagManagerUtilMockedStatic = Mockito.mockStatic(
					FeatureFlagManagerUtil.class)) {

			featureFlagManagerUtilMockedStatic.when(
				() -> FeatureFlagManagerUtil.isEnabled(
					Mockito.anyLong(), Mockito.eq("LPD-11319"))
			).thenReturn(
				true
			);

			if (languageId == null) {
				_textEmbeddingDocumentContributorImpl.contribute(
					document, _getBlogsEntry(), text);
			}
			else {
				_textEmbeddingDocumentContributorImpl.contribute(
					document, languageId, _getBlogsEntry(), text);
			}
		}
	}

	private SemanticSearchConfiguration _createSemanticSearchConfiguration(
		String[] embeddingProviderLanguageIds,
		String[] embeddingProviderModelClassNames, String embeddingProviderName,
		boolean enabled) {

		SemanticSearchConfiguration semanticSearchConfiguration = Mockito.mock(
			SemanticSearchConfiguration.class);

		Mockito.when(
			semanticSearchConfiguration.
				textEmbeddingProviderConfigurationJSONs()
		).thenReturn(
			new String[] {
				new EmbeddingProviderConfiguration(
				) {

					{
						setLanguageIds(embeddingProviderLanguageIds);
						setModelClassNames(embeddingProviderModelClassNames);
						setProviderName(embeddingProviderName);
					}
				}.toString()
			}
		);

		Mockito.when(
			semanticSearchConfiguration.textEmbeddingsEnabled()
		).thenReturn(
			enabled
		);

		return semanticSearchConfiguration;
	}

	private BlogsEntry _getBlogsEntry() {
		BlogsEntry blogsEntry = Mockito.mock(BlogsEntry.class);

		Mockito.doReturn(
			RandomTestUtil.randomLong()
		).when(
			blogsEntry
		).getCompanyId();

		Mockito.doReturn(
			BlogsEntry.class
		).when(
			blogsEntry
		).getModelClass();

		Mockito.doReturn(
			WorkflowConstants.STATUS_APPROVED
		).when(
			blogsEntry
		).getStatus();

		return blogsEntry;
	}

	private ObjectEntry _getObjectEntry(String objectDefinitionClassName)
		throws Exception {

		ObjectEntry objectEntry = Mockito.mock(ObjectEntry.class);

		Mockito.doReturn(
			RandomTestUtil.randomLong()
		).when(
			objectEntry
		).getCompanyId();

		Mockito.doReturn(
			ObjectEntry.class
		).when(
			objectEntry
		).getModelClass();

		Mockito.doReturn(
			objectDefinitionClassName
		).when(
			objectEntry
		).getModelClassName();

		return objectEntry;
	}

	private void _setSemanticSearchConfiguration(
		String[] embeddingProviderLanguageIds,
		String[] embeddingProviderModelClassNames, boolean enabled) {

		_setSemanticSearchConfiguration(
			embeddingProviderLanguageIds, embeddingProviderModelClassNames,
			RandomTestUtil.randomString(), enabled);
	}

	private void _setSemanticSearchConfiguration(
		String[] embeddingProviderLanguageIds,
		String[] embeddingProviderModelClassNames, String embeddingProviderName,
		boolean enabled) {

		SemanticSearchConfiguration semanticSearchConfiguration =
			_createSemanticSearchConfiguration(
				embeddingProviderLanguageIds, embeddingProviderModelClassNames,
				embeddingProviderName, enabled);

		Mockito.when(
			_semanticSearchConfigurationProvider.getCompanyConfiguration(
				Mockito.anyLong())
		).thenReturn(
			semanticSearchConfiguration
		);
	}

	private void _setUpTextEmbeddingDocumentContributorImpl() {
		_textEmbeddingDocumentContributorImpl =
			new TextEmbeddingDocumentContributorImpl();

		_textEmbeddingDocumentContributorImpl.activate(
			HashMapBuilder.<String, Object>put(
				"semantic.field.groups", _SEMANTIC_FIELD_GROUPS
			).build());

		ReflectionTestUtil.setFieldValue(
			_textEmbeddingDocumentContributorImpl,
			"semanticSearchConfigurationProvider",
			_semanticSearchConfigurationProvider);

		ReflectionTestUtil.setFieldValue(
			_textEmbeddingDocumentContributorImpl, "_semanticFieldNames",
			new SemanticFieldNamesImpl());

		Mockito.when(
			_externalEmbeddingCapabilityGate.check()
		).thenReturn(
			ExternalEmbeddingEligibility.available()
		);

		ReflectionTestUtil.setFieldValue(
			_textEmbeddingDocumentContributorImpl,
			"_externalEmbeddingCapabilityGate",
			_externalEmbeddingCapabilityGate);

		Language language = Mockito.mock(Language.class);

		Mockito.when(
			language.getAvailableLocales(Mockito.anyLong())
		).thenReturn(
			SetUtil.fromArray(new Locale[] {LocaleUtil.US, LocaleUtil.GERMAN})
		);

		Mockito.when(
			language.isAvailableLanguageCode(Mockito.anyString())
		).thenReturn(
			true
		);

		Mockito.when(
			language.isAvailableLocale(Mockito.any(Locale.class))
		).thenReturn(
			true
		);

		ReflectionTestUtil.setFieldValue(
			_textEmbeddingDocumentContributorImpl, "_language", language);

		LanguageUtil languageUtil = new LanguageUtil();

		languageUtil.setLanguage(language);

		SearchEngineInformation searchEngineInformation = Mockito.mock(
			SearchEngineInformation.class);

		Mockito.when(
			searchEngineInformation.getVendorString()
		).thenReturn(
			"Elasticsearch"
		);

		ReflectionTestUtil.setFieldValue(
			_textEmbeddingDocumentContributorImpl, "_searchEngineInformation",
			searchEngineInformation);

		Mockito.when(
			_textEmbeddingRetriever.getTextEmbedding(
				Mockito.anyString(), Mockito.anyString())
		).thenReturn(
			new Double[768]
		);

		ReflectionTestUtil.setFieldValue(
			_textEmbeddingDocumentContributorImpl, "_textEmbeddingRetriever",
			_textEmbeddingRetriever);
	}

	private static final String _BYO_LLM_PROVIDER_NAME =
		"Elasticsearch Inference Endpoint";

	private static final String _SEMANTIC_FIELD_GROUPS =
		"Title:title|Content/Body:content|Categorization:" +
			"assetCategoryTitles|Tags:assetTagNames";

	private final ExternalEmbeddingCapabilityGate
		_externalEmbeddingCapabilityGate = Mockito.mock(
			ExternalEmbeddingCapabilityGate.class);
	private final SemanticSearchConfigurationProvider
		_semanticSearchConfigurationProvider = Mockito.mock(
			SemanticSearchConfigurationProvider.class);
	private TextEmbeddingDocumentContributorImpl
		_textEmbeddingDocumentContributorImpl;
	private final TextEmbeddingRetriever _textEmbeddingRetriever = Mockito.mock(
		TextEmbeddingRetriever.class);

}