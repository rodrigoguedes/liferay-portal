/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.portal.search.internal.spi.model.query.contributor;

import com.liferay.portal.kernel.search.BooleanClauseOccur;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.search.filter.BooleanFilter;
import com.liferay.portal.kernel.search.filter.TermsFilter;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.search.internal.preview.PreviewSearchContext;
import com.liferay.portal.search.spi.model.query.contributor.ModelPreFilterContributor;
import com.liferay.portal.search.spi.model.registrar.ModelSearchSettings;

import java.util.Collection;
import java.util.Map;

import org.osgi.service.component.annotations.Component;

/**
 * @author Michael C. Han
 */
@Component(
	property = "model.pre.filter.contributor.id=WorkflowStatus",
	service = ModelPreFilterContributor.class
)
public class WorkflowStatusModelPreFilterContributor
	implements ModelPreFilterContributor {

	@Override
	public void contribute(
		BooleanFilter booleanFilter, ModelSearchSettings modelSearchSettings,
		SearchContext searchContext) {

		int[] statuses = _getStatuses(searchContext);

		// Preview Framework POC (LPD-92301): when a preview swap map is present
		// for this entry class, relax the workflow status filter so the
		// previewed draft versions are matched in place of their approved
		// counterparts. The swap must happen inside the query (not as a
		// post-search result swap) so that keyword searches match the draft
		// documents.

		Map<Long, Long> swapMap = PreviewSearchContext.getSwapMap(
			searchContext, modelSearchSettings.getClassName());

		if (swapMap.isEmpty()) {
			_contributeStatuses(booleanFilter, statuses);

			return;
		}

		_contributePreviewStatuses(booleanFilter, statuses, swapMap);
	}

	private void _contributePreviewStatuses(
		BooleanFilter booleanFilter, int[] statuses, Map<Long, Long> swapMap) {

		// MUST(
		//     OR(
		//         AND(<status filter>, NOT entryClassPK IN fromClassPKs),
		//         entryClassPK IN toClassPKs))

		BooleanFilter previewBooleanFilter = new BooleanFilter();

		// SHOULD #1: the regular status-filtered documents, minus the approved
		// versions being swapped out.

		BooleanFilter approvedBooleanFilter = new BooleanFilter();

		_contributeStatuses(approvedBooleanFilter, statuses);

		TermsFilter fromClassPKsTermsFilter = new TermsFilter(
			Field.ENTRY_CLASS_PK);

		fromClassPKsTermsFilter.addValues(_toStringArray(swapMap.keySet()));

		approvedBooleanFilter.add(
			fromClassPKsTermsFilter, BooleanClauseOccur.MUST_NOT);

		previewBooleanFilter.add(
			approvedBooleanFilter, BooleanClauseOccur.SHOULD);

		// SHOULD #2: the previewed draft documents being swapped in, regardless
		// of their workflow status.

		TermsFilter toClassPKsTermsFilter = new TermsFilter(
			Field.ENTRY_CLASS_PK);

		toClassPKsTermsFilter.addValues(_toStringArray(swapMap.values()));

		previewBooleanFilter.add(
			toClassPKsTermsFilter, BooleanClauseOccur.SHOULD);

		booleanFilter.add(previewBooleanFilter, BooleanClauseOccur.MUST);
	}

	private void _contributeStatuses(
		BooleanFilter booleanFilter, int[] statuses) {

		if (!ArrayUtil.contains(statuses, WorkflowConstants.STATUS_ANY)) {
			TermsFilter statusesTermsFilter = new TermsFilter(Field.STATUS);

			statusesTermsFilter.addValues(ArrayUtil.toStringArray(statuses));

			booleanFilter.add(statusesTermsFilter, BooleanClauseOccur.MUST);
		}
		else {
			booleanFilter.addTerm(
				Field.STATUS, String.valueOf(WorkflowConstants.STATUS_IN_TRASH),
				BooleanClauseOccur.MUST_NOT);
		}
	}

	private int[] _getStatuses(SearchContext searchContext) {
		int[] statuses = GetterUtil.getIntegerValues(
			searchContext.getAttribute(Field.STATUS), null);

		if (ArrayUtil.isEmpty(statuses)) {
			int status = GetterUtil.getInteger(
				searchContext.getAttribute(Field.STATUS),
				WorkflowConstants.STATUS_APPROVED);

			statuses = new int[] {status};
		}

		return statuses;
	}

	private String[] _toStringArray(Collection<Long> values) {
		String[] array = new String[values.size()];

		int i = 0;

		for (Long value : values) {
			array[i++] = String.valueOf(value);
		}

		return array;
	}

}
