-- Optional one-line description on a team. Surfaced in the team
-- sidebar card and inside the New Team modal's live preview so the
-- "what does Trino core actually do?" question has a place to live.
ALTER TABLE team ADD COLUMN description TEXT;
