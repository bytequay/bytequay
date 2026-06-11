-- User-defined reviewer personas — each row is a (name, prompt, role)
-- bundle the Start Review dialog can pick from. The persona is
-- provider-agnostic: the dialog chooses which LLM provider runs each
-- persona per pass, so the same persona ("Trino style") can be served
-- by Claude on one pass and DeepSeek on the next.
--
-- Roles:
--   - LEAD: drafts the consensus and gets the final say in a debate
--     loop. Exactly one LEAD per pass is typical, though the panel
--     wiring tolerates zero (falls back to the first reviewer).
--   - REVIEWER: contributes findings; does not draft consensus.
--
-- is_active is a soft-delete flag so a persona that's referenced by
-- prior findings or pass rows doesn't dangle as a missing FK. The
-- dialog filters by is_active = 1 when populating the picker.
CREATE TABLE reviewer_personas (
    id              TEXT    PRIMARY KEY,
    name            TEXT    NOT NULL,
    system_prompt   TEXT    NOT NULL,
    role            TEXT    NOT NULL CHECK (role IN ('LEAD', 'REVIEWER')),
    is_active       INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at_ms   INTEGER NOT NULL,
    updated_at_ms   INTEGER NOT NULL
);

CREATE INDEX reviewer_personas_active_name_idx
    ON reviewer_personas(is_active, name);
