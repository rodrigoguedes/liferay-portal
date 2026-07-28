/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.rest.internal.jaxrs.exception.mapper;

import com.liferay.portal.search.searcher.SearchRequestWindowLimitExceededException;
import com.liferay.portal.vulcan.jaxrs.exception.mapper.BaseExceptionMapper;
import com.liferay.portal.vulcan.jaxrs.exception.mapper.Problem;

import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import org.osgi.service.component.annotations.Component;

/**
 * Maps the deep-pagination guardrail exception to HTTP 400 for the headless
 * Search API: a request whose result window (from + size) exceeds the engine's
 * index.max_result_window is a bad request, not a silently degraded result
 * (LPD-64988, AC#3.3).
 *
 * @author Rodrigo Guedes de Souza
 */
@Component(
	property = {
		"osgi.jaxrs.application.select=(osgi.jaxrs.name=Liferay.Portal.Search.REST)",
		"osgi.jaxrs.extension=true",
		"osgi.jaxrs.name=Liferay.Portal.Search.REST.SearchRequestWindowLimitExceededExceptionMapper"
	},
	service = ExceptionMapper.class
)
@Provider
public class SearchRequestWindowLimitExceededExceptionMapper
	extends BaseExceptionMapper<SearchRequestWindowLimitExceededException> {

	@Override
	protected Problem getProblem(
		SearchRequestWindowLimitExceededException
			searchRequestWindowLimitExceededException) {

		return new Problem(searchRequestWindowLimitExceededException);
	}

}
