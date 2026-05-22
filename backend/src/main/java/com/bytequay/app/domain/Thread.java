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
 * Pure projection of one row in the {@code threads} table — the
 * top-level record for an AI coding thread. Lifecycle, ownership of the
 * agent loop, persistent metadata.
 *
 * <p><b>Bridge fields.</b> V72 moved the work-unit columns
 * ({@code working_dir}, {@code branch_name}, {@code worktree_path},
 * {@code process_pid}, {@code log_path}, {@code task_type},
 * {@code linked_pr_number}, {@code linked_issue_number},
 * {@code metadata_json}) out of {@code threads} and onto
 * {@code tasks}. Until callers migrate to reading the active task
 * directly through {@link com.bytequay.app.repository.TaskStore},
 * {@link com.bytequay.app.repository.sqlite.SqliteThreadStore#toThread}
 * still synthesises those fields from the thread's active task
 * row. Treat them as a temporary read projection; new code should
 * go through TaskStore. The bridge is being torn down field by
 * field in follow-up commits.
 *
 * <p>Several fields are conditional on {@link #kind}:
 * <ul>
 *   <li>{@code agentSessionId}, {@code processPid}, {@code logPath}
 *       are populated for {@link ThreadKind#CLI_AGENT} threads while a
 *       child process is alive, and {@code null} for
 *       {@link ThreadKind#LOGIC_LOOP}.</li>
 *   <li>{@code branchName} currently mirrors the active task's branch
 *       (post-V72). The pre-V72 semantics — "the branch the user had
 *       checked out at thread-create time" — no longer hold; callers
 *       wanting that data have to look elsewhere or live without it.</li>
 *   <li>{@code endedAt} / {@code errorMessage} only set on terminal
 *       transitions (COMPLETED / ERRORED).</li>
 *   <li>{@code worktreePath} is populated when ByteQuay created a
 *       dedicated worktree for the active task.</li>
 * </ul>
 *
 * @param costUsdMilli running cost in USD × 1000; divide on read so
 *                     SQLite's lack of fixed-precision decimal type
 *                     doesn't cause display drift.
 * @param taskType free-form active-task type, e.g. {@code "DEVELOP"}
 *                 or {@code "FIX"}. Null when the thread has no active
 *                 task (0-Task brainstorm threads); callers must
 *                 handle the null rather than assume a default.
 * @param linkedPrNumber GitHub PR number the active task is linked to.
 *                       Null when no active task or no link.
 * @param linkedIssueNumber GitHub issue number the active task is linked to.
 *                          Null when no active task or no link.
 * @param worktreePath absolute path to the git worktree the agent runs in.
 * Null when no worktree was created for this thread (legacy rows, fallback
 * for non-git working dirs).
 * @param flow structural discriminator. Set at creation, never silently
 * flipped — the persistence layer refuses to rewrite it on an existing
 * row.
 */
public record Thread(
        String id,
        ThreadKind kind,
        String provider,
        String agentSessionId,
        String title,
        ThreadStatus status,
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
        ThreadFlow flow)
{
    /**
     * Resolves the directory the agent process should be spawned in.
     * When a worktree was created for this thread, that's where the agent
     * runs. Otherwise we fall back to the user-supplied
     * {@link #workingDir()} (the repo root for coding threads, or any
     * directory for legacy / non-git threads).
     */
    public String agentCwd()
    {
        return worktreePath != null && !worktreePath.isBlank() ? worktreePath : workingDir;
    }
}
