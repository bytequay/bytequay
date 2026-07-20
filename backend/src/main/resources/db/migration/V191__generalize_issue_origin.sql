-- Generalize origin attribution from the product repository to any GitHub repository.
CREATE TABLE issue_origin (
    issue_id BIGINT PRIMARY KEY,
    issue_number INTEGER NOT NULL,
    origin TEXT NOT NULL CHECK (origin IN ('user', 'user-report', 'quality-scan'))
);

INSERT INTO issue_origin (issue_id, issue_number, origin)
SELECT issue_id, issue_number, origin FROM product_issue_origin;

DROP TRIGGER product_issue_origin_immutable;
DROP TABLE product_issue_origin;

CREATE TRIGGER issue_origin_immutable
BEFORE UPDATE OF origin ON issue_origin
WHEN NEW.origin <> OLD.origin
BEGIN
    SELECT RAISE(ABORT, 'issue origin is immutable');
END;
