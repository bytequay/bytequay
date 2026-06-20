-- Surface visits — a local navigation signal that powers "Today's
-- footprints" on the home page. One row per visit: whenever the user
-- opens a tracked surface (the PR kanban, a PR, a task, a thread) the
-- renderer fires a fire-and-forget write here. The trail reads these
-- back, grouped by surface for a calendar day.
--
-- Denormalized on purpose: `title` and `context` are a point-in-time
-- snapshot of the label so a stop still renders even if the surface is
-- later renamed or deleted. `surface_id` is the renderer's navigable
-- key (e.g. "owner/repo#5680", "threadId/taskId") that the resume
-- handler parses to re-open the surface — the backend never interprets
-- it. `visited_at_ms` is epoch milliseconds, matching every other
-- timestamp column in this schema.
CREATE TABLE surface_visits (
    id              TEXT    NOT NULL PRIMARY KEY,
    surface_type    TEXT    NOT NULL,   -- PR_KANBAN | PR | TASK | THREAD
    surface_id      TEXT    NOT NULL,
    title           TEXT,
    context         TEXT,
    visited_at_ms   INTEGER NOT NULL
);

-- Day-range scan for "today's" (and prior days') trail.
CREATE INDEX idx_surface_visits_visited_at ON surface_visits (visited_at_ms);

-- Grouping the day's visits by surface for the merged "N×" pin.
CREATE INDEX idx_surface_visits_surface ON surface_visits (surface_type, surface_id);
