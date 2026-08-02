-- Schema v5 adds exact GitHub Actions job-log proof while keeping already
-- durable schema-v3 concrete and schema-v4 aggregate evidence readable.
DROP TRIGGER remote_pr_snapshot_ci_provenance_v304;
CREATE TRIGGER remote_pr_snapshot_ci_provenance_v321
BEFORE INSERT ON remote_pr_snapshot
WHEN NEW.ci_provenance_json IS NOT NULL
 AND (json_valid(NEW.ci_provenance_json) = 0
      OR COALESCE(json_type(
             NEW.ci_provenance_json, '$.schemaVersion'), '') <> 'integer'
      OR COALESCE(json_extract(
             NEW.ci_provenance_json, '$.schemaVersion'), -1) NOT IN (3, 4, 5))
BEGIN SELECT RAISE(ABORT,
    'Remote CI provenance is not schema v3, v4, or v5'); END;

DROP TRIGGER ci_base_repair_manifest_insert_v304;
CREATE TRIGGER ci_base_repair_manifest_insert_v321
BEFORE INSERT ON ci_base_repair_manifest_v303
WHEN NOT EXISTS (
    SELECT 1
    FROM ci_repair_episode episode
    JOIN remote_ci_evaluation evaluation
      ON evaluation.id = episode.failed_ci_evaluation_id
    JOIN remote_pr_snapshot snapshot
      ON snapshot.id = evaluation.remote_pr_snapshot_id
    WHERE episode.id = NEW.ci_repair_episode_id
      AND episode.classification = 'BASE_DETERMINISTIC'
      AND episode.failed_ci_evaluation_id = NEW.failed_ci_evaluation_id
      AND episode.subject_head_sha = NEW.subject_head_sha
      AND episode.subject_base_sha = NEW.subject_base_sha
      AND evaluation.remote_pr_snapshot_id = NEW.remote_pr_snapshot_id
      AND evaluation.head_sha = NEW.subject_head_sha
      AND evaluation.base_sha = NEW.subject_base_sha
      AND snapshot.head_sha = NEW.subject_head_sha
      AND snapshot.base_sha = NEW.subject_base_sha
      AND snapshot.ci_provenance_json IS NOT NULL
      AND json_valid(snapshot.ci_provenance_json)
      AND json_extract(snapshot.ci_provenance_json,
          '$.schemaVersion') IN (3, 4, 5)
      AND json_extract(snapshot.ci_provenance_json, '$.complete') = 1)
BEGIN SELECT RAISE(ABORT,
    'Base repair requires exact complete typed CI proof'); END;
