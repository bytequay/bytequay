-- A reviewed V2 Plan may be accepted by an explicitly attributed local
-- automation policy. This is distinct from both a user click and Task policy
-- auto-approval; the synchronous Plan owner still enforces the exact revision
-- and self-review fence.

PRAGMA legacy_alter_table = ON;

DROP TRIGGER plan_approval_fence_insert;
DROP TRIGGER plan_approval_immutable;

ALTER TABLE plan_approval RENAME TO plan_approval_v280;

CREATE TABLE plan_approval (
    id                  TEXT    NOT NULL PRIMARY KEY,
    plan_revision_id    TEXT    NOT NULL UNIQUE
        REFERENCES plan_revision(id) ON DELETE CASCADE,
    self_review_id      TEXT    NOT NULL UNIQUE REFERENCES plan_self_review(id),
    approval_kind       TEXT    NOT NULL CHECK (approval_kind IN (
        'HUMAN', 'POLICY', 'AUTOMATION')),
    policy_revision_id  TEXT    NOT NULL REFERENCES task_policy_revision(id),
    actor               TEXT    NOT NULL,
    approved_at_ms      INTEGER NOT NULL,
    CHECK (approval_kind <> 'AUTOMATION'
        OR (actor LIKE 'automation/%' AND length(actor) > 11))
);

INSERT INTO plan_approval(
    id, plan_revision_id, self_review_id, approval_kind,
    policy_revision_id, actor, approved_at_ms)
SELECT id, plan_revision_id, self_review_id, approval_kind,
       policy_revision_id, actor, approved_at_ms
FROM plan_approval_v280;

DROP TABLE plan_approval_v280;

CREATE TRIGGER plan_approval_fence_insert
BEFORE INSERT ON plan_approval
WHEN NOT EXISTS (
    SELECT 1
    FROM plan_self_review sr
    JOIN plan_revision r ON r.id = sr.plan_revision_id
    JOIN plan_stage p ON p.stage_id = r.plan_stage_id
    JOIN stage s ON s.id = p.stage_id
    JOIN tasks t ON t.id = p.task_id
    JOIN task_current_stage c ON c.stage_id = s.id
    JOIN task_policy_revision policy ON policy.id = NEW.policy_revision_id
    WHERE sr.id = NEW.self_review_id
      AND sr.plan_revision_id = NEW.plan_revision_id
      AND sr.status = 'SUCCEEDED'
      AND sr.verdict = 'APPROVED'
      AND t.policy_revision_id = NEW.policy_revision_id
      AND (NEW.approval_kind <> 'POLICY' OR (
          policy.auto_approve = 1
          AND NOT EXISTS (
              SELECT 1
              FROM plan_followup f
              JOIN plan_revision fr ON fr.id = f.plan_revision_id
              JOIN plan_stage fp ON fp.stage_id = fr.plan_stage_id
              WHERE fp.task_id = t.id
                AND f.kind = 'STEWARDSHIP'
                AND f.status <> 'RESOLVED')))
      AND t.epoch = p.opened_for_epoch
      AND c.task_id = t.id
      AND c.stage_generation = p.generation
      AND s.completed_at_ms IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM plan_revision newer
          WHERE newer.plan_stage_id = r.plan_stage_id
            AND newer.revision > r.revision))
BEGIN SELECT RAISE(ABORT, 'Plan approval requires exact approved self-review evidence'); END;

CREATE TRIGGER plan_approval_immutable
BEFORE UPDATE ON plan_approval
BEGIN SELECT RAISE(ABORT, 'Plan approval is immutable'); END;

PRAGMA legacy_alter_table = OFF;
