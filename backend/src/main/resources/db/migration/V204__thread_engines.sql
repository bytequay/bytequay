-- Per-trunk engine overrides, keyed by session audience.
--
-- The engine (CLI agent / API provider) is normally the workspace's
-- call: one workspace runs one set of agents, and a thread / task /
-- stage only dials reasoning effort. The new-trunk dialog is the one
-- place that can pin a different agent for a session kind, and the pin
-- applies to that trunk alone — rows exist only for the kinds the
-- creator actually swapped, so an untouched trunk keeps inheriting.
--
-- `choice` holds the same picker id the workspace settings page writes
-- (`cli:<agent>`, `api:<provider>[:<account>]`, `local`) so both sides
-- parse through WorkspaceEngineSettings.parseChoice.

CREATE TABLE thread_engines (
    thread_id  TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,

    -- One of the four session audiences: plan / dev / review / ci-fix.
    audience   TEXT NOT NULL,

    choice     TEXT NOT NULL,

    PRIMARY KEY (thread_id, audience)
);
