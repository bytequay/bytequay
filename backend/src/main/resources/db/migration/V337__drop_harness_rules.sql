-- The knowledge base is prose memory in knowledge_item now, written by the agent
-- after CI confirms a fix. This table held the rule/recipe form it replaced:
-- matcher patterns, candidate/active/retired promotion and recipe bindings. The
-- last writer to it was deleted with the diagnosis loop, so every row it could
-- still hold is unreachable.
DROP TABLE IF EXISTS ci_harness_rule;
