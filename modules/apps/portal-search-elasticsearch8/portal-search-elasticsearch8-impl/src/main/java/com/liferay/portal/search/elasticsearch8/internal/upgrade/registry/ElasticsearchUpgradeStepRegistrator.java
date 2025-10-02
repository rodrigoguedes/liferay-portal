package com.liferay.portal.search.elasticsearch8.internal.upgrade.registry;

import com.liferay.portal.search.elasticsearch8.internal.upgrade.v1_0_0.ElasticsearchConfigurationUpgradeProcess;
import com.liferay.portal.upgrade.registry.UpgradeStepRegistrator;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Rodrigo Guedes de Souza
 */
@Component(service = UpgradeStepRegistrator.class)
public class ElasticsearchUpgradeStepRegistrator implements
	UpgradeStepRegistrator {

	@Override
	public void register(Registry registry) {
		registry.registerInitialization();

		registry.register(
			"0.0.0", "1.0.0",
			new ElasticsearchConfigurationUpgradeProcess(_configurationAdmin));
	}

	@Reference
	private ConfigurationAdmin _configurationAdmin;

}
