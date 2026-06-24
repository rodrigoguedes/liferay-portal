/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.journal.service.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.journal.constants.JournalFolderConstants;
import com.liferay.journal.exception.NoSuchArticleException;
import com.liferay.journal.model.JournalArticle;
import com.liferay.journal.model.JournalFolder;
import com.liferay.journal.service.JournalArticleLocalService;
import com.liferay.journal.service.JournalFolderLocalService;
import com.liferay.journal.test.util.JournalFolderFixture;
import com.liferay.journal.test.util.JournalTestUtil;
import com.liferay.petra.lang.SafeCloseable;
import com.liferay.portal.kernel.cache.thread.local.Lifecycle;
import com.liferay.portal.kernel.cache.thread.local.ThreadLocalCacheManager;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.util.GroupTestUtil;
import com.liferay.portal.preview.PreviewableResolverUtil;
import com.liferay.portal.test.rule.Inject;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;

import java.lang.reflect.UndeclaredThrowableException;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author Shuyang Zhou
 */
@RunWith(Arquillian.class)
public class JournalArticleLocalServicePreviewTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new LiferayIntegrationTestRule();

	@Before
	public void setUp() throws Exception {
		_group = GroupTestUtil.addGroup();

		_journalArticle1 = JournalTestUtil.addArticle(
			_group.getGroupId(),
			JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID);

		_journalArticle2 = JournalTestUtil.addArticle(
			_group.getGroupId(),
			JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID);

		_journalFolder = _journalFolderFixture.addFolder(
			_group.getGroupId(), "PREVIEW");

		_journalArticle3 = JournalTestUtil.addArticle(
			_group.getGroupId(), _journalFolder.getFolderId());

		_journalArticle4 = JournalTestUtil.addArticle(
			_group.getGroupId(), _journalFolder.getFolderId());
	}

	@Test
	public void testListJournalArticles() {

		// Outside preview

		Assert.assertEquals(
			Arrays.asList(_journalArticle1, _journalArticle2),
			_journalArticleLocalService.getArticles(
				_group.getGroupId(),
				JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID));
		Assert.assertEquals(
			Arrays.asList(_journalArticle3, _journalArticle4),
			_journalArticleLocalService.getArticles(
				_group.getGroupId(), _journalFolder.getFolderId()));

		// Nonexistent preview

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(-1L)) {

			Assert.assertEquals(
				Arrays.asList(_journalArticle1, _journalArticle2),
				_journalArticleLocalService.getArticles(
					_group.getGroupId(),
					JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID));
			Assert.assertEquals(
				Arrays.asList(_journalArticle3, _journalArticle4),
				_journalArticleLocalService.getArticles(
					_group.getGroupId(), _journalFolder.getFolderId()));
		}

		// Empty preview

		Long previewId1 = PreviewableResolverUtil.addPreviewableMap(
			Collections.emptyMap());

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId1)) {

			Assert.assertEquals(
				Arrays.asList(_journalArticle1, _journalArticle2),
				_journalArticleLocalService.getArticles(
					_group.getGroupId(),
					JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID));
			Assert.assertEquals(
				Arrays.asList(_journalArticle3, _journalArticle4),
				_journalArticleLocalService.getArticles(
					_group.getGroupId(), _journalFolder.getFolderId()));
		}

		// Preview with empty model class mapping

		Long previewId2 = PreviewableResolverUtil.addPreviewableMap(
			Map.of(JournalArticle.class, Collections.emptyMap()));

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId2)) {

			Assert.assertEquals(
				Arrays.asList(_journalArticle1, _journalArticle2),
				_journalArticleLocalService.getArticles(
					_group.getGroupId(),
					JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID));
			Assert.assertEquals(
				Arrays.asList(_journalArticle3, _journalArticle4),
				_journalArticleLocalService.getArticles(
					_group.getGroupId(), _journalFolder.getFolderId()));
		}

		// Preview _journalArticle1 -> _journalArticle3

		Long previewId3 = PreviewableResolverUtil.addPreviewableMap(
			Map.of(
				JournalArticle.class,
				Map.of(_journalArticle1.getId(), _journalArticle3.getId())));

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId3)) {

			Assert.assertEquals(
				Arrays.asList(_journalArticle3, _journalArticle2),
				_journalArticleLocalService.getArticles(
					_group.getGroupId(),
					JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID));
			Assert.assertEquals(
				Arrays.asList(_journalArticle3, _journalArticle4),
				_journalArticleLocalService.getArticles(
					_group.getGroupId(), _journalFolder.getFolderId()));
		}

		// Preview _journalArticle2 -> _journalArticle4

		Long previewId4 = PreviewableResolverUtil.addPreviewableMap(
			Map.of(
				JournalArticle.class,
				Map.of(_journalArticle2.getId(), _journalArticle4.getId())));

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId4)) {

			Assert.assertEquals(
				Arrays.asList(_journalArticle1, _journalArticle4),
				_journalArticleLocalService.getArticles(
					_group.getGroupId(),
					JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID));
			Assert.assertEquals(
				Arrays.asList(_journalArticle3, _journalArticle4),
				_journalArticleLocalService.getArticles(
					_group.getGroupId(), _journalFolder.getFolderId()));
		}

		// Preview with missing target

		Long previewId5 = PreviewableResolverUtil.addPreviewableMap(
			Map.of(
				JournalArticle.class,
				Map.of(
					_journalArticle1.getId(), -1L, _journalArticle2.getId(),
					-2L)));

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId5)) {

			_journalArticleLocalService.getArticles(
				_group.getGroupId(),
				JournalFolderConstants.DEFAULT_PARENT_FOLDER_ID);

			Assert.fail();
		}
		catch (UndeclaredThrowableException undeclaredThrowableException) {
			Throwable throwable = undeclaredThrowableException.getCause();

			Assert.assertSame(
				NoSuchArticleException.class, throwable.getClass());
			Assert.assertEquals(
				"No JournalArticle exists with the primary key -1",
				throwable.getMessage());

			Throwable[] throwables = throwable.getSuppressed();

			Assert.assertEquals(
				Arrays.toString(throwables), 1, throwables.length);

			throwable = throwables[0];

			Assert.assertSame(
				NoSuchArticleException.class, throwable.getClass());
			Assert.assertEquals(
				"No JournalArticle exists with the primary key -2",
				throwable.getMessage());
		}

		PreviewableResolverUtil.removePreviewableMap(previewId1);
		PreviewableResolverUtil.removePreviewableMap(previewId2);
		PreviewableResolverUtil.removePreviewableMap(previewId3);
		PreviewableResolverUtil.removePreviewableMap(previewId4);
		PreviewableResolverUtil.removePreviewableMap(previewId5);
	}

	@Test
	public void testSingleJournalArticle() {

		// Outside preview

		Assert.assertEquals(
			_journalArticle1,
			_journalArticleLocalService.fetchArticle(
				_group.getGroupId(), _journalArticle1.getArticleId()));
		Assert.assertEquals(
			_journalArticle2,
			_journalArticleLocalService.fetchArticle(
				_group.getGroupId(), _journalArticle2.getArticleId()));
		Assert.assertEquals(
			_journalArticle3,
			_journalArticleLocalService.fetchArticle(
				_group.getGroupId(), _journalArticle3.getArticleId()));
		Assert.assertEquals(
			_journalArticle4,
			_journalArticleLocalService.fetchArticle(
				_group.getGroupId(), _journalArticle4.getArticleId()));

		// Nonexistent preview

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(-1L)) {

			Assert.assertEquals(
				_journalArticle1,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle1.getArticleId()));
			Assert.assertEquals(
				_journalArticle2,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle2.getArticleId()));
			Assert.assertEquals(
				_journalArticle3,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle3.getArticleId()));
			Assert.assertEquals(
				_journalArticle4,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle4.getArticleId()));
		}

		// Empty preview

		Long previewId1 = PreviewableResolverUtil.addPreviewableMap(
			Collections.emptyMap());

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId1)) {

			Assert.assertEquals(
				_journalArticle1,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle1.getArticleId()));
			Assert.assertEquals(
				_journalArticle2,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle2.getArticleId()));
			Assert.assertEquals(
				_journalArticle3,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle3.getArticleId()));
			Assert.assertEquals(
				_journalArticle4,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle4.getArticleId()));
		}

		// Preview with empty model class mapping

		Long previewId2 = PreviewableResolverUtil.addPreviewableMap(
			Map.of(JournalArticle.class, Collections.emptyMap()));

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId2)) {

			Assert.assertEquals(
				_journalArticle1,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle1.getArticleId()));
			Assert.assertEquals(
				_journalArticle2,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle2.getArticleId()));
			Assert.assertEquals(
				_journalArticle3,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle3.getArticleId()));
			Assert.assertEquals(
				_journalArticle4,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle4.getArticleId()));
		}

		// Preview _journalArticle1 -> _journalArticle3

		Long previewId3 = PreviewableResolverUtil.addPreviewableMap(
			Map.of(
				JournalArticle.class,
				Map.of(_journalArticle1.getId(), _journalArticle3.getId())));

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId3)) {

			// _journalArticle1 is swapped to _journalArticle3

			Assert.assertEquals(
				_journalArticle3,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle1.getArticleId()));

			// _journalArticle2, _journalArticle3 and _journalArticle4 are themselves

			Assert.assertEquals(
				_journalArticle2,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle2.getArticleId()));
			Assert.assertEquals(
				_journalArticle3,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle3.getArticleId()));
			Assert.assertEquals(
				_journalArticle4,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle4.getArticleId()));

			// Preview disabled method

			Assert.assertEquals(
				_journalArticle1,
				_journalArticleLocalService.fetchArticle(
					_journalArticle1.getId()));
		}

		// Preview _journalArticle2 -> _journalArticle4

		Long previewId4 = PreviewableResolverUtil.addPreviewableMap(
			Map.of(
				JournalArticle.class,
				Map.of(_journalArticle2.getId(), _journalArticle4.getId())));

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId4)) {

			// _journalArticle2 is swapped to _journalArticle4

			Assert.assertEquals(
				_journalArticle4,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle2.getArticleId()));

			// _journalArticle1, _journalArticle3 and _journalArticle4 are themselves

			Assert.assertEquals(
				_journalArticle1,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle1.getArticleId()));
			Assert.assertEquals(
				_journalArticle3,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle3.getArticleId()));
			Assert.assertEquals(
				_journalArticle4,
				_journalArticleLocalService.fetchArticle(
					_group.getGroupId(), _journalArticle4.getArticleId()));

			// Preview disabled method

			Assert.assertEquals(
				_journalArticle2,
				_journalArticleLocalService.fetchArticle(
					_journalArticle2.getId()));
		}

		// Preview with missing target

		Long previewId5 = PreviewableResolverUtil.addPreviewableMap(
			Map.of(
				JournalArticle.class, Map.of(_journalArticle1.getId(), -1L)));

		try (SafeCloseable safeCloseable =
				PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
					previewId5)) {

			_journalArticleLocalService.fetchArticle(
				_group.getGroupId(), _journalArticle1.getArticleId());

			Assert.fail();
		}
		catch (UndeclaredThrowableException undeclaredThrowableException) {
			Throwable throwable = undeclaredThrowableException.getCause();

			Assert.assertSame(
				NoSuchArticleException.class, throwable.getClass());
			Assert.assertEquals(
				"No JournalArticle exists with the primary key -1",
				throwable.getMessage());
		}

		PreviewableResolverUtil.removePreviewableMap(previewId1);
		PreviewableResolverUtil.removePreviewableMap(previewId2);
		PreviewableResolverUtil.removePreviewableMap(previewId3);
		PreviewableResolverUtil.removePreviewableMap(previewId4);
		PreviewableResolverUtil.removePreviewableMap(previewId5);
	}

	@Test
	public void testThreadLocalCacheHonorsPreviewSwap() throws Exception {
		ThreadLocalCacheManager.clearAll(Lifecycle.ETERNAL);

		try {

			// Cache the live version outside the preview.

			Assert.assertEquals(
				_journalArticle1,
				_journalArticleLocalService.getArticle(
					_group.getGroupId(), _journalArticle1.getArticleId()));

			// Preview _journalArticle1 -> _journalArticle3

			Long previewId = PreviewableResolverUtil.addPreviewableMap(
				Map.of(
					JournalArticle.class,
					Map.of(
						_journalArticle1.getId(), _journalArticle3.getId())));

			try (SafeCloseable safeCloseable =
					PreviewableResolverUtil.setPreviewIdWithSafeCloseable(
						previewId)) {

				// A non-cached method swaps to the draft.

				Assert.assertEquals(
					_journalArticle3,
					_journalArticleLocalService.fetchArticle(
						_group.getGroupId(), _journalArticle1.getArticleId()));

				// The cached method swaps too, because the preview id is part
				// of the cache key.

				Assert.assertEquals(
					_journalArticle3,
					_journalArticleLocalService.getArticle(
						_group.getGroupId(), _journalArticle1.getArticleId()));
			}

			PreviewableResolverUtil.removePreviewableMap(previewId);
		}
		finally {
			ThreadLocalCacheManager.clearAll(Lifecycle.ETERNAL);
		}
	}

	@Inject
	private static JournalArticleLocalService _journalArticleLocalService;

	@Inject
	private static JournalFolderLocalService _journalFolderLocalService;

	private Group _group;
	private JournalArticle _journalArticle1;
	private JournalArticle _journalArticle2;
	private JournalArticle _journalArticle3;
	private JournalArticle _journalArticle4;
	private JournalFolder _journalFolder;
	private final JournalFolderFixture _journalFolderFixture =
		new JournalFolderFixture(_journalFolderLocalService);

}