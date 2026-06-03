/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.capabilities;

import com.liferay.portal.search.capabilities.ElasticsearchLicenseInformation;
import com.liferay.portal.search.elasticsearch8.internal.connection.ElasticsearchConnectionManager;
import com.liferay.portal.search.elasticsearch8.internal.web.cache.ElasticsearchLicenseWebCacheItem;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Rodrigo Guedes de Souza
 */
@Component(service = ElasticsearchLicenseInformation.class)
public class ElasticsearchLicenseInformationImpl
	implements ElasticsearchLicenseInformation {

	@Override
	public boolean supportsInferenceAPI() {
		return ElasticsearchLicenseWebCacheItem.get(
			_elasticsearchConnectionManager);
	}

	@Reference
	private ElasticsearchConnectionManager _elasticsearchConnectionManager;

}