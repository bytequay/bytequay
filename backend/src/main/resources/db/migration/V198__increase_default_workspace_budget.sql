-- Raise workspaces that still carry the previous daily default. New and
-- missing settings rows use the matching default in WorkspaceSettingsDto.
UPDATE workspace_settings
SET settings_json = json_set(settings_json, '$.dailyCapUsd', 500.0),
    updated_at_ms = strftime('%s','now') * 1000
WHERE json_extract(settings_json, '$.dailyCapUsd') = 10.0;
