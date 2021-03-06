/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.oauth2.provider.rest.internal.endpoint.filter;

import com.liferay.oauth2.provider.rest.internal.endpoint.constants.OAuth2ProviderRestEndpointConstants;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;

import org.osgi.service.component.annotations.Component;

/**
 * @author Tomas Polesovsky
 */
@Component(
	immediate = true,
	property = OAuth2ProviderRestEndpointConstants.PROPERTY_KEY_OAUTH2_ENDPOINT_JAXRS_PROVIDER + "=true",
	service = Object.class
)
@Provider
public class OAuth2NoCacheFilter implements ContainerResponseFilter {

	@Override
	public void filter(
		ContainerRequestContext requestContext,
		ContainerResponseContext responseContext) {

		MultivaluedMap<String, Object> headers = responseContext.getHeaders();

		headers.add("Cache-Control", "no-cache, no-store, must-revalidate");
		headers.add("Expires", "0");
		headers.add("Pragma", "no-cache");
	}

}