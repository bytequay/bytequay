-- Bind publish dispatch to the one callback understood by the Local Stage.
-- The V2 dispatcher delivers raw results; it never chooses a domain command.

DROP TRIGGER publish_operation_dispatch;

CREATE TRIGGER publish_operation_dispatch
BEFORE UPDATE OF status ON publish_operation
WHEN NEW.status = 'DISPATCHED'
  AND (NOT EXISTS (
      SELECT 1 FROM dispatch_ticket ticket
      WHERE ticket.operation_id = NEW.operation_id
        AND ticket.operation_kind = 'PUBLISH_LOCAL_DEVELOPMENT'
        AND ticket.async_family = 'GITHUB_EFFECT'
        AND ticket.owner_kind = 'STAGE'
        AND ticket.owner_id = NEW.local_development_stage_id
        AND ticket.callback_route = 'STAGE_PUBLISH_RESULT'
        AND ticket.task_id = NEW.task_id
        AND ticket.task_epoch = NEW.task_epoch
        AND ticket.stage_id = NEW.local_development_stage_id
        AND ticket.stage_generation = NEW.stage_generation
        AND ticket.attempt = NEW.semantic_attempt
        AND ticket.expected_code_fingerprint = NEW.code_fingerprint
        AND ticket.expected_head_sha = NEW.expected_head_sha
        AND ticket.expected_base_sha = NEW.expected_base_sha
        AND ticket.trunk_control = 0
        AND ticket.exclusive_task = 1
        AND ticket.writer_required = 1
        AND ticket.lane_mask = 48
        AND ticket.status = 'REQUESTED')
    OR (SELECT COUNT(*) FROM publish_effect_step step
        WHERE step.publish_operation_id = NEW.id) <> 6)
BEGIN SELECT RAISE(ABORT,
    'dispatched PublishOperation requires exact route, lanes, and six steps'); END;
