/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.rest.client.dto.v1_0;

import com.liferay.portal.search.rest.client.function.UnsafeSupplier;
import com.liferay.portal.search.rest.client.serdes.v1_0.InferenceEndpointConfigurationSerDes;

import jakarta.annotation.Generated;

import java.io.Serializable;

import java.util.Objects;

/**
 * @author Petteri Karttunen
 * @generated
 */
@Generated("")
public class InferenceEndpointConfiguration implements Cloneable, Serializable {

	public static InferenceEndpointConfiguration toDTO(String json) {
		return InferenceEndpointConfigurationSerDes.toDTO(json);
	}

	public String getInferenceId() {
		return inferenceId;
	}

	public void setInferenceId(String inferenceId) {
		this.inferenceId = inferenceId;
	}

	public void setInferenceId(
		UnsafeSupplier<String, Exception> inferenceIdUnsafeSupplier) {

		try {
			inferenceId = inferenceIdUnsafeSupplier.get();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected String inferenceId;

	public String getService() {
		return service;
	}

	public void setService(String service) {
		this.service = service;
	}

	public void setService(
		UnsafeSupplier<String, Exception> serviceUnsafeSupplier) {

		try {
			service = serviceUnsafeSupplier.get();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected String service;

	public Object getServiceSettings() {
		return serviceSettings;
	}

	public void setServiceSettings(Object serviceSettings) {
		this.serviceSettings = serviceSettings;
	}

	public void setServiceSettings(
		UnsafeSupplier<Object, Exception> serviceSettingsUnsafeSupplier) {

		try {
			serviceSettings = serviceSettingsUnsafeSupplier.get();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected Object serviceSettings;

	@Override
	public InferenceEndpointConfiguration clone()
		throws CloneNotSupportedException {

		return (InferenceEndpointConfiguration)super.clone();
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}

		if (!(object instanceof InferenceEndpointConfiguration)) {
			return false;
		}

		InferenceEndpointConfiguration inferenceEndpointConfiguration =
			(InferenceEndpointConfiguration)object;

		return Objects.equals(
			toString(), inferenceEndpointConfiguration.toString());
	}

	@Override
	public int hashCode() {
		String string = toString();

		return string.hashCode();
	}

	public String toString() {
		return InferenceEndpointConfigurationSerDes.toJSON(this);
	}

}
// LIFERAY-REST-BUILDER-HASH:-1202860098