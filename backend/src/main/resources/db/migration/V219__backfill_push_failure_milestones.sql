-- Surface terminal push failures that were already parked before failure
-- milestones were recorded. Future failures use PRService's transactional
-- dual-write; this migration repairs only active legacy authorizations.
INSERT INTO pr_timeline_event(
    id, pr_id, event_type, actor, is_local_only,
    stripped_on_push_at_ms, created_at_ms, payload_json, remote_event_id)
SELECT
    'push-failure-' || lower(hex(randomblob(16))),
    authorization.pr_id,
    'pull-request-progress',
    'claude-code',
    1,
    NULL,
    COALESCE(effect.last_claimed_at_ms, authorization.created_at_ms),
    json_object(
        'phase', 'failed',
        'branch', pr.branch_name,
        'baseBranch', pr.base_branch,
        'failedStep', effect.effect_key,
        'reason', substr(COALESCE(
            effect.last_error, effect.last_error_class, 'Push attempts exhausted'), 1, 2000)),
    NULL
FROM task_push_effect effect
JOIN task_push_authorization authorization ON authorization.token = effect.token
JOIN pr ON pr.id = authorization.pr_id
WHERE effect.status = 'PERMANENT_FAILED'
  AND authorization.revoked_at_ms IS NULL
  AND authorization.consumed_at_ms IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM pr_timeline_event event
      WHERE event.pr_id = authorization.pr_id
        AND event.event_type = 'pull-request-progress'
        AND CASE WHEN json_valid(event.payload_json)
            THEN json_extract(event.payload_json, '$.phase') END = 'failed'
        AND CASE WHEN json_valid(event.payload_json)
            THEN json_extract(event.payload_json, '$.failedStep') END = effect.effect_key
  );

INSERT INTO task_stage_event(
    id, stage_id, task_id, event_type, event_at_ms, payload_json)
SELECT
    'push-failure-' || lower(hex(randomblob(16))),
    stage.id,
    authorization.task_id,
    'PULL_REQUEST_PROGRESS',
    COALESCE(effect.last_claimed_at_ms, authorization.created_at_ms),
    json_object(
        'phase', 'failed',
        'branch', pr.branch_name,
        'baseBranch', pr.base_branch,
        'failedStep', effect.effect_key,
        'reason', substr(COALESCE(
            effect.last_error, effect.last_error_class, 'Push attempts exhausted'), 1, 2000))
FROM task_push_effect effect
JOIN task_push_authorization authorization ON authorization.token = effect.token
JOIN pr ON pr.id = authorization.pr_id
JOIN task_stage stage ON stage.id = (
    SELECT candidate.id
    FROM task_stage candidate
    WHERE candidate.task_id = authorization.task_id
      AND candidate.stage_type = 'DEVELOPMENT_STAGE'
    ORDER BY candidate.opened_at_ms DESC, candidate.id DESC
    LIMIT 1)
WHERE effect.status = 'PERMANENT_FAILED'
  AND authorization.revoked_at_ms IS NULL
  AND authorization.consumed_at_ms IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM task_stage_event event
      WHERE event.task_id = authorization.task_id
        AND event.event_type = 'PULL_REQUEST_PROGRESS'
        AND CASE WHEN json_valid(event.payload_json)
            THEN json_extract(event.payload_json, '$.phase') END = 'failed'
        AND CASE WHEN json_valid(event.payload_json)
            THEN json_extract(event.payload_json, '$.failedStep') END = effect.effect_key
  );
