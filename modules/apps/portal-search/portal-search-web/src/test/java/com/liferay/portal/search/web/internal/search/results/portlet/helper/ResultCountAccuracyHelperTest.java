/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.web.internal.search.results.portlet.helper;

import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.test.ReflectionTestUtil;
import com.liferay.portal.kernel.util.HashMapDictionary;
import com.liferay.portal.search.engine.SearchEngineInformation;
import com.liferay.portal.test.rule.LiferayUnitTestRule;

import java.util.Dictionary;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import org.mockito.Mockito;

import org.osgi.framework.Constants;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

/**
 * @author Rodrigo Guedes de Souza
 */
public class ResultCountAccuracyHelperTest {

	@ClassRule
	@Rule
	public static final LiferayUnitTestRule liferayUnitTestRule =
		LiferayUnitTestRule.INSTANCE;

	@Before
	public void setUp() {
		ReflectionTestUtil.setFieldValue(
			_resultCountAccuracyHelper, "_configurationAdmin",
			_configurationAdmin);
		ReflectionTestUtil.setFieldValue(
			_resultCountAccuracyHelper, "_searchEngineInformation",
			_searchEngineInformation);
	}

	@Test
	public void testGetLimit() throws Exception {
		_setUpSearchEngineInformation("Elasticsearch");

		_setUpConfiguration(_ELASTICSEARCH_CONFIGURATION_PID, 5000);

		Assert.assertEquals(5000, _resultCountAccuracyHelper.getLimit());

		_setUpConfiguration(_ELASTICSEARCH_CONFIGURATION_PID, 10001);

		Assert.assertEquals(10001, _resultCountAccuracyHelper.getLimit());

		_setUpConfiguration(_ELASTICSEARCH_CONFIGURATION_PID, 20000);

		Assert.assertEquals(10001, _resultCountAccuracyHelper.getLimit());

		_setUpConfiguration(_ELASTICSEARCH_CONFIGURATION_PID, "5000");

		Assert.assertEquals(5000, _resultCountAccuracyHelper.getLimit());
	}

	@Test
	public void testGetLimitWithOpenSearch() throws Exception {
		_setUpSearchEngineInformation("OpenSearch");

		_setUpConfiguration(_OPENSEARCH_CONFIGURATION_PID, 5000);

		Assert.assertEquals(5000, _resultCountAccuracyHelper.getLimit());
	}

	@Test
	public void testGetLimitWithSidecar() throws Exception {
		_setUpSearchEngineInformation("Elasticsearch (Sidecar)");

		_setUpConfiguration(_ELASTICSEARCH_CONFIGURATION_PID, 5000);

		Assert.assertEquals(5000, _resultCountAccuracyHelper.getLimit());
	}

	@Test
	public void testGetLimitWithUnknownSearchEngine() throws Exception {
		_setUpSearchEngineInformation("Solr");

		_setUpConfiguration(_ELASTICSEARCH_CONFIGURATION_PID, 5000);
		_setUpConfiguration(_OPENSEARCH_CONFIGURATION_PID, 5000);

		Assert.assertEquals(10001, _resultCountAccuracyHelper.getLimit());
	}

	@Test
	public void testGetLimitWithoutConfiguration() throws Exception {
		_setUpSearchEngineInformation("Elasticsearch");

		Assert.assertEquals(10001, _resultCountAccuracyHelper.getLimit());
	}

	@Test
	public void testGetLimitWithoutTrackTotalHitsLimit() throws Exception {
		_setUpSearchEngineInformation("Elasticsearch");

		_setUpConfiguration(_ELASTICSEARCH_CONFIGURATION_PID, null);

		Assert.assertEquals(10001, _resultCountAccuracyHelper.getLimit());
	}

	private void _setUpConfiguration(
			String configurationPid, Object trackTotalHitsLimit)
		throws Exception {

		Dictionary<String, Object> properties = new HashMapDictionary<>();

		if (trackTotalHitsLimit != null) {
			properties.put("trackTotalHitsLimit", trackTotalHitsLimit);
		}

		Configuration configuration = Mockito.mock(Configuration.class);

		Mockito.doReturn(
			properties
		).when(
			configuration
		).getProperties();

		Mockito.doReturn(
			new Configuration[] {configuration}
		).when(
			_configurationAdmin
		).listConfigurations(
			StringBundler.concat(
				"(", Constants.SERVICE_PID, "=", configurationPid, ")")
		);
	}

	private void _setUpSearchEngineInformation(String vendorString) {
		Mockito.doReturn(
			vendorString
		).when(
			_searchEngineInformation
		).getVendorString();
	}

	private static final String _ELASTICSEARCH_CONFIGURATION_PID =
		"com.liferay.portal.search.elasticsearch8.configuration." +
			"ElasticsearchConfiguration";

	private static final String _OPENSEARCH_CONFIGURATION_PID =
		"com.liferay.portal.search.opensearch2.configuration." +
			"OpenSearchConfiguration";

	private final ConfigurationAdmin _configurationAdmin = Mockito.mock(
		ConfigurationAdmin.class);
	private final ResultCountAccuracyHelper _resultCountAccuracyHelper =
		new ResultCountAccuracyHelper();
	private final SearchEngineInformation _searchEngineInformation =
		Mockito.mock(SearchEngineInformation.class);

}