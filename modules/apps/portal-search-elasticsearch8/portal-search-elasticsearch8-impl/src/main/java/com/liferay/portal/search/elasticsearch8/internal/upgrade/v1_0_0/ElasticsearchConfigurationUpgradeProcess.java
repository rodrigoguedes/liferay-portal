/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.elasticsearch8.internal.upgrade.v1_0_0;

import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.upgrade.UpgradeProcess;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.search.elasticsearch8.configuration.ElasticsearchConfiguration;

import java.util.Dictionary;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

/**
 * @author Rodrigo Guedes de Souza
 */
public class ElasticsearchConfigurationUpgradeProcess extends UpgradeProcess {

	public ElasticsearchConfigurationUpgradeProcess(
		ConfigurationAdmin configurationAdmin) {

		_configurationAdmin = configurationAdmin;
	}

	@Override
	protected void doUpgrade() throws Exception {
		Configuration elasticsearch7Configuration = _configurationAdmin.getConfiguration(
			com.liferay.portal.search.elasticsearch7.configuration.ElasticsearchConfiguration.class.getName(), StringPool.QUESTION);

		Configuration elasticsearch8Configuration = _configurationAdmin.getConfiguration(
			ElasticsearchConfiguration.class.getName(), StringPool.QUESTION);

		Dictionary<String, Object> elasticsearch7Properties = elasticsearch7Configuration.getProperties();

		Dictionary<String, Object> elasticsearch8Properties = elasticsearch8Configuration.getProperties();

		if (elasticsearch8Properties == null || elasticsearch7Properties == null) {
			return;
		}

		String operationMode = GetterUtil.getString(
			elasticsearch7Properties.get("operationMode"));

		if (StringUtil.equals(operationMode, "REMOTE")) {
			elasticsearch8Properties.put("productionModeEnabled", Boolean.TRUE);
		}

		int embeddedHttpPort = GetterUtil.getInteger(
			elasticsearch7Properties.get("embeddedHttpPort"));

		elasticsearch8Properties.put("sidecarHttpPort", embeddedHttpPort);

		elasticsearch8Properties.remove("operationMode");
		elasticsearch8Properties.remove("embeddedHttpPort");
		elasticsearch8Properties.remove("discoveryZenPingUnicastHostsPort");
		elasticsearch8Properties.remove("trackTotalHits");
		elasticsearch8Properties.remove("restClientLoggerLevel");

		elasticsearch8Configuration.update(elasticsearch8Properties);
	}

	private final ConfigurationAdmin _configurationAdmin;
}
