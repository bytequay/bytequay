-- Per-repo review "skills" — extra system-prompt context applied when the
-- AI reviewer runs against a matching repo. llm_provider may be null
-- (skill applies to every provider) or set to a specific provider id to
-- force that provider for the run. skill_name and repo are both unique.
CREATE TABLE review_skill (
    id            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    skill_name    TEXT    NOT NULL UNIQUE,
    repo          TEXT    NOT NULL UNIQUE,
    llm_provider  TEXT,
    description   TEXT,
    context       TEXT,
    created_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX review_skill_repo_idx ON review_skill(repo);

-- Seed a default Trino skill — applies to every provider (llm_provider IS
-- NULL) so reviews of trinodb/trino pick up Trino's code-style context
-- regardless of which LLM is active.
INSERT INTO review_skill (skill_name, repo, llm_provider, description, context)
VALUES (
    'Trino code style',
    'trinodb/trino',
    NULL,
    'Default review context for the Trino repo — code style, commit-message conventions, and review priorities.',
    'You are reviewing a pull request against trinodb/trino. Apply Trino''s code style and review conventions:

- Java code style: 4-space indentation; no tabs; opening brace on its own line; one statement per line.
- Imports: java.* / javax.* / jakarta.* first, then everything else, alphabetical within each group; static imports last.
- Method ordering: public before private, instance before static where reasonable; keep related methods adjacent.
- Tests use JUnit 5 + Airlift testing utilities; avoid Mockito where a real test fixture is feasible.
- Prefer explicit types over var; avoid Optional in fields.
- Commit messages do NOT use Conventional Commits — use Trino''s standard imperative subject + body explaining motivation.
- Flag anything that looks like a regression in coordinator startup time, query planner cost, or worker memory pressure.
- Suggest using existing Trino utilities (e.g., io.airlift.* helpers, Trino''s own Slice/Block/Page abstractions) over hand-rolled equivalents.'
);
