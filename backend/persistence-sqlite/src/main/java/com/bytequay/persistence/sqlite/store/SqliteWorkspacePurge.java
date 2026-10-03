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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.persistence.sqlite.entity.BackportChainEntity;
import com.bytequay.persistence.sqlite.entity.RemoteSourceEntity;
import com.bytequay.persistence.sqlite.entity.WorkspaceEntity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** The fixed SQLite ownership graph removed by the explicit workspace Delete action. */
@Component
final class SqliteWorkspacePurge
{
    private final EntityManager entities;

    SqliteWorkspacePurge(EntityManager entities)
    {
        this.entities = requireNonNull(entities, "entities is null");
    }

    boolean hasActiveWorkflows(String workspaceId)
    {
        if (entities.createQuery("FROM BackportChainEntity", BackportChainEntity.class).getResultList().stream()
                .map(BackportChainEntity::toDomain).anyMatch(chain -> chain.involves(workspaceId)
                        && chain.state() != BackportChain.State.STOPPED && chain.state() != BackportChain.State.COMPLETED)) {
            return true;
        }
        Map<String, Object> parameters = ImmutableMap.of("workspaceId", workspaceId);
        String tasks = workspaceTaskIds();
        String query = """
                SELECT EXISTS (
                    SELECT 1 FROM cherry_pick_execution
                    WHERE (workspace_id = :workspaceId OR agent_task_id IN (%s)) AND terminal_at IS NULL
                ) OR EXISTS (
                    SELECT 1 FROM ci_autofix_run
                    WHERE (workspace_id = :workspaceId OR agent_task_id IN (%s)) AND terminal_at IS NULL
                ) OR EXISTS (
                    SELECT 1 FROM branch_rebase WHERE agent_task_id IN (%s) AND terminal_at IS NULL
                ) OR EXISTS (
                    SELECT 1 FROM agent_review_run r JOIN agent_review v ON v.id = r.review_id
                    WHERE v.workspace_id = :workspaceId AND r.state = 'RUNNING'
                )
                """.formatted(tasks, tasks, tasks);
        return exists(query, parameters);
    }

    void delete(Workspace workspace)
    {
        String id = workspace.id();
        Map<String, Object> parameters = ImmutableMap.of("workspaceId", id);
        entities.flush();
        // Acquire SQLite's writer lock before checking that no task/workflow was started during filesystem cleanup.
        update("UPDATE watched_repo_workspaces SET watched = watched WHERE id = :workspaceId", parameters);
        checkState(!hasActiveWorkflows(id), "Workspace has an active workflow: %s", id);
        checkState(!exists("SELECT EXISTS (SELECT 1 FROM agent_task WHERE id IN (" + workspaceTaskIds() + "))", parameters),
                "Workspace still has agent tasks: %s", id);

        List<Workspace> others = entities.createQuery("FROM WorkspaceEntity WHERE id <> :id", WorkspaceEntity.class)
                .setParameter("id", id).getResultList().stream().map(WorkspaceEntity::toDomain).collect(toImmutableList());
        List<RemoteSource> remotes = entities.createQuery("FROM RemoteSourceEntity", RemoteSourceEntity.class)
                .getResultList().stream().map(RemoteSourceEntity::toDomain).collect(toImmutableList());
        Map<Long, RepoRef> repositories = new LinkedHashMap<>();
        repositories.put(workspace.selectedRepositoryId(), workspace.selectedRepository());
        repositories.put(workspace.monitoredRepositoryId(), workspace.monitoredRepository());
        workspace.upstreamRepositoryId().ifPresent(repositoryId -> repositories.put(repositoryId, workspace.upstreamRepository().orElseThrow()));
        remotes.stream().filter(remote -> remote.workspaceId().equals(id)).forEach(remote -> remote.repositoryId()
                .ifPresent(repositoryId -> repositories.put(repositoryId, remote.repository().orElseThrow())));

        update("DELETE FROM branch_rebase WHERE agent_task_id IN (" + workspaceTaskIds() + ")", parameters);
        update("DELETE FROM ci_autofix_attempt WHERE run_id IN (SELECT id FROM ci_autofix_run WHERE workspace_id = :workspaceId)", parameters);
        update("DELETE FROM ci_autofix_run WHERE workspace_id = :workspaceId", parameters);
        update("DELETE FROM cherry_pick_execution WHERE workspace_id = :workspaceId", parameters);
        update("DELETE FROM local_run_output WHERE run_id IN (SELECT id FROM local_run WHERE workspace_id = :workspaceId)", parameters);
        update("DELETE FROM local_run WHERE workspace_id = :workspaceId", parameters);
        for (String table : ImmutableList.of("agent_review_finding", "agent_review_message", "agent_review_run")) {
            update("DELETE FROM " + table + " WHERE review_id IN (SELECT id FROM agent_review WHERE workspace_id = :workspaceId)", parameters);
        }
        update("DELETE FROM agent_review WHERE workspace_id = :workspaceId", parameters);
        update("DELETE FROM recent_text WHERE item_key IN (SELECT item_key FROM recent_touch WHERE workspace_id = :workspaceId AND item_type = 'TASK')", parameters);
        update("DELETE FROM recent_touch WHERE workspace_id = :workspaceId AND item_type IN ('TASK', 'COMMIT')", parameters);
        update("""
                UPDATE recent_touch SET workspace_id = (
                    SELECT id FROM watched_repo_workspaces w WHERE w.id <> :workspaceId
                        AND lower(w.monitored_owner || '/' || w.monitored_repo) = lower(recent_touch.repo)
                    ORDER BY w.watched DESC, w.display_order, w.created_at LIMIT 1
                ) WHERE workspace_id = :workspaceId
                """, parameters);
        update("DELETE FROM work_item_identity WHERE work_item_id IN (SELECT id FROM work_item WHERE workspace_id = :workspaceId)", parameters);
        for (String table : ImmutableList.of("workspace_remote_sources", "workspace_credentials", "commit_refresh_metadata", "goal", "work_item", "repository_memory")) {
            update("DELETE FROM " + table + " WHERE workspace_id = :workspaceId", parameters);
        }
        // Matched by prefix, not by exact key: these settings are per workspace and now also per
        // agent type, so an exact list silently leaks every suffix a later type adds.
        for (String prefix : ImmutableList.of("workspace.agentModel." + id, "workspace.commitsTargetBranch." + id, "workspace.memory." + id,
                "workspace.relationships." + id, "workspace.linked-work." + id, "workspace.memoryHarvest." + id, "workspace.reviewReplays." + id)) {
            update(
                    "DELETE FROM app_settings WHERE setting_key = :key OR setting_key LIKE :pattern",
                    ImmutableMap.of("key", prefix, "pattern", prefix + ".%"));
        }
        update("DELETE FROM app_settings WHERE setting_key LIKE 'linked-work.source.%' AND setting_value = :workspaceId", parameters);
        for (Map.Entry<Long, RepoRef> repository : repositories.entrySet()) {
            if (!referencedElsewhere(id, repository.getKey(), repository.getValue(), others, remotes)) {
                deleteRepositoryData(repository.getKey(), repository.getValue());
            }
        }
        update("DELETE FROM watched_repo_workspaces WHERE id = :workspaceId", parameters);
        entities.clear();
    }

    private String workspaceTaskIds()
    {
        return """
                SELECT id FROM agent_task WHERE workspace_id = :workspaceId
                UNION SELECT agent_task_id FROM ci_autofix_run WHERE workspace_id = :workspaceId
                UNION SELECT agent_task_id FROM cherry_pick_execution WHERE workspace_id = :workspaceId
                UNION SELECT task_id FROM agent_review WHERE workspace_id = :workspaceId
                """;
    }

    private static boolean referencedElsewhere(String workspaceId, long repositoryId, RepoRef repository, List<Workspace> others, List<RemoteSource> remotes)
    {
        return others.stream().anyMatch(workspace -> workspace.selectedRepositoryId() == repositoryId
                        || workspace.monitoredRepositoryId() == repositoryId
                        || workspace.upstreamRepositoryId().filter(id -> id == repositoryId).isPresent()
                        || workspace.selectedRepository().equals(repository)
                        || workspace.monitoredRepository().equals(repository)
                        || workspace.upstreamRepository().filter(repository::equals).isPresent())
                || remotes.stream().filter(remote -> !remote.workspaceId().equals(workspaceId))
                        .anyMatch(remote -> remote.repositoryId().filter(id -> id == repositoryId).isPresent()
                                || remote.repository().filter(repository::equals).isPresent()
                                || referencesGitHubRepository(remote.url(), repository));
    }

    private static boolean referencesGitHubRepository(String url, RepoRef repository)
    {
        String normalized = url.toLowerCase(Locale.ROOT).replaceFirst("(?:\\.git)?/?$", "");
        String fullName = repository.fullName().toLowerCase(Locale.ROOT);
        return normalized.equals("https://github.com/" + fullName)
                || normalized.equals("ssh://git@github.com/" + fullName)
                || normalized.equals("git@github.com:" + fullName);
    }

    private void deleteRepositoryData(long repositoryId, RepoRef repository)
    {
        Map<String, Object> parameters = ImmutableMap.of("repositoryId", repositoryId, "repo", repository.fullName().toLowerCase(Locale.ROOT));
        for (String table : ImmutableList.of("repository_issue_details", "repository_issue_rows", "repository_issue_sync", "repository_metadata", "commit_enrichment")) {
            update("DELETE FROM " + table + " WHERE repository_id = :repositoryId", parameters);
        }
        for (String table : ImmutableList.of("recent_touch", "recent_text")) {
            update("DELETE FROM " + table + " WHERE item_type = 'ISSUE' AND lower(repo) = :repo", parameters);
        }
        // Dashboard rows, explicitly monitored PRs, and local review drafts belong to the account independently of watching.
        String pullRequests = "SELECT p.id FROM pull_requests p WHERE lower(p.repo) = :repo AND p.dashboard_visible = 0 AND p.monitored_at IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM pr_review_draft d WHERE lower(d.repo) = lower(p.repo) AND d.number = p.number)"
                + " AND NOT EXISTS (SELECT 1 FROM agent_review r WHERE lower(r.repo) = lower(p.repo) AND r.number = p.number)";
        for (String table : ImmutableList.of("recent_touch", "recent_text")) {
            update("DELETE FROM " + table + " WHERE item_type = 'PULL_REQUEST' AND lower(item_key) IN "
                    + "(SELECT 'pr:' || lower(repo) || '#' || number FROM pull_requests WHERE id IN (" + pullRequests + "))", parameters);
        }
        for (String table : ImmutableList.of("pr_check_runs", "pr_reviews", "pr_timeline", "pr_diff", "pr_detail", "pr_view_state")) {
            update("DELETE FROM " + table + " WHERE pr_id IN (" + pullRequests + ")", parameters);
        }
        update("DELETE FROM pull_requests WHERE id IN (" + pullRequests + ")", parameters);
        update("UPDATE pull_requests SET workspace_visible = 0, workspace_sync_marker = NULL WHERE lower(repo) = :repo", parameters);
    }

    private boolean exists(String statement, Map<String, Object> parameters)
    {
        Object result = query(statement, parameters).getSingleResult();
        return result instanceof Boolean value ? value : ((Number) result).intValue() != 0;
    }

    private void update(String statement, Map<String, Object> parameters)
    {
        query(statement, parameters).executeUpdate();
    }

    private Query query(String statement, Map<String, Object> parameters)
    {
        Query query = entities.createNativeQuery(statement);
        query.getParameters().forEach(parameter -> query.setParameter(parameter.getName(), parameters.get(parameter.getName())));
        return query;
    }
}
