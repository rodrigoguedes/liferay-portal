/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.search.experiences.rest.resource.v1_0.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.portal.test.rule.FeatureFlag;
import com.liferay.portal.test.rule.FeatureFlags;
import org.junit.runner.RunWith;

/**
 * @author Rodrigo Guedes de Souza
 */
@FeatureFlags(featureFlags = @FeatureFlag(value = "LPS-179669"))
@RunWith(Arquillian.class)
public class SXPBlueprintSearchResultResourceTest {
//	extends BaseSearchResultResourceTestCase {

//	@ClassRule
//	@Rule
//	public static final AggregateTestRule aggregateTestRule =
//		new AggregateTestRule(
//			new LiferayIntegrationTestRule(),
//			PermissionCheckerMethodTestRule.INSTANCE);
//
//	@Before
//	@Override
//	public void setUp() throws Exception {
//		super.setUp();
//
//		_locale = LocaleUtil.getSiteDefault();
//
//		_searchEngine = _searchEngineHelper.getSearchEngine();
//
//		_user = TestPropsValues.getUser();
//
//		_serviceContext = ServiceContextTestUtil.getServiceContext(
//			testGroup, _user.getUserId());
//	}
//
//	@Test
//	@TestInfo("LPD-57341")
//	public void testPostSearchPageWithHighlighting() throws Exception {
//		_testPostSearchPageWithHighlightConfiguration();
//		_testPostSearchPageWithoutHighlightConfiguration();
//	}
//
//	private SXPBlueprint _addSXPBlueprint(boolean highlightingEnabled)
//		throws Exception {
//
//		JSONObject configurationJSONObject = JSONUtil.put(
//			"advancedConfiguration",
//			JSONUtil.put(
//				"source",
//				JSONUtil.put(
//					"fetchSource", true
//				).put(
//					"includes",
//					JSONFactoryUtil.createJSONArray(
//					).put(
//						"fullName"
//					)
//				))
//		).put(
//			"generalConfiguration",
//			JSONUtil.put(
//				"searchableAssetTypes",
//				JSONUtil.put("com.liferay.portal.kernel.model.User"))
//		).put(
//			"queryConfiguration", JSONUtil.put("applyIndexerClauses", true)
//		);
//
//		if (highlightingEnabled) {
//			configurationJSONObject.put(
//				"highlightConfiguration",
//				_createSXPBlueprintHighlightConfigurationJSON());
//		}
//
//		return _sxpBlueprintLocalService.addSXPBlueprint(
//			null, _user.getUserId(), configurationJSONObject.toString(),
//			Collections.singletonMap(_locale, StringPool.BLANK), null,
//			StringPool.BLANK,
//			Collections.singletonMap(_locale, RandomTestUtil.randomString()),
//			_serviceContext);
//	}
//
//	private JSONObject _createSXPBlueprintHighlightConfigurationJSON() {
//		return JSONUtil.put(
//			"fields",
//			JSONUtil.put(
//				"fullName",
//				JSONUtil.put(
//					"fragment_size", 100
//				).put(
//					"number_of_fragments", 10
//				))
//		).put(
//			"post_tags",
//			JSONFactoryUtil.createJSONArray(
//			).put(
//				"</liferay-hl>"
//			)
//		).put(
//			"pre_tags",
//			JSONFactoryUtil.createJSONArray(
//			).put(
//				"<liferay-hl>"
//			)
//		).put(
//			"require_field_match", true
//		);
//	}
//
//	private String _getEndpoint(Map<String, String> parameters)
//		throws Exception {
//
//		StringBundler sb = new StringBundler((parameters.size() * 4) + 2);
//
//		sb.append(_baseURI);
//		sb.append("/v1.0/search?");
//
//		for (Map.Entry<String, String> entry : parameters.entrySet()) {
//			sb.append("&");
//			sb.append(entry.getKey());
//			sb.append("=");
//			sb.append(
//				URLEncoder.encode(
//					GetterUtil.getString(entry.getValue()), StringPool.UTF8));
//		}
//
//		return sb.toString();
//	}
//
//	private Version _getSearchEngineVersion() {
//		List<ConnectionInformation> connectionInformationList =
//			_searchEngineInformation.getConnectionInformationList();
//
//		ConnectionInformation connectionInformation =
//			connectionInformationList.get(0);
//
//		List<NodeInformation> nodeInformationList =
//			connectionInformation.getNodeInformationList();
//
//		NodeInformation nodeInformation = nodeInformationList.get(0);
//
//		return Version.parseVersion(nodeInformation.getVersion());
//	}
//
//	private Map<String, JSONArray> _getSearchFacets(JSONObject jsonObject) {
//		JSONObject searchFacetsJSONObject = jsonObject.getJSONObject(
//			"searchFacets");
//
//		if (searchFacetsJSONObject == null) {
//			return null;
//		}
//
//		Map<String, JSONArray> map = new HashMap<>();
//
//		Iterator<String> iterator = searchFacetsJSONObject.keys();
//
//		while (iterator.hasNext()) {
//			String key = iterator.next();
//
//			map.put(key, searchFacetsJSONObject.getJSONArray(key));
//		}
//
//		return map;
//	}
//
//	private String _getUserHighlightedFullName() {
//		Version version = _getSearchEngineVersion();
//
//		if (_isSearchEngineElasticsearch() &&
//			(version.compareTo(Version.parseVersion("8.10.2")) >= 0)) {
//
//			return StringBundler.concat(
//				HighlightUtil.HIGHLIGHT_TAG_OPEN, _user.getFirstName(),
//				StringPool.SPACE, _user.getLastName(),
//				HighlightUtil.HIGHLIGHT_TAG_CLOSE);
//		}
//
//		return StringBundler.concat(
//			HighlightUtil.HIGHLIGHT_TAG_OPEN, _user.getFirstName(),
//			HighlightUtil.HIGHLIGHT_TAG_CLOSE, StringPool.SPACE,
//			HighlightUtil.HIGHLIGHT_TAG_OPEN, _user.getLastName(),
//			HighlightUtil.HIGHLIGHT_TAG_CLOSE);
//	}
//
//	private boolean _isSearchEngineElasticsearch() {
//		return StringUtil.startsWith(
//			_searchEngineInformation.getVendorString(), "Elasticsearch");
//	}
//
//	private SearchPage<SearchResult> _postSearchPage(
//			Map<String, String> parameters, SearchRequestBody searchRequestBody)
//		throws Exception {
//
//		return _toSearchPage(
//			HTTPTestUtil.invokeToJSONObject(
//				searchRequestBody.toString(), _getEndpoint(parameters),
//				Http.Method.POST));
//	}
//
//	private SearchPage<SearchResult>
//		_postSearchPageWithSXPBlueprintConfiguration(
//			String entryClassNames, String keywords,
//			SXPBlueprint sxpBlueprint)
//		throws Exception {
//
//		SearchRequestBody searchRequestBody = new SearchRequestBody() {
//			{
//				attributes = HashMapBuilder.<String, Object>put(
//					"search.experiences.blueprint.external.reference.code",
//					sxpBlueprint.getExternalReferenceCode()
//				).build();
//			}
//		};
//
//		return _postSearchPage(
//			HashMapBuilder.put(
//				"entryClassNames", entryClassNames
//			).put(
//				"search", keywords
//			).build(),
//			searchRequestBody);
//	}
//
//	private void _testPostSearchPageWithHighlightConfiguration()
//		throws Exception {
//
//		if (Objects.equals(_searchEngine.getVendor(), "Solr")) {
//			return;
//		}
//
//		SearchPage<SearchResult> searchPage =
//			_postSearchPageWithSXPBlueprintConfiguration(
//				_user.getModelClassName(), _user.getFullName(),
//				_addSXPBlueprint(true));
//
//		List<SearchResult> searchResults = ListUtil.fromCollection(
//			searchPage.getItems());
//
//		Assert.assertFalse(searchResults.isEmpty());
//
//		int count = ListUtil.count(
//			searchResults,
//			searchResult -> Objects.equals(
//				searchResult.getTitle(), _getUserHighlightedFullName()));
//
//		Assert.assertTrue(count >= 1);
//	}
//
//	private void _testPostSearchPageWithoutHighlightConfiguration()
//		throws Exception {
//
//		if (Objects.equals(_searchEngine.getVendor(), "Solr")) {
//			return;
//		}
//
//		SearchPage<SearchResult> searchPage =
//			_postSearchPageWithSXPBlueprintConfiguration(
//				_user.getModelClassName(), _user.getFullName(),
//				_addSXPBlueprint(false));
//
//		List<SearchResult> searchResults = ListUtil.fromCollection(
//			searchPage.getItems());
//
//		Assert.assertFalse(searchResults.isEmpty());
//
//		int count = ListUtil.count(
//			searchResults,
//			searchResult -> Objects.equals(
//				searchResult.getTitle(), _user.getFullName()));
//
//		Assert.assertTrue(count >= 1);
//
//		Assert.assertEquals(
//			0,
//			ListUtil.count(
//				searchResults,
//				searchResult -> Objects.equals(
//					searchResult.getTitle(), _getUserHighlightedFullName())));
//	}
//
//	private SearchPage<SearchResult> _toSearchPage(JSONObject jsonObject)
//		throws Exception {
//
//		return SearchPage.of(
//			null, null, _getSearchFacets(jsonObject),
//			JSONUtil.toList(
//				jsonObject.getJSONArray("items"),
//				itemJSONObject -> SearchResult.toDTO(
//					itemJSONObject.toString())),
//			Pagination.of(
//				jsonObject.getInt("page"), jsonObject.getInt("pageSize")),
//			jsonObject.getLong("totalCount"));
//	}
//
//	private String _baseURI = "search";
//	private Locale _locale;
//	private SearchEngine _searchEngine;
//
//	@Inject
//	private SearchEngineHelper _searchEngineHelper;
//
//	@Inject
//	private SearchEngineInformation _searchEngineInformation;
//
//	private ServiceContext _serviceContext;
//
//	@Inject
//	private SXPBlueprintLocalService _sxpBlueprintLocalService;
//
//	private User _user;

}