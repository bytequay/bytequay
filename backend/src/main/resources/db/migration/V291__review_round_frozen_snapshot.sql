-- Every typed ReviewAssignmentTurn in a round must observe the same frozen
-- code subject.  The capture path writes this row before it creates/admit
-- assignments; all later continuations read it instead of reloading Git or
-- GitHub state.
CREATE TABLE review_round_snapshot_v291 (
    round_id           TEXT    NOT NULL PRIMARY KEY
        REFERENCES review_round(id) ON DELETE CASCADE,
    repository         TEXT,
    remote_pr_number   INTEGER,
    base_branch        TEXT    NOT NULL,
    pr_title           TEXT    NOT NULL,
    pr_description     TEXT    NOT NULL,
    base_commit        TEXT    NOT NULL,
    head_commit        TEXT    NOT NULL,
    diff               TEXT    NOT NULL,
    files_json         TEXT    NOT NULL CHECK (json_valid(files_json)),
    file_contents_json TEXT    NOT NULL
        CHECK (json_valid(file_contents_json)
            AND json_type(file_contents_json) = 'object'),
    local_root         TEXT,
    repository_root    TEXT,
    capabilities_json  TEXT    NOT NULL CHECK (json_valid(capabilities_json)),
    created_at_ms      INTEGER NOT NULL,
    CHECK (length(trim(base_commit)) > 0
        AND length(trim(head_commit)) > 0),
    CHECK (length(trim(base_branch)) > 0),
    CHECK ((repository IS NULL) = (remote_pr_number IS NULL)),
    CHECK (repository IS NULL OR length(trim(repository)) > 0),
    CHECK (remote_pr_number IS NULL OR remote_pr_number > 0),
    CHECK (local_root IS NULL OR length(trim(local_root)) > 0),
    CHECK (repository_root IS NULL OR length(trim(repository_root)) > 0)
);

CREATE TRIGGER review_round_snapshot_subject_v291
BEFORE INSERT ON review_round_snapshot_v291
WHEN NOT EXISTS (
    SELECT 1
    FROM review_round round
    JOIN review_session review ON review.id = round.session_id
    JOIN pr ON pr.id = review.pr_id
    WHERE round.id = NEW.round_id
      AND round.start_commit = NEW.head_commit
      AND review.base_commit = NEW.base_commit
      AND pr.repo IS NEW.repository
      AND pr.remote_pr_number IS NEW.remote_pr_number
      AND pr.base_branch = NEW.base_branch
      AND pr.title = NEW.pr_title
      AND pr.description = NEW.pr_description)
BEGIN
    SELECT RAISE(ABORT,
        'Review round snapshot differs from its exact ReviewSession subject');
END;

CREATE TRIGGER review_round_snapshot_immutable_v291
BEFORE UPDATE ON review_round_snapshot_v291
BEGIN
    SELECT RAISE(ABORT, 'Review round snapshot is immutable');
END;
