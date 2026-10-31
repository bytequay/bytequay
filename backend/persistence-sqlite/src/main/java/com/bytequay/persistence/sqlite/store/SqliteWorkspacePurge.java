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
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.persistence.sqlite.entity.BackportChainEntity;
import com.bytequay.persistence.sqlite.entity.RemoteSourceEntity;
import com.bytequay.persistence.sqlite.entity.WorkspaceEntity;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
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
        checkState(!exists("SELECT EXISTS (SELECT 1 FROM agent_task WHERE id IN (" + workspaceTaskIds() + "))", parameters), "Workspace still has agent tasks: %s", id);

        List<Workspace> others = entities.createQuery("FROM WorkspaceEntity WHERE id <> :id", WorkspaceEntity.class)
                .setParameter("id", id).getResultList().stream().map(WorkspaceEntity::toDomain).collect(toImmutableList());
        List<RemoteSource> remotes = entities.createQuery("FROM RemoteSourceEntity", RemoteSourceEntity.class)
                .getResultList().stream().map(RemoteSourceEntity::toDomain).collect(toImmutableList());
        // Commit rows belong to the hosting repositories a workspace reads commits from; one another workspace still reads keeps them.
        Set<Long> commitRepositories = commitRepositories(workspace, remotes);
        Set<Long> retainedCommitRepositories = others.stream().flatMap(other -> commitRepositories(other, remotes).stream()).collect(toImmutableSet());

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
        // An issue's key names its workspace, so its Recent rows go with it.
        for (String table : ImmutableList.of("recent_touch", "recent_text")) {
            update("DELETE FROM " + table + " WHERE item_type = 'ISSUE' AND item_key LIKE 'issue:' || :workspaceId || ':%'", parameters);
        }
        reassignPullRequestTouches(id);
        update("DELETE FROM work_item_identity WHERE work_item_id IN (SELECT id FROM work_item WHERE workspace_id = :workspaceId)", parameters);
        for (String table : ImmutableList.of(
                "workspace_remote_sources", "workspace_credentials", "commit_refresh_metadata", "epic", "work_item",
                "repository_memory")) {
            update("DELETE FROM " + table + " WHERE workspace_id = :workspaceId", parameters);
        }
        // Matched by prefix, not by exact key: these settings are per workspace and now also per
        // agent type, so an exact list silently leaks every suffix a later type adds.
        for (String prefix : ImmutableList.of("workspace.agentModel." + id, "workspace.commitsTargetBranch." + id, "workspace.memory." + id,
                "workspace.relationships." + id, "workspace.linked-work." + id, "workspace.memoryHarvest." + id, "workspace.reviewReplays." + id)) {
            update("DELETE FROM app_settings WHERE setting_key = :key OR setting_key LIKE :pattern", ImmutableMap.of("key", prefix, "pattern", prefix + ".%"));
        }
        update("DELETE FROM app_settings WHERE setting_key LIKE 'linked-work.source.%' AND setting_value = :workspaceId", parameters);
        commitRepositories.stream().filter(repositoryId -> !retainedCommitRepositories.contains(repositoryId))
                .forEach(repositoryId -> update("DELETE FROM commit_enrichment WHERE hosting_repository_id = :repositoryId", ImmutableMap.of("repositoryId", repositoryId)));
        deleteHostedRepositoryData(id);
        update("DELETE FROM watched_repo_workspaces WHERE id = :workspaceId", parameters);
        entities.clear();
    }

    /** A pull request's touches move to another workspace on its repository, a watched one first, else to none. */
    private void reassignPullRequestTouches(String workspaceId)
    {
        Map<String, Object> parameters = ImmutableMap.of("workspaceId", workspaceId);
        List<WorkspaceEntity> others = entities.createQuery("FROM WorkspaceEntity WHERE id <> :id ORDER BY watched DESC, displayOrder, createdAt", WorkspaceEntity.class)
                .setParameter("id", workspaceId).getResultList();
        List<?> repositories = query("""
                SELECT DISTINCT p.hosting_repository_id FROM recent_touch t JOIN pull_requests p ON t.item_key = 'pr:' || p.id
                WHERE t.workspace_id = :workspaceId AND t.item_type = 'PULL_REQUEST'
                """, parameters).getResultList();
        for (Object repository : repositories) {
            long repositoryId = ((Number) repository).longValue();
            others.stream().filter(other -> hostingRepositoryId(other).filter(id -> id == repositoryId).isPresent()).findFirst().ifPresent(heir -> update("""
                    UPDATE recent_touch SET workspace_id = :heir WHERE workspace_id = :workspaceId AND item_type = 'PULL_REQUEST'
                        AND item_key IN (SELECT 'pr:' || id FROM pull_requests WHERE hosting_repository_id = :repositoryId)
                    """, ImmutableMap.of("heir", heir.getId(), "workspaceId", workspaceId, "repositoryId", repositoryId)));
        }
        update("UPDATE recent_touch SET workspace_id = NULL WHERE workspace_id = :workspaceId", parameters);
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

    /** The hosting repositories on the workspace's server it reads commits from: its own, its upstream and its linked remotes. */
    private Set<Long> commitRepositories(Workspace workspace, List<RemoteSource> remotes)
    {
        Set<String> remoteIds = Stream.concat(Stream.of(workspace.selectedRepositoryId(), workspace.monitoredRepositoryId()),
                        remotes.stream().filter(remote -> remote.workspaceId().equals(workspace.id())).flatMap(remote -> remote.repositoryId().stream()))
                .map(String::valueOf)
                .collect(toImmutableSet());
        HostingServer server = workspace.server();
        return ImmutableSet.copyOf(entities.createQuery("""
                        SELECT r.id FROM HostingRepositoryEntity r
                        WHERE r.instance.provider = :provider AND r.instance.webBaseUrl = :webBaseUrl AND r.remoteId IN :remoteIds
                        """, Long.class)
                .setParameter("provider", server.provider())
                .setParameter("webBaseUrl", server.webBaseUrl().toASCIIString())
                .setParameter("remoteIds", remoteIds)
                .getResultList());
    }

    private void deleteHostedRepositoryData(String workspaceId)
    {
        Optional<Long> repositoryId = hostingRepositoryId(entities.find(WorkspaceEntity.class, workspaceId));
        Set<Long> retained = entities.createQuery("FROM WorkspaceEntity WHERE id <> :id", WorkspaceEntity.class).setParameter("id", workspaceId)
                .getResultList().stream().flatMap(workspace -> hostingRepositoryId(workspace).stream()).collect(toImmutableSet());
        repositoryId.filter(id -> !retained.contains(id)).ifPresent(id -> {
            for (String entity : ImmutableList.of("RepositoryIssueEntity", "RepositoryIssueDetailEntity", "RepositoryIssueSyncEntity", "RepositoryMetadataEntity")) {
                entities.createQuery("DELETE FROM " + entity + " WHERE repositoryId = :id").setParameter("id", id).executeUpdate();
            }
            deletePullRequests(id);
        });
    }

    private void deletePullRequests(long repositoryId)
    {
        Map<String, Object> parameters = ImmutableMap.of("repositoryId", repositoryId);
        // Dashboard rows, explicitly monitored PRs, and local review drafts belong to the account independently of watching.
        String pullRequests = "SELECT p.id FROM pull_requests p WHERE p.hosting_repository_id = :repositoryId"
                + " AND p.dashboard_visible = 0 AND p.monitored_at IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM pr_review_draft d WHERE lower(d.repo) = lower(p.repo) AND d.number = p.number)"
                + " AND NOT EXISTS (SELECT 1 FROM agent_review r WHERE lower(r.repo) = lower(p.repo) AND r.number = p.number)";
        for (String table : ImmutableList.of("recent_touch", "recent_text")) {
            update("DELETE FROM " + table + " WHERE item_type = 'PULL_REQUEST' AND item_key IN (SELECT 'pr:' || id FROM pull_requests WHERE id IN (" + pullRequests + "))", parameters);
        }
        for (String table : ImmutableList.of("pr_check_runs", "pr_reviews", "pr_timeline", "pr_diff", "pr_detail", "pr_view_state", "pr_metadata_snapshots")) {
            update("DELETE FROM " + table + " WHERE pr_id IN (" + pullRequests + ")", parameters);
        }
        update("DELETE FROM pull_requests WHERE id IN (" + pullRequests + ")", parameters);
    }

    private static Optional<Long> hostingRepositoryId(WorkspaceEntity workspace)
    {
        return workspace.hostingBinding().map(binding -> binding.monitoredRepositoryId()).or(() -> workspace.hostingCacheTarget().map(target -> target.repositoryId()));
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
