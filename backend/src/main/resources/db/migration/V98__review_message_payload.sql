-- Structured payloads for review-transcript messages. The LLM-driven
-- CROSS_REVIEW and CONSENSUS phases (and later DEBATE turns) carry a
-- machine-readable envelope alongside the human-readable body, so the
-- panel UI can render the "agreed / disputed / open question" structure
-- and a downstream model call can replay just the structured claim
-- without re-parsing prose.
--
-- payload_kind: 'prose' (plain kickoff / moderator / debate text — the
--   default), 'cross_review', 'consensus', or 'debate_turn'. A NULL is
--   treated as 'prose' for rows written before this column existed.
-- payload_json: the structured envelope as a JSON string. TEXT because
--   SQLite has no native JSON type; the app (de)serialises with Jackson,
--   the same way the existing mentions / refs columns are handled. NULL
--   for plain prose.
ALTER TABLE review_messages ADD COLUMN payload_kind TEXT;
ALTER TABLE review_messages ADD COLUMN payload_json TEXT;
