-- Credentials vault: GitHub PAT and LLM API keys, plus local-LLM endpoints.
-- The `ciphertext` column holds AES-256-GCM encrypted plaintext; the master
-- key lives at ~/Library/Application Support/ByteQuay/credentials.key with
-- 0600 permissions and is auto-generated on first use. Raw secrets never hit
-- the database.
--
-- `preview` is a masked fragment suitable for display (e.g. "ghp_abc•••wxyz").
-- `last_used_at` is stamped whenever the backend decrypts the value to use it.
CREATE TABLE credentials (
    id            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    kind          TEXT    NOT NULL UNIQUE,
    label         TEXT,
    ciphertext    TEXT    NOT NULL,
    preview       TEXT    NOT NULL,
    notes         TEXT,
    created_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at  TEXT
);
