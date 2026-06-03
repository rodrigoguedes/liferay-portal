/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.web.cache;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.license.GetLicenseResponse;
import co.elastic.clients.elasticsearch.license.LicenseStatus;
import co.elastic.clients.elasticsearch.license.LicenseType;
import co.elastic.clients.elasticsearch.license.get.LicenseInformation;

import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.Time;
import com.liferay.portal.kernel.webcache.WebCacheItem;
import com.liferay.portal.kernel.webcache.WebCachePoolUtil;
import com.liferay.portal.search.elasticsearch8.internal.connection.ElasticsearchConnectionManager;

/**
 * @author Rodrigo Guedes de Souza
 */
public class ElasticsearchLicenseWebCacheItem implements WebCacheItem {

	public static boolean get(
		ElasticsearchConnectionManager elasticsearchConnectionManager) {

		try {
			return GetterUtil.getBoolean(
				WebCachePoolUtil.get(
					ElasticsearchLicenseWebCacheItem.class.getName(),
					new ElasticsearchLicenseWebCacheItem(
						elasticsearchConnectionManager)));
		}
		catch (Exception exception) {
			if (_log.isDebugEnabled()) {
				_log.debug(exception);
			}

			return false;
		}
	}

	public ElasticsearchLicenseWebCacheItem(
		ElasticsearchConnectionManager elasticsearchConnectionManager) {

		_elasticsearchConnectionManager = elasticsearchConnectionManager;
	}

	@Override
	public Boolean convert(String key) {
		try {
			ElasticsearchClient elasticsearchClient =
				_elasticsearchConnectionManager.getElasticsearchClient();

			if (elasticsearchClient == null) {
				return false;
			}

			GetLicenseResponse getLicenseResponse = elasticsearchClient.license(
			).get();

			if (getLicenseResponse == null) {
				return false;
			}

			LicenseInformation licenseInformation =
				getLicenseResponse.license();

			if ((licenseInformation == null) ||
				(licenseInformation.status() != LicenseStatus.Active)) {

				return false;
			}

			LicenseType type = licenseInformation.type();

			if ((type == LicenseType.Trial) ||
				(type == LicenseType.Enterprise)) {

				return true;
			}

			return false;
		}
		catch (Exception exception) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"Unable to query the Elasticsearch \"_license\" API: " +
						exception.getMessage());
			}

			if (_log.isDebugEnabled()) {
				_log.debug(
					"Unable to query the Elasticsearch \"_license\" API",
					exception);
			}

			return false;
		}
	}

	@Override
	public long getRefreshTime() {
		return _REFRESH_TIME;
	}

	private static final long _REFRESH_TIME = Time.MINUTE * 5;

	private static final Log _log = LogFactoryUtil.getLog(
		ElasticsearchLicenseWebCacheItem.class);

	private final ElasticsearchConnectionManager
		_elasticsearchConnectionManager;

}