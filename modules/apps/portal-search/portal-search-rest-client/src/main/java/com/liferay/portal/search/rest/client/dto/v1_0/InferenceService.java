/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.rest.client.dto.v1_0;

import com.liferay.portal.search.rest.client.function.UnsafeSupplier;
import com.liferay.portal.search.rest.client.serdes.v1_0.InferenceServiceSerDes;

import jakarta.annotation.Generated;

import java.io.Serializable;

import java.util.Objects;

/**
 * @author Petteri Karttunen
 * @generated
 */
@Generated("")
public class InferenceService implements Cloneable, Serializable {

	public static InferenceService toDTO(String json) {
		return InferenceServiceSerDes.toDTO(json);
	}

	public InferenceServiceField[] getInferenceServiceFields() {
		return inferenceServiceFields;
	}

	public void setInferenceServiceFields(
		InferenceServiceField[] inferenceServiceFields) {

		this.inferenceServiceFields = inferenceServiceFields;
	}

	public void setInferenceServiceFields(
		UnsafeSupplier<InferenceServiceField[], Exception>
			inferenceServiceFieldsUnsafeSupplier) {

		try {
			inferenceServiceFields = inferenceServiceFieldsUnsafeSupplier.get();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected InferenceServiceField[] inferenceServiceFields;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setName(UnsafeSupplier<String, Exception> nameUnsafeSupplier) {
		try {
			name = nameUnsafeSupplier.get();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected String name;

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

	public String[] getTaskTypes() {
		return taskTypes;
	}

	public void setTaskTypes(String[] taskTypes) {
		this.taskTypes = taskTypes;
	}

	public void setTaskTypes(
		UnsafeSupplier<String[], Exception> taskTypesUnsafeSupplier) {

		try {
			taskTypes = taskTypesUnsafeSupplier.get();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected String[] taskTypes;

	@Override
	public InferenceService clone() throws CloneNotSupportedException {
		return (InferenceService)super.clone();
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}

		if (!(object instanceof InferenceService)) {
			return false;
		}

		InferenceService inferenceService = (InferenceService)object;

		return Objects.equals(toString(), inferenceService.toString());
	}

	@Override
	public int hashCode() {
		String string = toString();

		return string.hashCode();
	}

	public String toString() {
		return InferenceServiceSerDes.toJSON(this);
	}

}
// LIFERAY-REST-BUILDER-HASH:995095691