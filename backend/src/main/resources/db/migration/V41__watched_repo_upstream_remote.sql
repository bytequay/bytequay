-- Name of the git remote inside the local clone that points at the
-- watched github.com/{owner}/{repo} (as opposed to the user's fork).
-- Populated by the Repos tab's locate / clone flow:
--   * direct clone → origin matches; column stays NULL because the
--     "upstream" concept doesn't apply.
--   * fork-based clone → origin is the user's fork; some other
--     remote (typically named "upstream") is the watched repo. We
--     record that name so Create-PR can default the base repo and
--     push commands can target the right place.
ALTER TABLE watched_repos ADD COLUMN upstream_remote_name TEXT;
