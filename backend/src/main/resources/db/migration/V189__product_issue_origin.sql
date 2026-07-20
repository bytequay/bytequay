-- Stable local attribution for issues created through trusted ByteQuay flows.
CREATE TABLE product_issue_origin (
    issue_id BIGINT PRIMARY KEY,
    issue_number INTEGER NOT NULL UNIQUE,
    origin TEXT NOT NULL CHECK (origin IN ('user', 'user-report', 'quality-scan'))
);

CREATE TRIGGER product_issue_origin_immutable
BEFORE UPDATE OF origin ON product_issue_origin
WHEN NEW.origin <> OLD.origin
BEGIN
    SELECT RAISE(ABORT, 'product issue origin is immutable');
END;
