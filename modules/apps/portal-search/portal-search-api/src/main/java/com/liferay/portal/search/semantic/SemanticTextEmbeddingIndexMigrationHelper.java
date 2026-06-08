/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.semantic;

import java.util.List;
import java.util.Locale;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Adds {@code semantic_text} fields to an already-created company index via
 * {@code PUT _mapping}, so an administrator enabling BYO-LLM on an existing
 * deployment does not have to drop and recreate the index (Elasticsearch
 * allows adding fields, not changing existing ones).
 *
 * <p>
 * This SPI seam (exported, so the configuration-save hook in another bundle
 * can {@code @Reference} it) covers only the schema migration. Back-filling the
 * new fields with embeddings is the reembedding queue's responsibility (a
 * separate epic) and is intentionally out of scope here. The helper consults
 * {@code ExternalEmbeddingCapabilityGate} so it is never more permissive than
 * the index-creation path, and fails fast (throws) on a client error or an
 * unacknowledged response, because it runs as an explicit admin action.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@ProviderType
public interface SemanticTextEmbeddingIndexMigrationHelper {

	/**
	 * Adds one {@code semantic_text} field per (asset type, locale) referencing
	 * the resolved {@code inferenceId} to the given index.
	 *
	 * @param indexName the company index to migrate
	 * @param assetTypes the asset-type tokens; must match the index-creation
	 *        set
	 * @param locales the locales; must match the index-creation set
	 * @param inferenceId the Elasticsearch Inference Endpoint id
	 */
	public void addSemanticTextFields(
		String indexName, List<String> assetTypes, List<Locale> locales,
		String inferenceId);

}