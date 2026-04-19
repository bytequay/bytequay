-- Allow multiple credentials per (type, name) pair — distinguished by an
-- instance_name. The driving case is "I have two DeepSeek keys" but the
-- column applies to all credential types so the schema stays uniform.
-- Existing rows backfill to 'default api', which is also the default for
-- new inserts that don't specify a name.
--
-- The old uniqueness constraint was (type, name); the new one extends to
-- (type, name, instance_name). ACCOUNT and REPO credentials remain
-- effectively singletons in practice — the resolver always passes
-- instance_name='default api' for those — but nothing in the schema
-- forbids future multi-instance use if the design calls for it.
ALTER TABLE credentials ADD COLUMN instance_name TEXT NOT NULL DEFAULT 'default api';
DROP INDEX IF EXISTS uq_credentials_type_name;
CREATE UNIQUE INDEX uq_credentials_type_name_instance ON credentials(type, name, instance_name);
