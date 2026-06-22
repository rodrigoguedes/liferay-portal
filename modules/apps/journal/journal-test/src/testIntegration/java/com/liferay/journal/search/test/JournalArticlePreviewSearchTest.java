/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.journal.search.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.journal.constants.JournalFolderConstants;
import com.liferay.journal.model.JournalArticle;
import com.liferay.journal.test.util.JournalTestUtil;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.search.Document;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.Hits;
import com.liferay.portal.kernel.search.Indexer;
import com.liferay.portal.kernel.search.IndexerRegistryUtil;
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.rule.DeleteAfterTestRun;
import com.liferay.portal.kernel.test.util.GroupTestUtil;
import com.liferay.portal.kernel.test.util.SearchContextTestUtil;
import com.liferay.portal.kernel.test.util.ServiceContextTestUtil;
import com.liferay.portal.kernel.test.util.TestPropsValues;
import com.liferay.portal.kernel.test.util.UserTestUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.HashMapBuilder;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;

import java.io.Serializable;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Preview Framework POC (LPD-92301): proves that the preview swap happens
 * inside the search query, by making {@code WorkflowStatusModelPreFilterContributor}
 * preview-aware.
 *
 * <p>
 * The swap key is {@link Field#ENTRY_CLASS_PK}. For a journal article that
 * field holds the resource primary key, which is shared across the versions of
 * a single article, so this test swaps two distinct articles. The "previewed"
 * article stands in for an unapproved version: a document that is indexed but
 * filtered out of regular searches because its status is not approved. Because
 * journal does not index a draft-only article (a never-approved article
 * produces no searchable document), the previewed article is created approved
 * and then expired. The query mechanism under test (relaxing the status filter
 * and including/excluding by entryClassPK) is identical for any non-approved
 * status. The realistic per-version swap (a distinct primary key per version)
 * is exercised by object entries and is the follow-up test.
 * </p>
 *
 * @author Rodrigo Guedes de Souza
 */
@RunWith(Arquillian.class)
public class JournalArticlePreviewSearchTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new LiferayIntegrationTestRule();

	@Before
	public void setUp() throws Exception {
		_group = GroupTestUtil.addGroup();

		_indexer = IndexerRegistryUtil.getIndexer(JournalArticle.class);

		UserTestUtil.setUser(TestPropsValues.getUser());

		// The live article carries a keyword present only in the approved
		// version.

		_liveArticle = JournalTestUtil.addArticleWithWorkflow(
			_group.getGroupId(),
			JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID,
			"Live Title " + _LIVE_KEYWORD, "content", true);

		// The previewed article carries a keyword present only in the
		// previewed version. It stands in for an unapproved version that is
		// indexed but filtered out of regular searches. It is created approved
		// (so it gets indexed) and then expired, since journal does not index a
		// draft-only article.

		_previewedArticle = JournalTestUtil.addArticleWithWorkflow(
			_group.getGroupId(),
			JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID,
			"Previewed Title " + _PREVIEWED_KEYWORD, "content", true);

		JournalTestUtil.expireArticle(_group.getGroupId(), _previewedArticle);
	}

	@Test
	public void testBaselineReturnsLiveOnly() throws Exception {

		// The live keyword matches the live article.

		_assertContainsClassPK(
			_liveArticle.getResourcePrimKey(), _search(_LIVE_KEYWORD, null));

		// The previewed keyword matches nothing: the previewed article is
		// filtered out by the default approved-only status filter.

		Assert.assertEquals(0, _search(_PREVIEWED_KEYWORD, null).getLength());
	}

	@Test
	public void testJournalArticleVersionIndexing() throws Exception {

		// The real journal preview case: one article approved (v1), then edited
		// and saved as a draft (v2) of the SAME article. This documents two
		// facts that prevent an entryClassPK-based preview swap for journal
		// articles:
		//
		// 1. All versions of an article share the same entryClassPK
		//    (= resourcePrimKey), so the swap cannot target a specific version
		//    by that key.
		// 2. The draft version is not indexed, so there is no target document
		//    to swap in.

		String approvedKeyword = "gammapreviewword";
		String draftKeyword = "deltapreviewword";

		JournalArticle article = JournalTestUtil.addArticleWithWorkflow(
			_group.getGroupId(),
			JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID,
			"V1 Title " + approvedKeyword, "content", true);

		// Edit and save as a draft (workflowEnabled = true, approved = false):
		// creates a new version with DRAFT status on the same resource as v1.

		JournalArticle draftArticle = JournalTestUtil.updateArticle(
			article, "V2 Title " + draftKeyword, article.getContent(), true,
			false, ServiceContextTestUtil.getServiceContext());

		// The draft is a new, distinct article version on the same resource.

		Assert.assertEquals(
			"The draft shares the resource primary key with the approved " +
				"version",
			article.getResourcePrimKey(), draftArticle.getResourcePrimKey());
		Assert.assertNotEquals(
			"The draft is a distinct article version", article.getId(),
			draftArticle.getId());
		Assert.assertEquals(
			"The new version is a draft", WorkflowConstants.STATUS_DRAFT,
			draftArticle.getStatus());

		// Fact 1: the only indexed document is the approved version, and its
		// entryClassPK is the shared resourcePrimKey, not a per-version key.

		Hits approvedHits = _searchAnyStatus(approvedKeyword);

		Assert.assertEquals(_dump(approvedHits), 1, approvedHits.getLength());

		Document approvedDocument = approvedHits.getDocs()[0];

		Assert.assertEquals(
			"Versions are keyed in the index by the shared resourcePrimKey",
			String.valueOf(article.getResourcePrimKey()),
			approvedDocument.get(Field.ENTRY_CLASS_PK));

		// Fact 2: the draft version is not indexed. Content that only exists in
		// the draft is not searchable, even with STATUS_ANY.

		Hits draftHits = _searchAnyStatus(draftKeyword);

		Assert.assertEquals(_dump(draftHits), 0, draftHits.getLength());
	}

	@Test
	public void testPreviewClearedRestoresLiveResults() throws Exception {
		Serializable swapMap = _swapMap(
			_liveArticle.getResourcePrimKey(),
			_previewedArticle.getResourcePrimKey());

		Assert.assertNotEquals(
			0, _search(_PREVIEWED_KEYWORD, swapMap).getLength());

		// Without the preview attribute the search returns to the baseline.

		Assert.assertEquals(0, _search(_PREVIEWED_KEYWORD, null).getLength());
	}

	@Test
	public void testPreviewedArticleIsIndexed() throws Exception {

		// Sanity check: the previewed (expired) article is indexed as its own
		// document. Searching its keyword with STATUS_ANY bypasses the workflow
		// status filter. The assertion message reports the indexed entryClassPK
		// and status for diagnostics.

		SearchContext searchContext = SearchContextTestUtil.getSearchContext(
			_group.getGroupId());

		searchContext.setAttribute(Field.STATUS, WorkflowConstants.STATUS_ANY);
		searchContext.setKeywords(_PREVIEWED_KEYWORD);

		Hits hits = _indexer.search(searchContext);

		Assert.assertTrue(
			_describe(hits), _containsClassPK(
				_previewedArticle.getResourcePrimKey(), hits));
	}

	@Test
	public void testPreviewSwapsLiveForPreviewed() throws Exception {
		Serializable swapMap = _swapMap(
			_liveArticle.getResourcePrimKey(),
			_previewedArticle.getResourcePrimKey());

		// The previewed keyword now matches: the swap is applied inside the
		// query, so the previewed document is included despite its non-approved
		// status.

		Hits previewedHits = _search(_PREVIEWED_KEYWORD, swapMap);

		Assert.assertTrue(
			_describe(previewedHits),
			_containsClassPK(
				_previewedArticle.getResourcePrimKey(), previewedHits));

		// The live version being swapped out is excluded.

		_assertNotContainsClassPK(
			_liveArticle.getResourcePrimKey(), _search(_LIVE_KEYWORD, swapMap));
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

		sb.append("liveResourcePrimKey=");
		sb.append(_liveArticle.getResourcePrimKey());
		sb.append(" previewedResourcePrimKey=");
		sb.append(_previewedArticle.getResourcePrimKey());
		sb.append(" hitsLength=");
		sb.append(hits.getLength());

		for (Document document : hits.getDocs()) {
			sb.append(" [entryClassPK=");
			sb.append(document.get(Field.ENTRY_CLASS_PK));
			sb.append(" status=");
			sb.append(document.get(Field.STATUS));
			sb.append("]");
		}

		return sb.toString();
	}

	private String _dump(Hits hits) {
		StringBuilder sb = new StringBuilder();

		sb.append("len=");
		sb.append(hits.getLength());

		for (Document document : hits.getDocs()) {
			sb.append(" {entryClassPK=");
			sb.append(document.get(Field.ENTRY_CLASS_PK));
			sb.append(" version=");
			sb.append(document.get(Field.VERSION));
			sb.append(" status=");
			sb.append(document.get(Field.STATUS));
			sb.append(" uid=");
			sb.append(document.get(Field.UID));
			sb.append("}");
		}

		return sb.toString();
	}

	private Hits _search(String keywords, Serializable swapMap)
		throws Exception {

		SearchContext searchContext = SearchContextTestUtil.getSearchContext(
			_group.getGroupId());

		searchContext.setKeywords(keywords);

		if (swapMap != null) {

			// Mock of the Preview Context Model. The attribute name mirrors
			// PreviewSearchContext.ATTRIBUTE (kept as a literal here because
			// that class lives in an internal package of portal-search).

			searchContext.setAttribute("preview.swap.map", swapMap);
		}

		return _indexer.search(searchContext);
	}

	private Hits _searchAnyStatus(String keywords) throws Exception {
		SearchContext searchContext = SearchContextTestUtil.getSearchContext(
			_group.getGroupId());

		searchContext.setKeywords(keywords);
		searchContext.setAttribute(Field.STATUS, WorkflowConstants.STATUS_ANY);

		return _indexer.search(searchContext);
	}

	private Serializable _swapMap(long fromClassPK, long toClassPK) {
		return (Serializable)HashMapBuilder.<String, Serializable>put(
			JournalArticle.class.getName(),
			(Serializable)HashMapBuilder.<Long, Long>put(
				fromClassPK, toClassPK
			).build()
		).build();
	}

	private static final String _LIVE_KEYWORD = "alphapreviewword";

	private static final String _PREVIEWED_KEYWORD = "betapreviewword";

	@DeleteAfterTestRun
	private Group _group;

	private Indexer<JournalArticle> _indexer;

	@DeleteAfterTestRun
	private JournalArticle _liveArticle;

	@DeleteAfterTestRun
	private JournalArticle _previewedArticle;

}
