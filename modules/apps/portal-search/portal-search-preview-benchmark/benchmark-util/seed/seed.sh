#!/bin/bash
#
# LPD-98298 — Seeds the benchmark corpus via the /benchmarks module
# (sample-sql-builder).
#
# Usage:
#   ./seed.sh smoke   # minimal corpus (validates the pipeline in minutes)
#   ./seed.sh full    # full corpus: 50k docs = 25k live/draft pairs
#
# Prerequisites: ant, MySQL (Docker container "mysql", root/root).
# The target schema (lportal_bench) is recreated from scratch — the current
# lportal database is NOT touched.
#
# Paths (prompted interactively when the variable is not set):
#   LIFERAY_PORTAL_SRC_DIR   portal source checkout (branch LPD-98298)
#   LIFERAY_BUNDLES_DIR      bundle path, used only in the printed next steps
#                            (default: sibling bundles/ of the source's parent)

set -euo pipefail

PORTAL="${LIFERAY_PORTAL_SRC_DIR:-${PORTAL:-}}"

if [ -z "$PORTAL" ]; then
	read -r -p "Liferay portal source path (e.g. /home/user/dev/search-2/liferay-portal): " PORTAL
fi

PORTAL="${PORTAL%/}"

if [ ! -d "$PORTAL/benchmarks" ]; then
	echo "ERROR: '$PORTAL' does not look like the portal source (no benchmarks/ directory)." >&2
	exit 1
fi

BUNDLES="${LIFERAY_BUNDLES_DIR:-$(dirname "$PORTAL")/bundles}"
SEED_DIR="$(cd "$(dirname "$0")" && pwd)"
DB="${DB:-lportal_bench}"

# MySQL runs in a Docker container (no mysql client on the host). Override
# MYSQL_CONTAINER when your container is not named "mysql" — setup/setup.sh
# prints the right value for the environment it configured.
MYSQL_CONTAINER="${MYSQL_CONTAINER:-mysql}"
MYSQL_CMD=(docker exec -i "$MYSQL_CONTAINER" mysql -uroot -proot)

MODE="${1:-smoke}"

case "$MODE" in
	smoke)
		GROUP_COUNT=2
		PAGE_COUNT=2
		ARTICLE_COUNT=5
		;;
	full)
		GROUP_COUNT=50
		PAGE_COUNT=10
		ARTICLE_COUNT=50
		;;
	*)
		echo "Usage: $0 [smoke|full]" >&2
		exit 1
		;;
esac

PAIRS=$((GROUP_COUNT * PAGE_COUNT * ARTICLE_COUNT))
FTL="modules/util/portal-tools-sample-sql-builder/src/main/resources/com/liferay/portal/tools/sample/sql/builder/dependencies/journal_article.ftl"
PROPS_FILE="$PORTAL/benchmarks/benchmarks.$(whoami).properties"

echo ">>> Mode: $MODE — $PAIRS live/draft pairs ($((PAIRS * 2)) indexed docs)"

# 1. Temporary patch to journal_article.ftl (MBDiscussion/PortletPreferences
#    inside the version loop collide with unique indexes when version.count=2).
if git -C "$PORTAL" apply --check "$SEED_DIR/journal_article_ftl.patch" 2>/dev/null; then
	git -C "$PORTAL" apply "$SEED_DIR/journal_article_ftl.patch"
	echo ">>> journal_article.ftl patch applied"
elif git -C "$PORTAL" apply --check --reverse "$SEED_DIR/journal_article_ftl.patch" 2>/dev/null; then
	echo ">>> journal_article.ftl patch already applied"
else
	echo "ERROR: journal_article.ftl patch does not apply (file changed?)" >&2
	exit 1
fi

revert_patch() {
	git -C "$PORTAL" checkout -- "$FTL"
	echo ">>> journal_article.ftl patch reverted"
}
trap revert_patch EXIT

# 2. Generator properties (single source of truth; overrides benchmarks.properties).
cat > "$PROPS_FILE" <<EOF
	sample.sql.db.type=mysql
	sample.sql.output.merge=true

	sample.sql.max.group.count=$GROUP_COUNT
	sample.sql.max.journal.article.page.count=$PAGE_COUNT
	sample.sql.max.journal.article.count=$ARTICLE_COUNT
	sample.sql.max.journal.article.version.count=2
	sample.sql.max.journal.article.size=30

	# MBCategory never gets an externalReferenceCode -> collides with unique
	# index IX_9E671C6A (groupId, externalReferenceCode, ctCollectionId) when
	# count > 1.
	sample.sql.max.mb.category.count=0
	sample.sql.max.mb.thread.count=0
	sample.sql.max.mb.message.count=0

	# Disables all commerce seeding (CPDSpecificationOptionValue also never
	# gets an externalReferenceCode -> collides with unique index IX_B34CA2FF).
	sample.sql.max.commerce.group.count=0

	# Noise reduction: faster generation/load/reindex.
	sample.sql.max.blogs.entry.count=0
	sample.sql.max.blogs.entry.comment.count=0
	sample.sql.max.dl.file.entry.count=0
	sample.sql.max.dl.folder.count=0
	sample.sql.max.ddl.record.count=0
	sample.sql.max.asset.publisher.page.count=0
	sample.sql.max.segments.entry.count=0
EOF
echo ">>> Generated $PROPS_FILE"

# 3. Generate the sample SQL.
(cd "$PORTAL/benchmarks" && ant build-sample-sql)

SQL_FILE="$PORTAL/benchmarks/sample-mysql.sql"
[ -f "$SQL_FILE" ] || { echo "ERROR: $SQL_FILE not generated" >&2; exit 1; }
echo ">>> Generated $SQL_FILE ($(du -h "$SQL_FILE" | cut -f1))"

# 4. Recreate the schema and load.
"${MYSQL_CMD[@]}" -e "DROP DATABASE IF EXISTS $DB; CREATE DATABASE $DB CHARACTER SET utf8mb4;"
"${MYSQL_CMD[@]}" "$DB" < "$SQL_FILE"
echo ">>> $DB loaded"

# 5. Convert v2.0 into drafts and verify.
"${MYSQL_CMD[@]}" -t "$DB" < "$SEED_DIR/make-drafts.sql"

# Complete pairs: approved v1.0 WITH a v2.0 draft of the same resourcePrimKey.
# (The generator also creates a few unpaired default articles — e.g. one
# TestJournalArticle_0_1 per special group — which stay out of this count.)
PAIRED=$("${MYSQL_CMD[@]}" -N "$DB" -e "
	SELECT COUNT(*)
	FROM JournalArticle approved
	JOIN JournalArticle draft
		ON draft.resourcePrimKey = approved.resourcePrimKey
		AND draft.version = 2 AND draft.status = 2
	WHERE approved.version = 1 AND approved.status = 0")

if [ "$PAIRED" -ne "$PAIRS" ]; then
	echo "ERROR: expected $PAIRS live/draft pairs; got $PAIRED" >&2
	exit 1
fi
echo ">>> OK: $PAIRED live/draft pairs (approved v1.0 + draft v2.0)"

COMPANY_ID=$("${MYSQL_CMD[@]}" -N "$DB" -e "SELECT companyId FROM JournalArticle LIMIT 1")

cat <<EOF

>>> Seed finished. Next steps:
  1. Stop Tomcat.
  2. In $BUNDLES/portal-setup-wizard.properties,
     change jdbc.default.url to .../$DB (keep the current value to restore later).
  3. Start Tomcat and reindex (Control Panel > Search > Index Actions >
     com.liferay.journal.model.JournalArticle).
  4. Verify the index (companyId=$COMPANY_ID):
     curl -s "localhost:9200/liferay-$COMPANY_ID/_count" -H 'Content-Type: application/json' \\
       -d '{"query":{"term":{"entryClassName":"com.liferay.journal.model.JournalArticle"}}}'
     Expected: count >= $((PAIRS * 2)) (2x pairs + a few unpaired default articles)
EOF
