/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.testing;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Completes a hand-seeded V2 task so the current schema accepts it.
 *
 * <p>A task assignment no longer stands on its own: it has to point at a trunk
 * authorization whose owner graph — trunk, workspace, policy revision and workspace
 * repository — matches exactly, and triggers check all of it on insert. Tests used to
 * get this for free by seeding at an older schema version and letting the migration
 * chain fill the newer columns in; with a squashed baseline there is no chain to do
 * that, so the seed has to be right the first time.
 *
 * <p>Call {@link #authorize} after inserting an assignment that carries a
 * {@code creation_authorization_id}. Everything else is derived from what is already
 * in the database, so callers do not have to restate the graph.
 */
public final class V2TaskSeed
{
    /**
     * The owner graph is checked against the trunk workspace's repository, which most
     * fixtures never had a reason to seed before. One repository per workspace and one
     * workspace per repository are both unique, so this tolerates already being set.
     */
    private static final String SEED_PRIMARY_REPOSITORY = """
            INSERT OR IGNORE INTO workspace_repos(
                workspace_id, repo_full_name, default_base_branch, added_at_ms)
            SELECT workspace.id, 'acme/widget', 'main', 1 FROM workspaces workspace
            """;

    /**
     * Only one workspace can hold a given repository, so any further workspace gets one
     * named after itself rather than silently going without.
     */
    private static final String SEED_REMAINING_REPOSITORIES = """
            INSERT OR IGNORE INTO workspace_repos(
                workspace_id, repo_full_name, default_base_branch, added_at_ms)
            SELECT workspace.id, 'acme/' || workspace.id, 'main', 1
            FROM workspaces workspace
            WHERE NOT EXISTS (
                SELECT 1 FROM workspace_repos existing
                WHERE existing.workspace_id = workspace.id)
            """;

    private static final String ADVANCE_TRUNK = """
            UPDATE threads
            SET aggregate_version = aggregate_version + 1
            WHERE id = (SELECT trunk_id FROM task_assignment WHERE id = '%1$s')
            """;

    /**
     * The authorization mirrors the assignment it belongs to. Base source has to agree
     * with the shape the assignment was seeded in, because a table CHECK pins which of
     * base_ref / planning_base_sha / the assignment shas may be set for each one.
     */
    private static final String AUTHORIZE = """
            INSERT INTO trunk_task_creation_authorization(
                id, trunk_id, workspace_id, command_id, actor, disposition,
                expected_trunk_version, returned_trunk_version, returned_lifecycle,
                assignment_id, policy_revision_id, provenance,
                repository_id, publish_repository_id, base_source,
                base_repository_id, base_ref, planning_base_sha,
                engine_snapshot, work_model_snapshot, recorded_at_ms)
            SELECT
                assignment.creation_authorization_id,
                trunk.id,
                trunk.workspace_id,
                'command-' || assignment.id,
                'test',
                'AUTHORIZED',
                trunk.aggregate_version - 1,
                trunk.aggregate_version,
                trunk.lifecycle_state,
                assignment.id,
                policy.id,
                'DIRECT_USER',
                repository.repo_full_name,
                repository.repo_full_name,
                CASE
                    WHEN assignment.kind <> 'NEW_FROM_TRUNK' THEN 'EXISTING_PR_HEAD'
                    WHEN assignment.planning_base_sha IS NOT NULL
                        THEN 'PLANNING_SNAPSHOT'
                    ELSE 'FRESH_REMOTE_BASE'
                END,
                repository.repo_full_name,
                CASE WHEN assignment.kind = 'NEW_FROM_TRUNK' THEN 'main' END,
                CASE WHEN assignment.kind = 'NEW_FROM_TRUNK'
                    THEN assignment.planning_base_sha END,
                'engine',
                '{"kind":"API","agentOrProvider":"openai",'
                    || '"model":"review-model","account":null,'
                    || '"reasoningEffort":null}',
                assignment.created_at_ms
            FROM task_assignment assignment
            JOIN threads trunk ON trunk.id = assignment.trunk_id
            JOIN workspace_repos repository
              ON repository.workspace_id = trunk.workspace_id
            JOIN task_policy_revision policy ON policy.trunk_id = trunk.id
            WHERE assignment.id = '%1$s'
              AND policy.revision = (
                  SELECT MAX(newer.revision) FROM task_policy_revision newer
                  WHERE newer.trunk_id = trunk.id)
            """;

    private V2TaskSeed() {}

    /**
     * Gives every seeded workspace a repository. Must run before the assignment insert:
     * the assignment trigger itself demands a live V2 trunk workspace with a repository,
     * so doing it as part of authorizing afterwards is already too late.
     */
    public static void prepareWorkspaces(Connection connection)
            throws SQLException
    {
        execute(connection, SEED_PRIMARY_REPOSITORY);
        execute(connection, SEED_REMAINING_REPOSITORIES);
    }

    public static void prepareWorkspaces(JdbcTemplate jdbc)
    {
        jdbc.update(SEED_PRIMARY_REPOSITORY);
        jdbc.update(SEED_REMAINING_REPOSITORIES);
    }

    public static void authorize(Connection connection, String assignmentId)
            throws SQLException
    {
        execute(connection, ADVANCE_TRUNK.formatted(assignmentId));
        execute(connection, AUTHORIZE.formatted(assignmentId));
    }

    public static void authorize(JdbcTemplate jdbc, String assignmentId)
    {
        jdbc.update(ADVANCE_TRUNK.formatted(assignmentId));
        jdbc.update(AUTHORIZE.formatted(assignmentId));
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
