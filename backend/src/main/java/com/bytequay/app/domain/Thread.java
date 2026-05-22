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
 * <p>Several fields are conditional on {@link #kind}:
 * <ul>
 *   <li>{@code agentSessionId}, {@code processPid}, {@code logPath}
 *       are populated for {@link ThreadKind#CLI_AGENT} threads while a
 *       child process is alive, and {@code null} for
 *       {@link ThreadKind#LOGIC_LOOP}.</li>
 *   <li>{@code branchName} is best-effort sniffed from the working
 *       directory's git head; null when not in a repo. Records the
 *       branch the user had checked out at thread-create time — it
 *       does <em>not</em> change when ByteQuay puts the agent on a
 *       dev branch via worktree.</li>
 *   <li>{@code endedAt} / {@code errorMessage} only set on terminal
 *       transitions (COMPLETED / ERRORED).</li>
 *   <li>{@code worktreePath} / {@code localBranch} are populated when
 *       ByteQuay created a dedicated worktree for the thread. Null
 *       on legacy rows and on threads where worktree isolation
 *       didn't apply (non-git working dir, or worktree creation
 *       failed and the thread fell back to the main checkout).</li>
 * </ul>
 *
 * @param costUsdMilli running cost in USD × 1000; divide on read so
 *                     SQLite's lack of fixed-precision decimal type
 *                     doesn't cause display drift.
 * @param taskType free-form thread type, currently {@code "DEVELOP"} or
 * {@code "FIX"}.
 * @param linkedPrNumber GitHub PR number the thread is associated with, scoped
 * to the thread's repo.
 * @param linkedIssueNumber GitHub issue number the thread is associated with,
 * scoped to the thread's repo.
 * @param worktreePath absolute path to the git worktree the agent runs in.
 * Null when no worktree was created for this thread (legacy rows, fallback
 * for non-git working dirs).
 * @param localBranch name of the branch created on the worktree (e.g.
 * {@code "dev/<sessionId>-<slug>"}). Null when {@code worktreePath} is null.
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
        String localBranch,
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
