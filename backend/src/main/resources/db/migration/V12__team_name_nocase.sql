-- Make team-name uniqueness case-insensitive. The original V11 schema
-- declared the column UNIQUE which produces a binary-comparison index, so
-- "Trino core" and "trino core" both fit. The new index enforces the
-- intended constraint at the DB level (in addition to the app-level check
-- in SqliteTeamStore via findByNameIgnoreCase).
CREATE UNIQUE INDEX IF NOT EXISTS uq_team_name_nocase ON team(name COLLATE NOCASE);
