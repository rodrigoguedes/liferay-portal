/**
 * SPDX-FileCopyrightText: (c) 2024 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.tools.benchmarks.task;

import com.liferay.petra.lang.SafeCloseable;
import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.util.ListUtil;
import com.liferay.portal.kernel.util.ObjectValuePair;
import com.liferay.portal.tools.benchmarks.http.HttpResponse;
import com.liferay.portal.tools.benchmarks.http.HttpUtil;
import com.liferay.portal.tools.benchmarks.http.ThreadLocalCookieStore;

import java.net.URL;
import java.net.URLEncoder;

import java.nio.charset.StandardCharsets;

import java.util.Base64;
import java.util.List;

import org.junit.Assert;

/**
 * LPD-97915: HTTP latency benchmark for the Search headless API. Measures the
 * Search headless API ({@code /o/search/v1.0/search}) at three representative
 * request shapes so the initiative's cost story is visible in the percentiles
 * (see {@link com.liferay.portal.tools.benchmarks.Statistics}): a shallow page,
 * a large page size, and a deep page.
 *
 * @author Rodrigo Guedes de Souza
 */
public class SearchBenchmarksTask implements BenchmarksTask {

	public SearchBenchmarksTask(
		String emailAddress, String hostname, String keywords, String password,
		int port) {

		_emailAddress = emailAddress;
		_hostname = hostname;
		_keywords = keywords;
		_password = password;
		_port = port;
	}

	@Override
	public List<ObjectValuePair<String, Long>> execute() throws Exception {
		try (SafeCloseable safeCloseable =
				ThreadLocalCookieStore.withSafeCloseable()) {

			String authorization =
				"Basic " +
					Base64.getEncoder(
					).encodeToString(
						StringBundler.concat(
							_emailAddress, StringPool.COLON, _password
						).getBytes(
							StandardCharsets.UTF_8
						)
					);

			return ListUtil.fromArray(
				new ObjectValuePair<>(
					"searchShallow", _search(authorization, 1, 20)),
				new ObjectValuePair<>(
					"searchLargePage", _search(authorization, 1, 200)),
				new ObjectValuePair<>(
					"searchDeepPage", _search(authorization, 100, 20)));
		}
	}

	private URL _createSearchURL(int page, int pageSize) throws Exception {
		return new URL(
			"http", _hostname, _port,
			StringBundler.concat(
				"/o/search/v1.0/search?search=",
				URLEncoder.encode(_keywords, StringPool.UTF8), "&page=", page,
				"&pageSize=", pageSize));
	}

	private long _search(String authorization, int page, int pageSize)
		throws Exception {

		HttpResponse httpResponse = HttpUtil.doGet(
			authorization, null, _createSearchURL(page, pageSize));

		Assert.assertEquals(200, httpResponse.getStatusCode());

		System.out.println(
			StringBundler.concat(
				"[LPD-97915][search] page=", page, " pageSize=", pageSize,
				" durationMs=", httpResponse.getDuration(), " responseBytes=",
				httpResponse.getLength()));

		return httpResponse.getDuration();
	}

	private final String _emailAddress;
	private final String _hostname;
	private final String _keywords;
	private final String _password;
	private final int _port;

}
