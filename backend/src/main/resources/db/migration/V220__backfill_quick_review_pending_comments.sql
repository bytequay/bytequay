-- Older quick reviews were stored only in the review-draft tables, so their
-- merge-blocking findings never appeared in the canonical pending-comment
-- surfaces. Backfill the latest review once; future runs dual-write directly.
WITH raw_candidates AS (
    SELECT
        comment.id AS review_comment_id,
        draft.unified_pr_id AS pr_id,
        comment.file_path,
        comment.line_number,
        CASE
            WHEN comment.edited_body IS NOT NULL AND trim(comment.edited_body) <> ''
                THEN comment.edited_body
            ELSE comment.body
        END AS body,
        CASE
            WHEN upper(trim(comment.side)) IN ('LEFT', 'RIGHT') THEN upper(trim(comment.side))
            ELSE 'RIGHT'
        END AS side,
        comment.start_line,
        CASE
            WHEN upper(trim(comment.start_side)) IN ('LEFT', 'RIGHT') THEN upper(trim(comment.start_side))
            ELSE NULL
        END AS start_side,
        COALESCE(
            CAST(strftime('%s', comment.created_at) AS INTEGER) * 1000,
            CAST(strftime('%s', draft.created_at) AS INTEGER) * 1000,
            0
        ) AS created_at_ms
    FROM pr_review_draft draft
    JOIN pr ON pr.id = draft.unified_pr_id
    JOIN pr_review_comment comment ON comment.draft_id = draft.id
    WHERE draft.id = (
        SELECT latest.id
        FROM pr_review_draft latest
        WHERE latest.unified_pr_id = draft.unified_pr_id
        ORDER BY latest.created_at DESC, latest.id DESC
        LIMIT 1
    )
      AND upper(trim(comment.source)) = 'AI'
      AND comment.dismissed = 0
      AND lower(replace(replace(trim(comment.severity), '-', '_'), ' ', '_'))
          IN ('blocker', 'critical', 'error', 'request_changes')
      AND trim(comment.file_path) <> ''
      AND comment.line_number > 0
      AND trim(CASE
          WHEN comment.edited_body IS NOT NULL AND trim(comment.edited_body) <> ''
              THEN comment.edited_body
          ELSE comment.body
      END) <> ''
),
candidates AS (
    SELECT
        candidate.*,
        row_number() OVER (
            PARTITION BY candidate.pr_id, candidate.file_path,
                candidate.line_number, candidate.body
            ORDER BY candidate.review_comment_id) AS duplicate_rank
    FROM raw_candidates candidate
)
INSERT INTO pr_comment(
    id, pr_id, origin, scope, file_path, line_number,
    author, body, created_at_ms, parent_comment_id,
    side, start_line, start_side)
SELECT
    lower(hex(randomblob(16))),
    candidate.pr_id,
    'local',
    'file-line',
    candidate.file_path,
    candidate.line_number,
    'ai-reviewer',
    candidate.body,
    candidate.created_at_ms,
    NULL,
    candidate.side,
    candidate.start_line,
    candidate.start_side
FROM candidates candidate
WHERE candidate.duplicate_rank = 1
  AND NOT EXISTS (
    SELECT 1
    FROM pr_comment existing
    WHERE existing.pr_id = candidate.pr_id
      AND existing.origin = 'local'
      AND existing.scope = 'file-line'
      AND existing.parent_comment_id IS NULL
      AND existing.file_path = candidate.file_path
      AND existing.line_number = candidate.line_number
      AND existing.body = candidate.body
      AND (existing.author = 'ai-reviewer'
        OR (existing.resolved_at_ms IS NULL
          AND existing.dismissed_at_ms IS NULL
          AND existing.stripped_on_push_at_ms IS NULL
          AND existing.published_at_ms IS NULL))
);
