/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.ml.embedding.text.helper;

import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the structured, labeled text a model document contributor sends for
 * embedding, one {@code <label>: <value>} line per field (for example {@code
 * Title: ...} and {@code Content/Body: ...}). Labels keep the field-type
 * semantics inside the single combined embedding, which embeds with better
 * relevance than a flat concatenation. A blank value contributes no line, so
 * an asset type that lacks a field (for example tags) does not emit an empty
 * label.
 *
 * @author Rodrigo Guedes de Souza
 */
public class SemanticTextContentBuilder {

	public SemanticTextContentBuilder append(String label, String value) {
		if (!Validator.isBlank(value)) {
			_labeledValues.add(
				StringBundler.concat(
					label, StringPool.COLON, StringPool.SPACE, value));
		}

		return this;
	}

	public String build() {
		return StringUtil.merge(_labeledValues, StringPool.NEW_LINE);
	}

	private final List<String> _labeledValues = new ArrayList<>();

}