/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

import com.liferay.petra.string.StringUtil;

import java.util.Objects;

/**
 * Decides whether switching the configured inference endpoint from {@code
 * currentInferenceEndpoint} to {@code candidateInferenceEndpoint} is an
 * equivalent change or a breaking one, by comparing the four metadata fields
 * that determine the embedding space: {@code service} and {@code similarity}
 * (compared case-insensitively, since they are provider keywords), {@code
 * model_id} (compared exactly), and {@code dimensions}. A missing endpoint on
 * either side cannot be confirmed equivalent and is treated as breaking.
 *
 * @author Rodrigo Guedes de Souza
 */
public class SemanticEndpointChanges {

	public static SemanticEndpointChangeType classify(
		InferenceEndpoint currentInferenceEndpoint,
		InferenceEndpoint candidateInferenceEndpoint) {

		if ((currentInferenceEndpoint == null) ||
			(candidateInferenceEndpoint == null)) {

			return SemanticEndpointChangeType.BREAKING;
		}

		if (StringUtil.equalsIgnoreCase(
				currentInferenceEndpoint.getService(),
				candidateInferenceEndpoint.getService()) &&
			Objects.equals(
				currentInferenceEndpoint.getModelId(),
				candidateInferenceEndpoint.getModelId()) &&
			(currentInferenceEndpoint.getDimensions() ==
				candidateInferenceEndpoint.getDimensions()) &&
			StringUtil.equalsIgnoreCase(
				currentInferenceEndpoint.getSimilarity(),
				candidateInferenceEndpoint.getSimilarity())) {

			return SemanticEndpointChangeType.EQUIVALENT;
		}

		return SemanticEndpointChangeType.BREAKING;
	}

	private SemanticEndpointChanges() {
	}

}