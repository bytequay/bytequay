-- New trunks snapshot the complete work model for every session audience.
-- Keep `choice` for legacy rows and compact UI identity; old sparse rows
-- continue to resolve through it because their create-time model cannot be
-- reconstructed after the fact.

ALTER TABLE thread_engines ADD COLUMN work_model_json TEXT;
