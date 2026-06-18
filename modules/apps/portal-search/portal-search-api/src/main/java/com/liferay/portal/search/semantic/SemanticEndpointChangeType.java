/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

/**
 * Classifies what switching the configured inference endpoint means for the
 * already-indexed data.
 *
 * <ul>
 * <li>{@link #EQUIVALENT} — the new endpoint embeds with the same provider,
 * model, dimensions, and similarity (the administrator renamed the endpoint or
 * rotated its API key), so the existing vectors stay valid and only a silent
 * {@code PUT _mapping} to the new {@code inference_id} is required.</li>
 * <li>{@link #BREAKING} — at least one of provider, model, dimensions, or
 * similarity differs, so the existing vectors live in a different space and all
 * data must be reembedded with the new endpoint for search to stay
 * correct.</li>
 * </ul>
 *
 * @author Rodrigo Guedes de Souza
 */
public enum SemanticEndpointChangeType {

	BREAKING, EQUIVALENT

}