/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.engine;

import java.util.List;

import org.osgi.annotation.versioning.ProviderType;

/**
 * @author Adam Brandizzi
 */
@ProviderType
public interface SearchEngineInformation {

	public String getClientVersionString();

	public List<ConnectionInformation> getConnectionInformationList();

	public int[] getEmbeddingVectorDimensions();

	/**
	 * Returns the search engine's {@code index.max_result_window} — the hard
	 * ceiling on {@code from + size} for a single request. Callers use it to
	 * reject deep-pagination requests before they reach the engine (LPD-64988).
	 */
	public int getMaxResultWindow();

	public String getNodesString();

	public String getVendorString();

}