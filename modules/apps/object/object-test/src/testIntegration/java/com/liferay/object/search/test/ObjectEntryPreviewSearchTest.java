/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.object.search.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.object.constants.ObjectDefinitionConstants;
import com.liferay.object.constants.ObjectEntryFolderConstants;
import com.liferay.object.field.builder.TextObjectFieldBuilder;
import com.liferay.object.model.ObjectDefinition;
import com.liferay.object.model.ObjectEntry;
import com.liferay.object.service.ObjectDefinitionLocalService;
import com.liferay.object.service.ObjectEntryLocalService;
import com.liferay.object.test.util.ObjectDefinitionTestUtil;
import com.liferay.portal.kernel.search.Document;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.Hits;
import com.liferay.portal.kernel.search.Indexer;
import com.liferay.portal.kernel.search.IndexerRegistryUtil;
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.rule.SynchronousDestinationTestRule;
import com.liferay.portal.kernel.test.util.SearchContextTestUtil;
import com.liferay.portal.kernel.test.util.ServiceContextTestUtil;
import com.liferay.portal.kernel.test.util.TestPropsValues;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.HashMapBuilder;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.test.rule.FeatureFlag;
import com.liferay.portal.test.rule.FeatureFlags;
import com.liferay.portal.test.rule.Inject;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;
import com.liferay.portal.test.rule.PermissionCheckerMethodTestRule;
import com.liferay.portal.vulcan.util.LocalizedMapUtil;

import java.io.Serializable;

import java.util.Collections;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Preview Framework POC (LPD-92301): proves the preview swap end to end on the
 * search side for object entries.
 *
 * <p>
 * With drafts and versioning enabled (and feature flag LPD-17564), an entry
 * that has a draft is represented by two indexed documents with distinct
 * primary keys: the draft head and a "latest approved" snapshot row. This is
 * the {@code fromClassPK → toClassPK} pair the Preview Context Model needs.
 * </p>
 *
 * <p>
 * Both documents are force-reindexed in {@link #setUp()}. The test then runs a
 * search with the preview swap map carried as a {@code SearchContext} attribute
 * (mirroring {@code PreviewSearchContext.ATTRIBUTE}) and asserts that the
 * preview-aware {@code WorkflowStatusModelPreFilterContributor} swaps the
 * approved snapshot out for the draft inside the query.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@FeatureFlags(
	featureFlags = {
		@FeatureFlag("LPD-17564"), @FeatureFlag("LPD-34594")
	}
)
@RunWith(Arquillian.class)
public class ObjectEntryPreviewSearchTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new AggregateTestRule(
			new LiferayIntegrationTestRule(),
			PermissionCheckerMethodTestRule.INSTANCE,
			SynchronousDestinationTestRule.INSTANCE);

	@Before
	public void setUp() throws Exception {
		_objectDefinition = ObjectDefinitionTestUtil.publishObjectDefinition(
			Collections.singletonList(
				new TextObjectFieldBuilder(
				).indexed(
					true
				).labelMap(
					LocalizedMapUtil.getLocalizedMap("Name")
				).name(
					"name"
				).build()),
			ObjectDefinitionConstants.SCOPE_COMPANY);

		_objectDefinition.setEnableObjectEntryDraft(true);
		_objectDefinition.setEnableObjectEntryVersioning(true);

		_objectDefinition = _objectDefinitionLocalService.updateObjectDefinition(
			_objectDefinition);

		_indexer = IndexerRegistryUtil.nullSafeGetIndexer(
			_objectDefinition.getClassName());

		// Approved entry: the live version, keyword only in the approved
		// content.

		ServiceContext publishServiceContext =
			ServiceContextTestUtil.getServiceContext();

		publishServiceContext.setWorkflowAction(
			WorkflowConstants.ACTION_PUBLISH);

		ObjectEntry objectEntry = _objectEntryLocalService.addObjectEntry(
			0, TestPropsValues.getUserId(),
			_objectDefinition.getObjectDefinitionId(),
			ObjectEntryFolderConstants.PARENT_OBJECT_ENTRY_FOLDER_ID_DEFAULT,
			null,
			HashMapBuilder.<String, Serializable>put(
				"name", _LIVE_KEYWORD
			).build(),
			publishServiceContext);

		_headObjectEntryId = objectEntry.getObjectEntryId();

		// Draft version: the previewed version, keyword only in the draft. The
		// head keeps the same primary key; the version listener creates a
		// separate "latest approved" snapshot row (distinct primary key).

		ServiceContext draftServiceContext =
			ServiceContextTestUtil.getServiceContext();

		draftServiceContext.setWorkflowAction(
			WorkflowConstants.ACTION_SAVE_DRAFT);

		_objectEntryLocalService.updateObjectEntry(
			TestPropsValues.getUserId(), _headObjectEntryId,
			ObjectEntryFolderConstants.PARENT_OBJECT_ENTRY_FOLDER_ID_DEFAULT,
			HashMapBuilder.<String, Serializable>put(
				"name", _PREVIEWED_KEYWORD
			).build(),
			draftServiceContext);

		ObjectEntry latestApprovedSnapshot =
			_objectEntryLocalService.fetchObjectEntryByHeadObjectEntryId(
				_headObjectEntryId);

		_snapshotObjectEntryId = latestApprovedSnapshot.getObjectEntryId();

		// The approved snapshot is not indexed automatically (its creator is not
		// @Indexable). Force both documents into the index so the swap has two
		// distinct-primary-key documents to operate on.

		_indexer.reindex(
			_objectDefinition.getClassName(), _headObjectEntryId);
		_indexer.reindex(
			_objectDefinition.getClassName(), _snapshotObjectEntryId);
	}

	@After
	public void tearDown() throws Exception {
		if (_objectDefinition != null) {
			_objectDefinitionLocalService.deleteObjectDefinition(
				_objectDefinition);
		}
	}

	@Test
	public void testBaselineReturnsApprovedSnapshotOnly() throws Exception {

		// The approved snapshot matches the live keyword.

		_assertContainsClassPK(
			_snapshotObjectEntryId, _search(_LIVE_KEYWORD, null));

		// The previewed keyword matches nothing: the draft head is filtered out
		// by the default approved-only status filter.

		Assert.assertEquals(
			_describe(_search(_PREVIEWED_KEYWORD, null)), 0,
			_searchLength(_PREVIEWED_KEYWORD, null));
	}

	@Test
	public void testPreviewSwapsApprovedForDraft() throws Exception {

		// from = approved snapshot (live), to = draft head (previewed).

		Serializable swapMap = _swapMap(
			_snapshotObjectEntryId, _headObjectEntryId);

		// The previewed keyword now matches: the swap is applied inside the
		// query, so the draft head is included despite its draft status.

		_assertContainsClassPK(
			_headObjectEntryId, _search(_PREVIEWED_KEYWORD, swapMap));

		// The approved snapshot being swapped out is excluded.

		_assertNotContainsClassPK(
			_snapshotObjectEntryId, _search(_LIVE_KEYWORD, swapMap));
	}

	private void _assertContainsClassPK(long classPK, Hits hits) {
		Assert.assertTrue(_describe(hits), _containsClassPK(classPK, hits));
	}

	private void _assertNotContainsClassPK(long classPK, Hits hits) {
		Assert.assertFalse(_describe(hits), _containsClassPK(classPK, hits));
	}

	private boolean _containsClassPK(long classPK, Hits hits) {
		for (Document document : hits.getDocs()) {
			long entryClassPK = GetterUtil.getLong(
				document.get(Field.ENTRY_CLASS_PK));

			if (classPK == entryClassPK) {
				return true;
			}
		}

		return false;
	}

	private String _describe(Hits hits) {
		StringBuilder sb = new StringBuilder();

		sb.append("headObjectEntryId=");
		sb.append(_headObjectEntryId);
		sb.append(" snapshotObjectEntryId=");
		sb.append(_snapshotObjectEntryId);
		sb.append(" hitsLength=");
		sb.append(hits.getLength());

		for (Document document : hits.getDocs()) {
			sb.append(" {pk=");
			sb.append(document.get(Field.ENTRY_CLASS_PK));
			sb.append(" status=");
			sb.append(document.get(Field.STATUS));
			sb.append("}");
		}

		return sb.toString();
	}

	private Hits _search(String keywords, Serializable swapMap)
		throws Exception {

		SearchContext searchContext = SearchContextTestUtil.getSearchContext(0L);

		searchContext.setKeywords(keywords);

		if (swapMap != null) {

			// Mock of the Preview Context Model. The attribute name mirrors
			// PreviewSearchContext.ATTRIBUTE (kept as a literal here because
			// that class lives in an internal package of portal-search).

			searchContext.setAttribute("preview.swap.map", swapMap);
		}

		return _indexer.search(searchContext);
	}

	private int _searchLength(String keywords, Serializable swapMap)
		throws Exception {

		Hits hits = _search(keywords, swapMap);

		return hits.getLength();
	}

	private Serializable _swapMap(long fromClassPK, long toClassPK) {
		Serializable classPKSwapMap =
			(Serializable)HashMapBuilder.<Long, Long>put(
				fromClassPK, toClassPK
			).build();

		return (Serializable)HashMapBuilder.<String, Serializable>put(
			_objectDefinition.getClassName(), classPKSwapMap
		).put(
			ObjectEntry.class.getName(), classPKSwapMap
		).build();
	}

	private static final String _LIVE_KEYWORD = "alphapreviewword";

	private static final String _PREVIEWED_KEYWORD = "betapreviewword";

	private long _headObjectEntryId;

	@Inject
	private ObjectDefinitionLocalService _objectDefinitionLocalService;

	private ObjectDefinition _objectDefinition;

	@Inject
	private ObjectEntryLocalService _objectEntryLocalService;

	private Indexer<ObjectEntry> _indexer;

	private long _snapshotObjectEntryId;

}
