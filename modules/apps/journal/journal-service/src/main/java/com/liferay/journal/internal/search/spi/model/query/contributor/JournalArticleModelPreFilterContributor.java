/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.journal.internal.search.spi.model.query.contributor;

import com.liferay.dynamic.data.mapping.model.DDMStructure;
import com.liferay.dynamic.data.mapping.service.DDMStructureLocalService;
import com.liferay.dynamic.data.mapping.util.DDMIndexer;
import com.liferay.journal.model.JournalArticle;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.search.BooleanClauseOccur;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.search.filter.BooleanFilter;
import com.liferay.portal.kernel.search.filter.QueryFilter;
import com.liferay.portal.kernel.search.filter.TermsFilter;
import com.liferay.portal.kernel.service.ClassNameLocalService;
import com.liferay.portal.kernel.service.GroupLocalService;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.FastDateFormatFactoryUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.MapUtil;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.kernel.util.PropsKeys;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.preview.PreviewableResolverUtil;
import com.liferay.portal.search.asset.AssetSubtypeIdentifier;
import com.liferay.portal.search.filter.DateRangeFilterBuilder;
import com.liferay.portal.search.filter.FilterBuilders;
import com.liferay.portal.search.spi.model.query.contributor.ModelPreFilterContributor;
import com.liferay.portal.search.spi.model.registrar.ModelSearchSettings;

import java.io.Serializable;

import java.text.Format;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Lourdes Fernández Besada
 */
@Component(
	property = "indexer.class.name=com.liferay.journal.model.JournalArticle",
	service = ModelPreFilterContributor.class
)
public class JournalArticleModelPreFilterContributor
	implements ModelPreFilterContributor {

	@Override
	public void contribute(
		BooleanFilter booleanFilter, ModelSearchSettings modelSearchSettings,
		SearchContext searchContext) {

		// LPD-92301 Preview Framework POC: when a preview swap map is present
		// for JournalArticle, replace the normal "status=approved AND head"
		// restriction with a relaxed clause that excludes the approved/live
		// version (fromClassPK) and includes the specific draft version
		// (toClassPK), keyed on Field.UID (all versions of a JournalArticle
		// share entryClassPK = resourcePrimKey, so the per-version UID is the
		// only safe key). The swap map comes from the preview context on the
		// current thread (PreviewableResolverUtil), the same source the
		// service-layer PreviewableAdvice reads.

		Map<Serializable, Serializable> previewSwaps =
			PreviewableResolverUtil.getPreviewableMap(JournalArticle.class);

		if (MapUtil.isEmpty(previewSwaps)) {
			_workflowStatusModelPreFilterContributor.contribute(
				booleanFilter, modelSearchSettings, searchContext);
		}
		else {
			_contributePreviewSwapFilter(booleanFilter, previewSwaps);
		}

		Long classNameId = (Long)searchContext.getAttribute(
			Field.CLASS_NAME_ID);

		if ((classNameId != null) && (classNameId != 0)) {
			booleanFilter.addRequiredTerm(
				Field.CLASS_NAME_ID, classNameId.toString());
		}

		long[] classTypeIds = searchContext.getClassTypeIds();

		if (ArrayUtil.isNotEmpty(classTypeIds)) {
			TermsFilter classTypeIdsTermsFilter = new TermsFilter(
				Field.CLASS_TYPE_ID);

			classTypeIdsTermsFilter.addValues(
				ArrayUtil.toStringArray(classTypeIds));

			booleanFilter.add(classTypeIdsTermsFilter, BooleanClauseOccur.MUST);
		}

		String ddmStructureFieldName = (String)searchContext.getAttribute(
			"ddmStructureFieldName");
		Serializable ddmStructureFieldValue = searchContext.getAttribute(
			"ddmStructureFieldValue");

		if (Validator.isNotNull(ddmStructureFieldName) &&
			Validator.isNotNull(ddmStructureFieldValue)) {

			Locale locale = LocaleUtil.getMostRelevantLocale();

			try {
				QueryFilter queryFilter =
					_ddmIndexer.createFieldValueQueryFilter(
						ddmStructureFieldName, ddmStructureFieldValue, locale);

				booleanFilter.add(queryFilter, BooleanClauseOccur.MUST);
			}
			catch (Exception exception) {
				if (_log.isDebugEnabled()) {
					_log.debug(exception);
				}
			}
		}

		String ddmStructureKey = (String)searchContext.getAttribute(
			"ddmStructureKey");

		if (Validator.isNotNull(ddmStructureKey)) {
			booleanFilter.addRequiredTerm("ddmStructureKey", ddmStructureKey);
		}

		HashMap<String, List<AssetSubtypeIdentifier>>
			assetSubtypeIdentifiersMap =
				(HashMap<String, List<AssetSubtypeIdentifier>>)
					searchContext.getAttribute("assetSubtypeIdentifiersMap");

		if ((assetSubtypeIdentifiersMap != null) &&
			assetSubtypeIdentifiersMap.containsKey(
				JournalArticle.class.getName())) {

			BooleanFilter subtypeBooleanFilter = new BooleanFilter();

			List<AssetSubtypeIdentifier> assetSubtypeIdentifiers =
				assetSubtypeIdentifiersMap.get(JournalArticle.class.getName());

			for (AssetSubtypeIdentifier assetSubtypeIdentifier :
					assetSubtypeIdentifiers) {

				try {
					Group group =
						_groupLocalService.getGroupByExternalReferenceCode(
							assetSubtypeIdentifier.
								getGroupExternalReferenceCode(),
							searchContext.getCompanyId());

					DDMStructure ddmStructure =
						_ddmStructureLocalService.
							fetchStructureByExternalReferenceCode(
								assetSubtypeIdentifier.
									getSubtypeExternalReferenceCode(),
								group.getGroupId(),
								_classNameLocalService.getClassNameId(
									JournalArticle.class));

					subtypeBooleanFilter.addTerm(
						"ddmStructureKey", ddmStructure.getStructureKey());
				}
				catch (Exception exception) {
					if (_log.isDebugEnabled()) {
						_log.debug("Unable to add subtype filter", exception);
					}
				}
			}

			if (subtypeBooleanFilter.hasClauses()) {
				booleanFilter.add(
					subtypeBooleanFilter, BooleanClauseOccur.MUST);
			}
		}

		String ddmTemplateKey = (String)searchContext.getAttribute(
			"ddmTemplateKey");

		if (Validator.isNotNull(ddmTemplateKey)) {
			booleanFilter.addRequiredTerm("ddmTemplateKey", ddmTemplateKey);
		}

		boolean head = GetterUtil.getBoolean(
			searchContext.getAttribute("head"), Boolean.TRUE);
		boolean headOrShowNonindexable = GetterUtil.getBoolean(
			searchContext.getAttribute("headOrShowNonindexable"));
		boolean latest = GetterUtil.getBoolean(
			searchContext.getAttribute("latest"));
		boolean relatedClassName = GetterUtil.getBoolean(
			searchContext.getAttribute("relatedClassName"));
		boolean showNonindexable = GetterUtil.getBoolean(
			searchContext.getAttribute("showNonindexable"));

		// Preview mode already added the combined status + head clause in
		// _contributePreviewSwapFilter. Suppress the normal head/latest filter
		// so the previewed draft (head=false) is not excluded again.

		if (MapUtil.isNotEmpty(previewSwaps)) {
			head = false;
			headOrShowNonindexable = false;
			latest = false;
			showNonindexable = false;
		}

		if (latest && !relatedClassName && !showNonindexable) {
			booleanFilter.addRequiredTerm("latest", Boolean.TRUE);
		}
		else if (head && !headOrShowNonindexable && !relatedClassName &&
				 !showNonindexable) {

			booleanFilter.addRequiredTerm("head", Boolean.TRUE);
		}

		if (latest && !relatedClassName && showNonindexable) {
			booleanFilter.addRequiredTerm("latest", Boolean.TRUE);
		}
		else if (!relatedClassName && showNonindexable) {
			booleanFilter.addRequiredTerm("headListable", Boolean.TRUE);
		}
		else if (headOrShowNonindexable && !relatedClassName) {
			booleanFilter.add(
				new BooleanFilter() {
					{
						addTerm("head", Boolean.TRUE);
						addTerm("headListable", Boolean.TRUE);
					}
				},
				BooleanClauseOccur.MUST);
		}

		boolean filterExpired = GetterUtil.getBoolean(
			searchContext.getAttribute("filterExpired"));

		if (!filterExpired) {
			return;
		}

		DateRangeFilterBuilder dateRangeFilterBuilder =
			_filterBuilders.dateRangeFilterBuilder();

		dateRangeFilterBuilder.setFieldName(Field.EXPIRATION_DATE);

		String formatPattern = PropsUtil.get(
			PropsKeys.INDEX_DATE_FORMAT_PATTERN);

		dateRangeFilterBuilder.setFormat(formatPattern);

		Format dateFormat = FastDateFormatFactoryUtil.getSimpleDateFormat(
			formatPattern);

		dateRangeFilterBuilder.setFrom(dateFormat.format(new Date()));

		dateRangeFilterBuilder.setIncludeLower(false);
		dateRangeFilterBuilder.setIncludeUpper(false);

		booleanFilter.add(dateRangeFilterBuilder.build());
	}

	private void _contributePreviewSwapFilter(
		BooleanFilter booleanFilter,
		Map<Serializable, Serializable> previewSwaps) {

		TermsFilter fromUIDsTermsFilter = new TermsFilter(Field.UID);
		TermsFilter toUIDsTermsFilter = new TermsFilter(Field.UID);

		for (Map.Entry<Serializable, Serializable> entry :
				previewSwaps.entrySet()) {

			fromUIDsTermsFilter.addValue(_getUID(entry.getKey()));
			toUIDsTermsFilter.addValue(_getUID(entry.getValue()));
		}

		// Keep all approved/head/live content except the previewed-away
		// versions...

		BooleanFilter liveBooleanFilter = new BooleanFilter();

		liveBooleanFilter.addRequiredTerm(
			Field.STATUS, WorkflowConstants.STATUS_APPROVED);
		liveBooleanFilter.addRequiredTerm("head", Boolean.TRUE);
		liveBooleanFilter.add(fromUIDsTermsFilter, BooleanClauseOccur.MUST_NOT);

		// ...OR include the specific draft versions being previewed.

		BooleanFilter previewBooleanFilter = new BooleanFilter();

		previewBooleanFilter.add(liveBooleanFilter, BooleanClauseOccur.SHOULD);
		previewBooleanFilter.add(toUIDsTermsFilter, BooleanClauseOccur.SHOULD);

		booleanFilter.add(previewBooleanFilter, BooleanClauseOccur.MUST);
	}

	private String _getUID(Serializable classPK) {

		// Mirrors UIDFactoryImpl production UID format
		// (modelClassName + "_PORTLET_" + primaryKey).

		return JournalArticle.class.getName() + "_PORTLET_" + classPK;
	}

	private static final Log _log = LogFactoryUtil.getLog(
		JournalArticleModelPreFilterContributor.class);

	@Reference
	private ClassNameLocalService _classNameLocalService;

	@Reference
	private DDMIndexer _ddmIndexer;

	@Reference
	private DDMStructureLocalService _ddmStructureLocalService;

	@Reference
	private FilterBuilders _filterBuilders;

	@Reference
	private GroupLocalService _groupLocalService;

	@Reference
	private Portal _portal;

	@Reference(target = "(model.pre.filter.contributor.id=WorkflowStatus)")
	private ModelPreFilterContributor _workflowStatusModelPreFilterContributor;

}