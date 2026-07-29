-- V270 deliberately shipped a four-value wire kind. Keep that column intact
-- so its rows remain readable, and add the product-level command identity
-- needed by the complete user PR-control protocol. New semantic actions use
-- DEQUEUE only as the legacy wire carrier; their immutable meaning lives here.

ALTER TABLE v2_user_remote_action_v270
    ADD COLUMN semantic_action TEXT;

UPDATE v2_user_remote_action_v270
SET semantic_action = kind
WHERE semantic_action IS NULL;

CREATE INDEX idx_v2_user_remote_action_semantic_v282
    ON v2_user_remote_action_v270(
        task_id, semantic_action, payload_digest, status);

CREATE TRIGGER v2_user_remote_action_semantic_insert_v282
BEFORE INSERT ON v2_user_remote_action_v270
WHEN NEW.semantic_action IS NULL
  OR NEW.semantic_action NOT IN (
      'DEQUEUE', 'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT',
      'SUBMIT_REVIEW', 'RERUN_FAILED_CHECKS', 'SET_DRAFT_STATE',
      'UPDATE_TITLE', 'UPDATE_BODY', 'CLOSE_PULL_REQUEST',
      'COMMENT_AND_CLOSE',
      'REPLY_REVIEW_THREAD', 'EDIT_ISSUE_COMMENT', 'EDIT_REVIEW_COMMENT',
      'DELETE_ISSUE_COMMENT', 'DELETE_REVIEW_COMMENT', 'ADD_REVIEWER',
      'REMOVE_REVIEWER', 'SET_ASSIGNEE', 'SET_LABEL',
      'CREATE_INLINE_COMMENT', 'REACT_PULL_REQUEST',
      'REACT_REVIEW_COMMENT', 'REACT_ISSUE_COMMENT',
      'SET_THREAD_RESOLUTION')
  OR (NEW.semantic_action IN (
          'DEQUEUE', 'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT',
          'SUBMIT_REVIEW')
      AND NEW.kind <> NEW.semantic_action)
  OR (NEW.semantic_action NOT IN (
          'DEQUEUE', 'DELETE_REMOTE_BRANCH', 'POST_TOP_LEVEL_COMMENT',
          'SUBMIT_REVIEW')
      AND NEW.kind <> 'DEQUEUE')
BEGIN SELECT RAISE(ABORT,
    'V2 user remote action semantic identity is invalid'); END;

CREATE TRIGGER v2_user_remote_action_semantic_update_v282
BEFORE UPDATE OF semantic_action ON v2_user_remote_action_v270
BEGIN SELECT RAISE(ABORT,
    'V2 user remote action semantic identity is immutable'); END;
