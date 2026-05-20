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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * Pure projection of one row in the {@code tasks} table — the
 * top-level record for an AI coding task. Lifecycle, ownership of the
 * agent loop, persistent metadata.
 *
 * <p>Several fields are conditional on {@link #kind}:
 * <ul>
 *   <li>{@code agentSessionId}, {@code processPid}, {@code logPath}
 *       are populated for {@link TaskKind#CLI_AGENT} tasks while a
 *       child process is alive, and {@code null} for
 *       {@link TaskKind#LOGIC_LOOP}.</li>
 *   <li>{@code branchName} is best-effort sniffed from the working
 *       directory's git head; null when not in a repo. Records the
 *       branch the user had checked out at task-create time — it
 *       does <em>not</em> change when ByteQuay puts the agent on a
 *       dev branch via worktree.</li>
 *   <li>{@code endedAt} / {@code errorMessage} only set on terminal
 *       transitions (COMPLETED / ERRORED).</li>
 *   <li>{@code worktreePath} / {@code localBranch} are populated when
 *       ByteQuay created a dedicated worktree for the task. Null
 *       on legacy rows and on tasks where worktree isolation
 *       didn't apply (non-git working dir, or worktree creation
 *       failed and the task fell back to the main checkout).</li>
 * </ul>
 *
 * @param costUsdMilli running cost in USD × 1000; divide on read so
 *                     SQLite's lack of fixed-precision decimal type
 *                     doesn't cause display drift.
 * @param taskType free-form task type, currently {@code "DEVELOP"} or
 * {@code "FIX"}.
 * @param linkedPrNumber GitHub PR number the task is associated with, scoped
 * to the task's repo.
 * @param linkedIssueNumber GitHub issue number the task is associated with,
 * scoped to the task's repo.
 * @param worktreePath absolute path to the git worktree the agent runs in.
 * Null when no worktree was created for this task (legacy rows, fallback
 * for non-git working dirs).
 * @param localBranch name of the branch created on the worktree (e.g.
 * {@code "dev/<sessionId>-<slug>"}). Null when {@code worktreePath} is null.
 */
public record Task(
        String id,
        TaskKind kind,
        String provider,
        String agentSessionId,
        String title,
        TaskStatus status,
        String workingDir,
        String branchName,
        String model,
        long costUsdMilli,
        long tokensIn,
        long tokensOut,
        Integer processPid,
        String logPath,
        Instant createdAt,
        Instant updatedAt,
        Instant endedAt,
        String errorMessage,
        String metadataJson,
        String taskType,
        Integer linkedPrNumber,
        Integer linkedIssueNumber,
        String worktreePath,
        String localBranch)
{
    /**
     * Resolves the directory the agent process should be spawned in.
     * When a worktree was created for this task, that's where the agent
     * runs. Otherwise we fall back to the user-supplied
     * {@link #workingDir()} (the repo root for coding tasks, or any
     * directory for legacy / non-git tasks).
     */
    public String agentCwd()
    {
        return worktreePath != null && !worktreePath.isBlank() ? worktreePath : workingDir;
    }
}
