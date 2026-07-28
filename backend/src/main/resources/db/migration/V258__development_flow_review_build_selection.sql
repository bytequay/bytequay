-- Freeze the exact standalone-review findings selected when a build Trunk is
-- spawned. Task creation later consumes this snapshot instead of re-reading
-- mutable review rows or downgrading the handoff to EXISTING_OWN_PR.

CREATE TABLE review_build_selection (
    thread_id          TEXT    PRIMARY KEY
        REFERENCES threads(id) ON DELETE RESTRICT,
    review_pass_id     TEXT    NOT NULL UNIQUE
        REFERENCES review_passes(id) ON DELETE RESTRICT,
    repo_full_name     TEXT    NOT NULL,
    pr_number          INTEGER NOT NULL CHECK (pr_number > 0),
    reviewed_head_sha  TEXT    NOT NULL,
    selection_digest  TEXT    NOT NULL,
    frozen_at_ms       INTEGER NOT NULL CHECK (frozen_at_ms >= 0),
    CHECK (length(trim(repo_full_name)) > 0
        AND length(trim(reviewed_head_sha)) > 0
        AND length(trim(selection_digest)) > 0)
);

CREATE TABLE review_build_selection_item (
    thread_id          TEXT    NOT NULL
        REFERENCES review_build_selection(thread_id) ON DELETE RESTRICT,
    position           INTEGER NOT NULL CHECK (position > 0),
    review_pass_id     TEXT    NOT NULL,
    finding_id         TEXT    NOT NULL
        REFERENCES review_findings(id) ON DELETE RESTRICT,
    finding_revision   INTEGER NOT NULL CHECK (finding_revision > 0),
    content_json       TEXT    NOT NULL,
    content_digest     TEXT    NOT NULL,
    PRIMARY KEY (thread_id, position),
    UNIQUE (thread_id, finding_id),
    CHECK (length(trim(review_pass_id)) > 0
        AND length(trim(finding_id)) > 0
        AND length(trim(content_json)) > 0
        AND length(trim(content_digest)) > 0)
);

CREATE TRIGGER review_build_selection_item_insert
BEFORE INSERT ON review_build_selection_item
WHEN NEW.position <> COALESCE((
        SELECT MAX(item.position) + 1
        FROM review_build_selection_item item
        WHERE item.thread_id = NEW.thread_id), 1)
  OR NOT EXISTS (
      SELECT 1
      FROM review_build_selection selection
      JOIN review_findings finding ON finding.id = NEW.finding_id
      WHERE selection.thread_id = NEW.thread_id
        AND selection.review_pass_id = NEW.review_pass_id
        AND finding.review_pass_id = NEW.review_pass_id)
BEGIN
    SELECT RAISE(ABORT, 'review build finding does not match its frozen selection');
END;

CREATE TRIGGER review_build_selection_immutable
BEFORE UPDATE ON review_build_selection
BEGIN
    SELECT RAISE(ABORT, 'Review build selection is immutable');
END;

CREATE TRIGGER review_build_selection_delete_guard
BEFORE DELETE ON review_build_selection
BEGIN
    SELECT RAISE(ABORT, 'Review build selection cannot be deleted');
END;

CREATE TRIGGER review_build_selection_item_immutable
BEFORE UPDATE ON review_build_selection_item
BEGIN
    SELECT RAISE(ABORT, 'Review build selection item is immutable');
END;

CREATE TRIGGER review_build_selection_item_delete_guard
BEFORE DELETE ON review_build_selection_item
BEGIN
    SELECT RAISE(ABORT, 'Review build selection item cannot be deleted');
END;
