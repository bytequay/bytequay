-- Local persistence for the home page's GitHub-sourced data. Each table is
-- a single-writer cache: the GithubHomeCacheRefreshJob ticks in the
-- background and overwrites rows after a successful GitHub fetch; the
-- /api/profile, /api/activity/{recent,following}, /api/stats, /api/user/orgs
-- endpoints read from these tables only and never call GitHub on the read
-- path. payload columns hold the JSON-serialised domain records (UserProfile,
-- List<RecentEvent>, UserStats, List<UserOrg>); fetched_at is the ISO-8601
-- timestamp of the last successful refresh, used by the scheduler to decide
-- whether the row is past its TTL.

CREATE TABLE github_user_profile_cache (
    login       TEXT NOT NULL PRIMARY KEY,
    payload     TEXT NOT NULL,
    fetched_at  TEXT NOT NULL
);

-- One row per (login, feed). feed ∈ {'RECENT','FOLLOWING'} and matches the
-- two GitHub endpoints we hit (/users/{login}/events vs /users/{login}/
-- received_events). payload holds the JSON list of RecentEvent records the
-- service originally returned, including its filtering (recent → current
-- month; following → trimmed to 10).
CREATE TABLE github_user_event_cache (
    login       TEXT NOT NULL,
    feed        TEXT NOT NULL,
    payload     TEXT NOT NULL,
    fetched_at  TEXT NOT NULL,
    PRIMARY KEY (login, feed)
);

CREATE TABLE github_user_stats_cache (
    login       TEXT NOT NULL PRIMARY KEY,
    payload     TEXT NOT NULL,
    fetched_at  TEXT NOT NULL
);

CREATE TABLE github_user_orgs_cache (
    login       TEXT NOT NULL PRIMARY KEY,
    payload     TEXT NOT NULL,
    fetched_at  TEXT NOT NULL
);
