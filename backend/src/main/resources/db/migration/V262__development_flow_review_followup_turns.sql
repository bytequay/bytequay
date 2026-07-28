-- Durable, purpose-specific follow-up calls for V2 investigation reviews.

-- Before V262 the only admitted purpose was the primary investigation. Do
-- not guess a logical identity if a database contains manually written or
-- partially migrated follow-up rows.
CREATE TABLE review_assignment_turn_backfill_guard_v262 (
    valid INTEGER NOT NULL CHECK (valid = 1)
);
INSERT INTO review_assignment_turn_backfill_guard_v262(valid)
SELECT CASE WHEN EXISTS (
    SELECT 1
    FROM review_assignment_turn turn
    JOIN review_assignment assignment ON assignment.id = turn.assignment_id
    JOIN review_round round ON round.id = assignment.round_id
    WHERE turn.purpose <> 'investigate'
       OR turn.start_commit <> round.start_commit)
    THEN 0 ELSE 1 END;
DROP TABLE review_assignment_turn_backfill_guard_v262;

ALTER TABLE review_assignment_turn
    ADD COLUMN subject_key TEXT NOT NULL DEFAULT '';

ALTER TABLE review_assignment_turn
    ADD COLUMN verifier_run_id TEXT REFERENCES agent_run(id);

DROP INDEX idx_review_assignment_turn_assignment_attempt;
CREATE UNIQUE INDEX idx_review_assignment_turn_logical_attempt
    ON review_assignment_turn(assignment_id, purpose, subject_key, attempt);

DROP TRIGGER review_assignment_turn_identity_immutable;

UPDATE review_assignment_turn
SET subject_key = assignment_id
WHERE subject_key = '';

CREATE TRIGGER review_assignment_turn_identity_immutable
BEFORE UPDATE OF assignment_id, purpose, subject_key, verifier_run_id,
        operation_id, attempt, start_commit, delivery_lane, launch_input
        ON review_assignment_turn
WHEN NEW.assignment_id IS NOT OLD.assignment_id
  OR NEW.purpose IS NOT OLD.purpose
  OR NEW.subject_key IS NOT OLD.subject_key
  OR NEW.verifier_run_id IS NOT OLD.verifier_run_id
  OR NEW.operation_id IS NOT OLD.operation_id
  OR NEW.attempt IS NOT OLD.attempt
  OR NEW.start_commit IS NOT OLD.start_commit
  OR NEW.delivery_lane IS NOT OLD.delivery_lane
  OR NEW.launch_input IS NOT OLD.launch_input
BEGIN SELECT RAISE(ABORT, 'review assignment Turn launch identity is immutable'); END;

CREATE TABLE review_round_followup_v262 (
    round_id                 TEXT    NOT NULL PRIMARY KEY
        REFERENCES review_round(id) ON DELETE CASCADE,
    start_commit             TEXT    NOT NULL,
    phase                    TEXT    NOT NULL CHECK (phase IN (
        'PRIMARY', 'SELF_REFUTATION', 'VERIFYING',
        'FINALIZING', 'COMPLETED', 'BLOCKED', 'CANCELED')),
    verifier_assignment_id   TEXT REFERENCES review_assignment(id),
    verifier_run_id          TEXT REFERENCES agent_run(id),
    version                  INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at_ms            INTEGER NOT NULL,
    updated_at_ms            INTEGER NOT NULL,
    CHECK ((verifier_assignment_id IS NULL) = (verifier_run_id IS NULL))
);

CREATE TRIGGER review_round_followup_exact_insert_v262
BEFORE INSERT ON review_round_followup_v262
BEGIN
    SELECT CASE
        WHEN NOT EXISTS (
            SELECT 1 FROM review_round round
            WHERE round.id = NEW.round_id
              AND round.start_commit = NEW.start_commit)
            THEN RAISE(ABORT, 'review follow-up must freeze the round start commit')
        WHEN NEW.verifier_assignment_id IS NOT NULL AND NOT EXISTS (
            SELECT 1
            FROM review_assignment assignment
            JOIN agent_run run ON run.id = NEW.verifier_run_id
            WHERE assignment.id = NEW.verifier_assignment_id
              AND assignment.round_id = NEW.round_id
              AND run.review_round_id = NEW.round_id)
            THEN RAISE(ABORT, 'review follow-up verifier ownership is not exact')
    END;
END;

CREATE TRIGGER review_round_followup_identity_update_v262
BEFORE UPDATE OF round_id, start_commit, verifier_assignment_id, verifier_run_id
        ON review_round_followup_v262
WHEN NEW.round_id IS NOT OLD.round_id
  OR NEW.start_commit IS NOT OLD.start_commit
  OR (OLD.verifier_assignment_id IS NOT NULL
      AND NEW.verifier_assignment_id IS NOT OLD.verifier_assignment_id)
  OR (OLD.verifier_run_id IS NOT NULL
      AND NEW.verifier_run_id IS NOT OLD.verifier_run_id)
BEGIN SELECT RAISE(ABORT, 'review follow-up identity is immutable'); END;

CREATE TRIGGER review_round_followup_binding_update_v262
BEFORE UPDATE OF verifier_assignment_id, verifier_run_id
        ON review_round_followup_v262
WHEN OLD.verifier_assignment_id IS NULL
  AND NEW.verifier_assignment_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM review_assignment assignment
      JOIN agent_run run ON run.id = NEW.verifier_run_id
      WHERE assignment.id = NEW.verifier_assignment_id
        AND assignment.round_id = NEW.round_id
        AND run.review_round_id = NEW.round_id)
BEGIN SELECT RAISE(ABORT, 'review follow-up verifier ownership is not exact'); END;

INSERT INTO review_round_followup_v262(
    round_id, start_commit, phase, created_at_ms, updated_at_ms)
SELECT DISTINCT assignment.round_id, turn.start_commit,
       CASE
           WHEN round.status = 'CANCELLED' THEN 'CANCELED'
           WHEN round.status = 'ERRORED' THEN 'BLOCKED'
           WHEN round.status IN ('COMPLETED', 'COMPLETED_WITH_QUESTIONS')
               THEN 'COMPLETED'
           ELSE 'PRIMARY'
       END,
       MIN(turn.requested_at_ms), MAX(turn.requested_at_ms)
FROM review_assignment_turn turn
JOIN review_assignment assignment ON assignment.id = turn.assignment_id
JOIN review_round round ON round.id = assignment.round_id
GROUP BY assignment.round_id, turn.start_commit, round.status;
