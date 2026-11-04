/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.web.internal.search.results.portlet.helper;

import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.HashMapBuilder;
import com.liferay.portal.search.engine.SearchEngineInformation;
import com.liferay.portal.search.web.internal.search.results.portlet.shared.search.SearchResultsPortletSharedSearchContributor;

import java.io.IOException;

import java.util.Dictionary;
import java.util.Map;

import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Rodrigo Guedes de Souza
 */
@Component(service = ResultCountAccuracyHelper.class)
public class ResultCountAccuracyHelper {

	public int getLimit() {
		return Math.min(
			SearchResultsPortletSharedSearchContributor.TRACK_TOTAL_HITS_LIMIT,
			_getTrackTotalHitsLimit());
	}

	private int _getTrackTotalHitsLimit() {
		String configurationPid = _configurationPids.get(
			_searchEngineInformation.getVendorString());

		if (configurationPid == null) {
			return Integer.MAX_VALUE;
		}

		try {
			Configuration[] configurations =
				_configurationAdmin.listConfigurations(
					StringBundler.concat(
						"(", Constants.SERVICE_PID, "=", configurationPid,
						")"));

			if (ArrayUtil.isEmpty(configurations)) {
				return Integer.MAX_VALUE;
			}

			Configuration configuration = configurations[0];

			Dictionary<String, Object> properties =
				configuration.getProperties();

			return GetterUtil.getInteger(
				properties.get("trackTotalHitsLimit"), Integer.MAX_VALUE);
		}
		catch (InvalidSyntaxException | IOException exception) {
			_log.error(
				"Unable to read the search engine configuration", exception);

			return Integer.MAX_VALUE;
		}
	}

	private static final String _ELASTICSEARCH_CONFIGURATION_PID =
		"com.liferay.portal.search.elasticsearch8.configuration." +
			"ElasticsearchConfiguration";

	private static final String _OPENSEARCH_CONFIGURATION_PID =
		"com.liferay.portal.search.opensearch2.configuration." +
			"OpenSearchConfiguration";

	private static final Log _log = LogFactoryUtil.getLog(
		ResultCountAccuracyHelper.class);

	private static final Map<String, String> _configurationPids =
		HashMapBuilder.put(
			"Elasticsearch", _ELASTICSEARCH_CONFIGURATION_PID
		).put(
			"Elasticsearch (Sidecar)", _ELASTICSEARCH_CONFIGURATION_PID
		).put(
			"OpenSearch", _OPENSEARCH_CONFIGURATION_PID
		).build();

	@Reference
	private ConfigurationAdmin _configurationAdmin;

	@Reference
	private SearchEngineInformation _searchEngineInformation;

}