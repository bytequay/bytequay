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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.WorkspaceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteWorkspaceStore
        implements WorkspaceStore
{
    /** Thread statuses we treat as "alive" for the landing card's
     *  activeThreadCount. Terminal rows are excluded; the parked
     *  AWAITING_REVIEW / NEEDS_ATTENTION states stay in because they
     *  still belong to the workspace's live surface. */
    private static final List<String> ACTIVE_THREAD_STATUSES = List.of(
            ThreadStatus.PENDING.name(),
            ThreadStatus.RUNNING.name(),
            ThreadStatus.AWAITING.name(),
            ThreadStatus.IDLE.name(),
            ThreadStatus.AWAITING_REVIEW.name(),
            ThreadStatus.NEEDS_ATTENTION.name());

    /** Task statuses that count as in-flight on a workspace card. Same
     *  shape as ACTIVE_THREAD_STATUSES but on the task lifecycle. */
    private static final List<String> IN_FLIGHT_TASK_STATUSES = List.of(
            TaskStatus.PENDING.name(),
            TaskStatus.RUNNING.name(),
            TaskStatus.AWAITING.name(),
            TaskStatus.IDLE.name(),
            TaskStatus.AWAITING_REVIEW.name(),
            TaskStatus.NEEDS_ATTENTION.name());

    /** Task statuses that drive the amber "needs you" chip. Both
     *  parked states qualify per the design's "AWAITING_REVIEW /
     *  NEEDS_ATTENTION" UI rules. */
    private static final List<String> PARKED_TASK_STATUSES = List.of(
            TaskStatus.AWAITING_REVIEW.name(),
            TaskStatus.NEEDS_ATTENTION.name());

    private final WorkspaceJpaRepository workspaces;
    private final WorkspaceRepoJpaRepository repos;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    SqliteWorkspaceStore(
            WorkspaceJpaRepository workspaces,
            WorkspaceRepoJpaRepository repos,
            ObjectMapper objectMapper)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.repos = requireNonNull(repos, "repos is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    @Transactional
    public void saveWorkspace(Workspace workspace)
    {
        WorkspaceEntity entity = workspaces.findById(workspace.id()).orElseGet(WorkspaceEntity::new);
        entity.setId(workspace.id());
        entity.setName(workspace.name());
        entity.setMemoryMd(workspace.memoryMd());
        entity.setIsScratch(workspace.isScratch() ? 1 : 0);
        entity.setWorkModelJson(serialiseWorkModel(workspace.workModel()));
        entity.setCreatedAtMs(workspace.createdAt().toEpochMilli());
        entity.setUpdatedAtMs(workspace.updatedAt().toEpochMilli());
        workspaces.save(entity);
    }

    @Override
    public Optional<Workspace> findWorkspaceById(String id)
    {
        return workspaces.findById(id).map(this::toWorkspace);
    }

    @Override
    public List<Workspace> listWorkspaces()
    {
        return workspaces.findAllByOrderByUpdatedAtMsDesc().stream()
                .map(this::toWorkspace)
                .toList();
    }

    @Override
    @Transactional
    public void deleteWorkspace(String id)
    {
        workspaces.deleteById(id);
    }

    @Override
    @Transactional
    public void addRepo(WorkspaceRepo repo)
    {
        WorkspaceRepoEntity.WorkspaceRepoKey key =
                new WorkspaceRepoEntity.WorkspaceRepoKey(repo.workspaceId(), repo.repoFullName());
        WorkspaceRepoEntity entity = repos.findById(key).orElseGet(WorkspaceRepoEntity::new);
        entity.setId(key);
        entity.setDefaultBaseBranch(repo.defaultBaseBranch());
        entity.setAutoFixEnabled(repo.autoFixEnabled() ? 1 : 0);
        entity.setAddedAtMs(repo.addedAt().toEpochMilli());
        repos.save(entity);
    }

    @Override
    @Transactional
    public void removeRepo(String workspaceId, String repoFullName)
    {
        WorkspaceRepoEntity.WorkspaceRepoKey key =
                new WorkspaceRepoEntity.WorkspaceRepoKey(workspaceId, repoFullName);
        if (repos.existsById(key)) {
            repos.deleteById(key);
        }
    }

    @Override
    public List<WorkspaceRepo> listRepos(String workspaceId)
    {
        return repos.findByIdWorkspaceIdOrderByAddedAtMsAsc(workspaceId).stream()
                .map(SqliteWorkspaceStore::toRepo)
                .toList();
    }

    @Override
    public Optional<WorkspaceRepo> findRepo(String workspaceId, String repoFullName)
    {
        WorkspaceRepoEntity.WorkspaceRepoKey key =
                new WorkspaceRepoEntity.WorkspaceRepoKey(workspaceId, repoFullName);
        return repos.findById(key).map(SqliteWorkspaceStore::toRepo);
    }

    @Override
    @Transactional
    public void setDefaultBaseBranch(String workspaceId, String repoFullName, String defaultBaseBranch)
    {
        WorkspaceRepoEntity.WorkspaceRepoKey key =
                new WorkspaceRepoEntity.WorkspaceRepoKey(workspaceId, repoFullName);
        repos.findById(key).ifPresent(entity -> {
            entity.setDefaultBaseBranch(defaultBaseBranch);
            repos.save(entity);
        });
    }

    @Override
    public WorkspaceStats fetchStats(String workspaceId, long sinceMs)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        int activeThreads = countActiveThreads(workspaceId);
        TaskAggregates tasks = aggregateTasks(workspaceId, sinceMs);
        Long lastActivity = maxThreadUpdatedAt(workspaceId);
        return new WorkspaceStats(
                activeThreads,
                tasks.inFlight(),
                tasks.parked(),
                tasks.spendMilliUsd(),
                lastActivity);
    }

    private int countActiveThreads(String workspaceId)
    {
        // SQLite's COUNT returns BIGINT; widen with Number to dodge a
        // ClassCastException when the JDBC driver hands back Long here
        // and Integer on other rows.
        Object raw = entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM threads "
                                + "WHERE workspace_id = :workspaceId "
                                + "AND status IN (:statuses)")
                .setParameter("workspaceId", workspaceId)
                .setParameter("statuses", ACTIVE_THREAD_STATUSES)
                .getSingleResult();
        return raw instanceof Number n ? n.intValue() : 0;
    }

    private TaskAggregates aggregateTasks(String workspaceId, long sinceMs)
    {
        // One round-trip via conditional SUMs: counts and the spend
        // accumulator all share the same threads→tasks JOIN. Cheaper
        // than three separate queries and keeps the workspace_id
        // join cost paid once. The CASE expressions return 1/0 for
        // counts and the cost field / 0 for the spend sum.
        Object[] row = (Object[]) entityManager.createNativeQuery(
                        "SELECT "
                                + "  COALESCE(SUM(CASE WHEN t.status IN (:inFlight) THEN 1 ELSE 0 END), 0), "
                                + "  COALESCE(SUM(CASE WHEN t.status IN (:parked) THEN 1 ELSE 0 END), 0), "
                                + "  COALESCE(SUM(CASE WHEN t.created_at_ms >= :sinceMs THEN t.cost_usd_milli ELSE 0 END), 0) "
                                + "FROM tasks t "
                                + "JOIN threads th ON th.id = t.thread_id "
                                + "WHERE th.workspace_id = :workspaceId")
                .setParameter("workspaceId", workspaceId)
                .setParameter("inFlight", IN_FLIGHT_TASK_STATUSES)
                .setParameter("parked", PARKED_TASK_STATUSES)
                .setParameter("sinceMs", sinceMs)
                .getSingleResult();
        int inFlight = row[0] instanceof Number n ? n.intValue() : 0;
        int parked = row[1] instanceof Number n ? n.intValue() : 0;
        long spend = row[2] instanceof Number n ? n.longValue() : 0L;
        return new TaskAggregates(inFlight, parked, spend);
    }

    private Long maxThreadUpdatedAt(String workspaceId)
    {
        // MAX over an empty selection comes back as NULL — we surface
        // that as a null Long so the card can fall back to "no
        // activity yet" instead of rendering epoch zero.
        Object raw = entityManager.createNativeQuery(
                        "SELECT MAX(updated_at_ms) FROM threads "
                                + "WHERE workspace_id = :workspaceId")
                .setParameter("workspaceId", workspaceId)
                .getSingleResult();
        return raw instanceof Number n ? n.longValue() : null;
    }

    /** Internal tuple for {@link #aggregateTasks} so the multi-column
     *  native-query result has a name instead of an opaque int[3]. */
    private record TaskAggregates(int inFlight, int parked, long spendMilliUsd) {}

    private Workspace toWorkspace(WorkspaceEntity e)
    {
        return new Workspace(
                e.getId(),
                e.getName(),
                e.getMemoryMd(),
                e.getIsScratch() != 0,
                deserialiseWorkModel(e.getWorkModelJson()),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()));
    }

    private String serialiseWorkModel(WorkModel m)
    {
        if (m == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(m);
        }
        catch (JsonProcessingException e) {
            // The record is plain-object-mapper-friendly, so a failure
            // here means a programming error — surface it loudly.
            throw new IllegalStateException("WorkModel JSON serialise failed", e);
        }
    }

    private WorkModel deserialiseWorkModel(String json)
    {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, WorkModel.class);
        }
        catch (JsonProcessingException e) {
            // Bad row → treat as "no override". The resolver falls back
            // to global default. Don't break the whole workspace load.
            return null;
        }
    }

    private static WorkspaceRepo toRepo(WorkspaceRepoEntity e)
    {
        return new WorkspaceRepo(
                e.getId().getWorkspaceId(),
                e.getId().getRepoFullName(),
                e.getDefaultBaseBranch(),
                e.getAutoFixEnabled() != 0,
                Instant.ofEpochMilli(e.getAddedAtMs()));
    }
}
