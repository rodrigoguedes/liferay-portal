-- LPD-98298: converts version 2.0 of every article into a DRAFT (status=2).
-- The sample-sql-builder generates all versions as APPROVED (status=0);
-- "head" is derived at reindex time (latest approved version per
-- resourcePrimKey), so after this UPDATE: v1.0 = approved head (live),
-- v2.0 = indexed draft.

UPDATE JournalArticle
SET status = 2, statusDate = NOW()
WHERE version = 2;

-- Verification: expected (1.0, 0, <pairs>) and (2.0, 2, <pairs>)
SELECT version, status, COUNT(*) AS total
FROM JournalArticle
GROUP BY version, status
ORDER BY version, status;
