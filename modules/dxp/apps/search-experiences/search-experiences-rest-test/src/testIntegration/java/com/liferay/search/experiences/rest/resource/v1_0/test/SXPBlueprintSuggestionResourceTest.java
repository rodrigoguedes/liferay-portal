package com.liferay.search.experiences.rest.resource.v1_0.test;

import com.liferay.portal.search.rest.resource.v1_0.test.SuggestionResourceTest;
import org.junit.Test;

/**
 * @author Rodrigo Guedes de Souza
 */
//@RunWith(Arquillian.class)
public class SXPBlueprintSuggestionResourceTest extends SuggestionResourceTest {

	@Test
	public void test() {

	}

//	@Before
//	@Override
//	public void setUp() throws Exception {
//		super.setUp();
//
//		_journalArticle = JournalTestUtil.addArticle(
//			testGroup.getGroupId(), StringUtil.randomString(),
//			StringUtil.randomString());
//		_layout = LayoutTestUtil.addTypePortletLayout(testGroup);
//		_locale = LocaleUtil.getSiteDefault();
//		_serviceContext = ServiceContextTestUtil.getServiceContext(
//			testGroup, TestPropsValues.getUserId());
//	}
//
//	@Override
//	@Test
//	public void testPostSuggestionsPage() throws Exception {
//		_testPostSuggestionsPageWithSXPBlueprintSuggestionsContributor();
//		_testPostSuggestionsPageWithSXPBlueprintSuggestionsContributorWithGroupERCScope();
//		_testPostSuggestionsPageWithSXPBlueprintSuggestionsContributorWithSearchExperiencesAttributes();
//	}
//
//	private void _testPostSuggestionsPageWithSXPBlueprintSuggestionsContributor()
//		throws Exception {
//
//		String suggestionsDisplayGroupGroupName = "Suggestions";
//
//		SXPBlueprint sxpBlueprint = _sxpBlueprintLocalService.addSXPBlueprint(
//			null, TestPropsValues.getUserId(), "{}",
//			Collections.singletonMap(LocaleUtil.US, ""), null, "",
//			Collections.singletonMap(
//				LocaleUtil.US, RandomTestUtil.randomString()),
//			_serviceContext);
//
//		Page<SuggestionsContributorResults> page = _postSuggestionsPage(
//			"http://localhost:8080/web/guest/home", "/search",
//			testGroup.getGroupId(), "q", _layout.getPlid(), null,
//			_journalArticle.getArticleId(),
//			new SuggestionsContributorConfiguration[] {
//				new SuggestionsContributorConfiguration() {
//					{
//						attributes = JSONUtil.put(
//							"sxpBlueprintExternalReferenceCode",
//							sxpBlueprint.getExternalReferenceCode());
//						contributorName = "sxpBlueprint";
//						displayGroupName = suggestionsDisplayGroupGroupName;
//					}
//				}
//			});
//
//		_assertSuggestionContributorResults(
//			suggestionsDisplayGroupGroupName, page,
//			_journalArticle.getTitle(_locale));
//	}
//
//	private void _testPostSuggestionsPageWithSXPBlueprintSuggestionsContributorWithGroupERCScope()
//		throws Exception {
//
//		SXPBlueprint sxpBlueprint = _sxpBlueprintLocalService.addSXPBlueprint(
//			null, TestPropsValues.getUserId(), "{}",
//			Collections.singletonMap(LocaleUtil.US, ""), null, "",
//			Collections.singletonMap(
//				LocaleUtil.US, RandomTestUtil.randomString()),
//			_serviceContext);
//
//		String suggestionsDisplayGroupGroupName = "Suggestions";
//
//		Page<SuggestionsContributorResults> page = _postSuggestionsPage(
//			"http://localhost:8080/web/guest/home", "/search", null, "q",
//			_layout.getPlid(), testGroup.getExternalReferenceCode(),
//			_journalArticle.getArticleId(),
//			new SuggestionsContributorConfiguration[] {
//				new SuggestionsContributorConfiguration() {
//					{
//						attributes = JSONUtil.put(
//							"sxpBlueprintExternalReferenceCode",
//							sxpBlueprint.getExternalReferenceCode());
//						contributorName = "sxpBlueprint";
//						displayGroupName = suggestionsDisplayGroupGroupName;
//					}
//				}
//			});
//
//		_assertSuggestionContributorResults(
//			suggestionsDisplayGroupGroupName, page,
//			_journalArticle.getTitle(_locale));
//	}
//
//	private void _testPostSuggestionsPageWithSXPBlueprintSuggestionsContributorWithSearchExperiencesAttributes()
//		throws Exception {
//
//		Class<?> clazz = getClass();
//
//		SXPBlueprint sxpBlueprint = _sxpBlueprintLocalService.addSXPBlueprint(
//			null, TestPropsValues.getUserId(),
//			StringUtil.read(
//				clazz,
//				StringBundler.concat(
//					"dependencies/", clazz.getSimpleName(),
//					"._testPostSuggestionsPageWithSXPBlueprintSuggestions",
//					"ContributorWithSearchExperiencesAttributes.json")),
//			Collections.singletonMap(LocaleUtil.US, StringPool.BLANK), null,
//			StringPool.BLANK,
//			Collections.singletonMap(
//				LocaleUtil.US, RandomTestUtil.randomString()),
//			_serviceContext);
//
//		String suggestionsDisplayGroupGroupName = "Suggestions";
//
//		Page<SuggestionsContributorResults> page = _postSuggestionsPage(
//			"http://localhost:8080/web/guest/home", "/search",
//			testGroup.getGroupId(), "q", _layout.getPlid(), null,
//			_journalArticle.getArticleId(),
//			new SuggestionsContributorConfiguration[] {
//				new SuggestionsContributorConfiguration() {
//					{
//						attributes = JSONUtil.put(
//							"search.experiences.entry.class.pk",
//							_journalArticle.getResourcePrimKey()
//						).put(
//							"sxpBlueprintExternalReferenceCode",
//							sxpBlueprint.getExternalReferenceCode()
//						);
//						contributorName = "sxpBlueprint";
//						displayGroupName = suggestionsDisplayGroupGroupName;
//					}
//				}
//			});
//
//		_assertSuggestionContributorResults(
//			suggestionsDisplayGroupGroupName, page,
//			_journalArticle.getTitle(_locale));
//	}
//
//	private void _assertSuggestionContributorResults(
//		String displayGroupName, Page<SuggestionsContributorResults> page,
//		String... expectedTexts)
//		throws Exception {
//
//		SuggestionsContributorResults suggestionsContributorResults1 = null;
//
//		for (SuggestionsContributorResults suggestionsContributorResults2 :
//			page.getItems()) {
//
//			if (!StringUtil.equals(
//				suggestionsContributorResults2.getDisplayGroupName(),
//				displayGroupName)) {
//
//				continue;
//			}
//
//			suggestionsContributorResults1 = suggestionsContributorResults2;
//		}
//
//		Assert.assertTrue(suggestionsContributorResults1 != null);
//
//		Suggestion[] suggestions =
//			suggestionsContributorResults1.getSuggestions();
//
//		Assert.assertEquals(
//			Arrays.toString(suggestions), expectedTexts.length,
//			suggestions.length);
//
//		_assertSuggestionTexts(suggestionsContributorResults1, expectedTexts);
//	}
//
//	private void _assertSuggestionTexts(
//		SuggestionsContributorResults suggestionsContributorResults,
//		String... expectedTexts)
//		throws Exception {
//
//		Suggestion[] suggestions =
//			suggestionsContributorResults.getSuggestions();
//
//		List<String> texts = new ArrayList<>();
//
//		for (Suggestion suggestion : suggestions) {
//			texts.add(suggestion.getText());
//		}
//
//		Arrays.sort(expectedTexts);
//
//		Collections.sort(texts);
//
//		Assert.assertEquals(
//			Arrays.toString(expectedTexts), String.valueOf(texts));
//	}
//
//	private Page<SuggestionsContributorResults> _postSuggestionsPage(
//		String currentURL, String destinationFriendlyURL, Long groupId,
//		String keywordsParameterName, Long plid, String scope,
//		String search,
//		SuggestionsContributorConfiguration[]
//			suggestionsContributorConfigurations)
//		throws Exception {
//
//		return suggestionResource.postSuggestionsPage(
//			currentURL, destinationFriendlyURL, groupId, keywordsParameterName,
//			plid, scope, search, suggestionsContributorConfigurations);
//	}
//
//	private JournalArticle _journalArticle;
//	private Layout _layout;
//	private Locale _locale;
//	private ServiceContext _serviceContext;
//
//	@Inject
//	private SXPBlueprintLocalService _sxpBlueprintLocalService;

}
