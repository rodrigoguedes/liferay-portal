/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.capabilities;

/**
 * Immutable result of {@link ExternalEmbeddingCapabilityGate#check()}. Carries
 * both the availability verdict and, when unavailable, the language key of the
 * reason. Obtaining availability and reason from the same value removes the
 * double-evaluation risk of a two-method {@code isAvailable()} / {@code
 * reason()} contract.
 *
 * @author Rodrigo Guedes de Souza
 */
public class ExternalEmbeddingEligibility {

	public static ExternalEmbeddingEligibility available() {
		return _AVAILABLE;
	}

	public static ExternalEmbeddingEligibility unavailable(String reason) {
		return new ExternalEmbeddingEligibility(false, reason);
	}

	public String getReason() {
		return _reason;
	}

	public boolean isAvailable() {
		return _available;
	}

	private ExternalEmbeddingEligibility(boolean available, String reason) {
		_available = available;
		_reason = reason;
	}

	private static final ExternalEmbeddingEligibility _AVAILABLE =
		new ExternalEmbeddingEligibility(true, null);

	private final boolean _available;
	private final String _reason;

}