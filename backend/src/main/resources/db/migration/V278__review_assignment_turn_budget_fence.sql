-- Freeze one billable ceiling per typed review Turn. Live reservations plus
-- already-recorded provider spend may never exceed the ReviewRound cap.

ALTER TABLE review_assignment_turn
    ADD COLUMN cost_cap_usd_milli INTEGER CHECK (cost_cap_usd_milli > 0);

-- There is no production review data at this cutover. Keep a deterministic
-- value for schema fixtures and fail closed for every new row below.
UPDATE review_assignment_turn
SET cost_cap_usd_milli = 1
WHERE cost_cap_usd_milli IS NULL;

DROP TRIGGER review_assignment_turn_identity_immutable;
CREATE TRIGGER review_assignment_turn_identity_immutable
BEFORE UPDATE OF assignment_id, purpose, subject_key, verifier_run_id,
        operation_id, attempt, start_commit, delivery_lane, launch_input,
        cost_cap_usd_milli
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
  OR NEW.cost_cap_usd_milli IS NOT OLD.cost_cap_usd_milli
BEGIN SELECT RAISE(ABORT, 'review assignment Turn launch identity is immutable'); END;

CREATE TRIGGER review_assignment_turn_budget_reservation_insert_v278
BEFORE INSERT ON review_assignment_turn
BEGIN
    SELECT CASE
        WHEN NEW.cost_cap_usd_milli IS NULL OR NEW.cost_cap_usd_milli < 1
            THEN RAISE(ABORT, 'review assignment Turn requires a positive frozen cost cap')
        WHEN COALESCE((
            SELECT CAST(COALESCE(
                        json_extract(round.budget_json, '$.cost_cap_cents'),
                        json_extract(round.budget_json, '$.costCapCents')) AS INTEGER)
            FROM review_assignment assignment
            JOIN review_round round ON round.id = assignment.round_id
            WHERE assignment.id = NEW.assignment_id), 0) < 1
            THEN RAISE(ABORT, 'review round requires a positive cost cap')
        WHEN NEW.cost_cap_usd_milli > (
            SELECT CAST(COALESCE(
                        json_extract(round.budget_json, '$.cost_cap_cents'),
                        json_extract(round.budget_json, '$.costCapCents')) AS INTEGER) * 10
                   - COALESCE((
                        SELECT SUM(receipt.cost_usd_milli)
                        FROM review_assignment_turn_result_receipt receipt
                        WHERE receipt.round_id = round.id), 0)
                   - COALESCE((
                        SELECT SUM(existing.cost_cap_usd_milli)
                        FROM review_assignment_turn existing
                        JOIN review_assignment existing_assignment
                          ON existing_assignment.id = existing.assignment_id
                        WHERE existing_assignment.round_id = round.id
                          AND existing.status IN (
                              'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')), 0)
            FROM review_assignment assignment
            JOIN review_round round ON round.id = assignment.round_id
            WHERE assignment.id = NEW.assignment_id)
            THEN RAISE(ABORT, 'review assignment Turn exceeds the round cost cap')
    END;
END;

CREATE TRIGGER review_round_budget_reservation_update_v278
BEFORE UPDATE OF budget_json ON review_round
WHEN COALESCE(
        json_extract(NEW.budget_json, '$.cost_cap_cents'),
        json_extract(NEW.budget_json, '$.costCapCents')) IS NULL
  OR CAST(COALESCE(
        json_extract(NEW.budget_json, '$.cost_cap_cents'),
        json_extract(NEW.budget_json, '$.costCapCents')) AS INTEGER) < 1
  OR CAST(COALESCE(
        json_extract(NEW.budget_json, '$.cost_cap_cents'),
        json_extract(NEW.budget_json, '$.costCapCents')) AS INTEGER) * 10 <
        COALESCE((
            SELECT SUM(receipt.cost_usd_milli)
            FROM review_assignment_turn_result_receipt receipt
            WHERE receipt.round_id = NEW.id), 0)
        + COALESCE((
            SELECT SUM(turn.cost_cap_usd_milli)
            FROM review_assignment_turn turn
            JOIN review_assignment assignment ON assignment.id = turn.assignment_id
            WHERE assignment.round_id = NEW.id
              AND turn.status IN (
                  'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')), 0)
BEGIN SELECT RAISE(ABORT, 'review round budget is below durable spend and reservations'); END;
