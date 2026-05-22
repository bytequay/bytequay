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
package com.bytequay.app.repository;

import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceRepo;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the Workspace tier. Keeps JPA entities
 * package-private inside {@code repository.sqlite}; the service
 * layer talks only to this interface.
 */
public interface WorkspaceStore
{
    // ── workspaces ─────────────────────────────────────────────────────

    /** Insert or update a workspace by primary key. */
    void saveWorkspace(Workspace workspace);

    /** Single-row lookup by id. */
    Optional<Workspace> findWorkspaceById(String id);

    /** All workspaces, newest-{@code updated_at_ms} first. */
    List<Workspace> listWorkspaces();

    /** Permanent removal. Drops all attached workspace_repos via
     *  FK cascade; threads pointing at it are left orphaned (callers
     *  should re-home them first). */
    void deleteWorkspace(String id);

    // ── workspace_repos ────────────────────────────────────────────────

    /** Pin a repo into a workspace. Idempotent on the composite key. */
    void addRepo(WorkspaceRepo repo);

    /** Remove a repo from a workspace. No-op when the row doesn't exist. */
    void removeRepo(String workspaceId, String repoFullName);

    /** All repos attached to a workspace. */
    List<WorkspaceRepo> listRepos(String workspaceId);

    /** Single-row lookup for a (workspace, repo) pair. */
    Optional<WorkspaceRepo> findRepo(String workspaceId, String repoFullName);

    /** Update only the default_base_branch on an existing row.
     *  No-op when the row doesn't exist. */
    void setDefaultBaseBranch(String workspaceId, String repoFullName, String defaultBaseBranch);

    // ── aggregates ─────────────────────────────────────────────────────

    /**
     * Per-workspace aggregates for the landing-grid card. One DB
     * round-trip's worth of counts/sums computed against {@code threads}
     * and {@code tasks}, scoped by {@code workspaceId}. Zeros for an
     * empty workspace; {@code lastActivityMs} is null in that case.
     *
     * @param sinceMs lower bound for {@code spendMilliUsd} — the
     *                service passes today's local-midnight epoch ms.
     */
    WorkspaceStats fetchStats(String workspaceId, long sinceMs);

    /** Tiny carrier for the four numeric aggregates the card needs.
     *  Pulled from the database in one call so the service doesn't
     *  fan out N queries per workspace. */
    record WorkspaceStats(
            int activeThreadCount,
            int tasksInFlight,
            int needsAttentionCount,
            long spendMilliUsd,
            Long lastActivityMs)
    {
    }
}
