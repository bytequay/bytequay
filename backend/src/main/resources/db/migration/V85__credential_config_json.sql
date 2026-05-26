-- MCP credentials need structured side-data the existing columns
-- can't carry — transport (remote | local), auth-kind (oauth |
-- bearer) for remote, launch command + env-var name for local,
-- server URL for remote. Rather than bolt on five MCP-only columns
-- that stay null for ACCOUNT / AI / REPO rows, add a single
-- nullable config_json blob the MCP rows use.
--
-- LLM / Git-PAT / OAuth rows ignore the column (it stays null);
-- the service layer parses + emits this as JSON.

ALTER TABLE credentials ADD COLUMN config_json TEXT;
