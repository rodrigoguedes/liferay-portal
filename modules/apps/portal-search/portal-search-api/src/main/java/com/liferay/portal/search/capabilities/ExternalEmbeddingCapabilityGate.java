/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.capabilities;

import org.osgi.annotation.versioning.ProviderType;

/**
 * Answers whether the "Bring Your Own LLM for Text Embeddings (via
 * Elasticsearch)" feature can operate on the current installation. A single
 * {@link #check()} entry point evaluates every business precondition (DXP
 * Enterprise license, search engine vendor, Elasticsearch Inference API
 * license) and returns the verdict plus a localizable reason as one immutable
 * value, so callers never re-run the checks to learn why the feature is
 * unavailable.
 *
 * <p>
 * The feature flag is intentionally <em>not</em> a precondition here; it is
 * evaluated by the admin renderer that surfaces the BYO-LLM affordances, since
 * the consumers of this gate (mapping, contributor, query) are only reachable
 * when the flag is already on.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@ProviderType
public interface ExternalEmbeddingCapabilityGate {

	public ExternalEmbeddingEligibility check();

}