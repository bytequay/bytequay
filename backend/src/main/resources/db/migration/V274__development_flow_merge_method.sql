-- Preserve the user's direct-merge strategy as immutable operation input.
-- Existing V2 rows predate strategy capture and retain the former squash
-- behavior through the additive default.

ALTER TABLE remote_merge_authorization
    ADD COLUMN merge_method TEXT NOT NULL DEFAULT 'squash'
        CHECK (merge_method IN ('merge', 'squash', 'rebase'));

ALTER TABLE remote_merge_operation
    ADD COLUMN merge_method TEXT NOT NULL DEFAULT 'squash'
        CHECK (merge_method IN ('merge', 'squash', 'rebase'));

CREATE TRIGGER remote_merge_authorization_method_immutable_v274
BEFORE UPDATE OF merge_method ON remote_merge_authorization
WHEN NEW.merge_method IS NOT OLD.merge_method
BEGIN SELECT RAISE(ABORT, 'Merge authorization method is immutable'); END;

CREATE TRIGGER remote_merge_operation_method_insert_v274
BEFORE INSERT ON remote_merge_operation
WHEN NOT EXISTS (
    SELECT 1 FROM remote_merge_authorization authorization
    WHERE authorization.id = NEW.merge_authorization_id
      AND authorization.merge_method = NEW.merge_method)
BEGIN SELECT RAISE(ABORT, 'Merge operation method differs from authorization'); END;

CREATE TRIGGER remote_merge_operation_method_immutable_v274
BEFORE UPDATE OF merge_method ON remote_merge_operation
WHEN NEW.merge_method IS NOT OLD.merge_method
BEGIN SELECT RAISE(ABORT, 'Merge operation method is immutable'); END;
