/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.searcher;

/**
 * Thrown when a search request's result window ({@code from + size}) exceeds the
 * engine's {@code index.max_result_window} (deep pagination past the physical
 * ceiling). It is raised for the original, user-facing request before the
 * permission filter slices it into amplification re-queries, so the guardrail
 * targets guest/attacker-reachable requests. Callers translate it into an empty
 * result (web) or an HTTP 400 (headless) — LPD-64988.
 *
 * @author Rodrigo Guedes de Souza
 */
public class SearchRequestWindowLimitExceededException
	extends RuntimeException {

	public SearchRequestWindowLimitExceededException(
		int start, int end, int maxResultWindow) {

		super(
			"The requested result window [" + start + ", " + end +
				") exceeds the maximum allowed depth " + maxResultWindow +
					" (index.max_result_window)");

		_start = start;
		_end = end;
		_maxResultWindow = maxResultWindow;
	}

	public int getEnd() {
		return _end;
	}

	public int getMaxResultWindow() {
		return _maxResultWindow;
	}

	public int getStart() {
		return _start;
	}

	private final int _end;
	private final int _maxResultWindow;
	private final int _start;

}
