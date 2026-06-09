/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.rest.internal.resource.v1_0;

import com.liferay.portal.search.rest.dto.v1_0.InferenceEndpoint;
import com.liferay.portal.search.rest.dto.v1_0.InferenceEndpointConfiguration;
import com.liferay.portal.search.rest.dto.v1_0.InferenceService;
import com.liferay.portal.search.rest.dto.v1_0.InferenceServiceField;
import com.liferay.portal.search.rest.resource.v1_0.InferenceEndpointResource;
import com.liferay.portal.search.semantic.InferenceEndpointRegistry;
import com.liferay.portal.vulcan.pagination.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * @author Rodrigo Guedes de Souza
 */
@Component(
	properties = "OSGI-INF/liferay/rest/v1_0/inference-endpoint.properties",
	scope = ServiceScope.PROTOTYPE, service = InferenceEndpointResource.class
)
public class InferenceEndpointResourceImpl
	extends BaseInferenceEndpointResourceImpl {

	@Override
	public Page<InferenceService> getEmbeddingInferenceServicesPage()
		throws Exception {

		List<InferenceService> inferenceServices = new ArrayList<>();

		for (com.liferay.portal.search.semantic.InferenceService
				inferenceService :
					_inferenceEndpointRegistry.
						getTextEmbeddingInferenceServices()) {

			inferenceServices.add(_toInferenceService(inferenceService));
		}

		return Page.of(inferenceServices);
	}

	@Override
	public InferenceEndpoint postEmbeddingInferenceEndpoint(
			InferenceEndpointConfiguration inferenceEndpointConfiguration)
		throws Exception {

		_inferenceEndpointRegistry.createTextEmbeddingInferenceEndpoint(
			inferenceEndpointConfiguration.getInferenceId(),
			inferenceEndpointConfiguration.getService(),
			(Map<String, Object>)
				inferenceEndpointConfiguration.getServiceSettings());

		return new InferenceEndpoint() {
			{
				setInferenceId(inferenceEndpointConfiguration::getInferenceId);
				setService(inferenceEndpointConfiguration::getService);
				setTaskType(() -> "text_embedding");
			}
		};
	}

	private InferenceService _toInferenceService(
		com.liferay.portal.search.semantic.InferenceService inferenceService) {

		return new InferenceService() {
			{
				setInferenceServiceFields(
					() -> _toInferenceServiceFieldDTOs(
						inferenceService.getFields()));
				setName(inferenceService::getName);
				setService(inferenceService::getService);
				setTaskTypes(
					() -> inferenceService.getTaskTypes(
					).toArray(
						new String[0]
					));
			}
		};
	}

	private InferenceServiceField _toInferenceServiceField(
		com.liferay.portal.search.semantic.InferenceServiceField
			inferenceServiceField) {

		return new InferenceServiceField() {
			{
				setDescription(inferenceServiceField::getDescription);
				setKey(inferenceServiceField::getKey);
				setLabel(inferenceServiceField::getLabel);
				setRequired(inferenceServiceField::isRequired);
				setSensitive(inferenceServiceField::isSensitive);
				setSupportedTaskTypes(
					() -> inferenceServiceField.getSupportedTaskTypes(
					).toArray(
						new String[0]
					));
				setType(inferenceServiceField::getType);
			}
		};
	}

	private InferenceServiceField[] _toInferenceServiceFieldDTOs(
		List<com.liferay.portal.search.semantic.InferenceServiceField>
			inferenceServiceFields) {

		InferenceServiceField[] inferenceServiceFieldDTOs =
			new InferenceServiceField[inferenceServiceFields.size()];

		for (int i = 0; i < inferenceServiceFields.size(); i++) {
			inferenceServiceFieldDTOs[i] = _toInferenceServiceField(
				inferenceServiceFields.get(i));
		}

		return inferenceServiceFieldDTOs;
	}

	@Reference
	private InferenceEndpointRegistry _inferenceEndpointRegistry;

}