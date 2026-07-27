-- Raise workspaces that still carry the previous per-session default. New
-- and missing settings rows use the matching default in WorkspaceSettingsDto.
UPDATE workspace_settings
SET settings_json = json_set(settings_json, '$.sessionCapUsd', 100.0),
    updated_at_ms = strftime('%s','now') * 1000
WHERE json_extract(settings_json, '$.sessionCapUsd') = 1.0;
