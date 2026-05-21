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
 * One unit of work within a {@link Thread}. Owns a git branch, a
 * worktree directory at {@code <repo>/.worktrees/<task-id>/}, the
 * commits the agent makes there, and (once opened) a PR + CI status.
 *
 * <p>A thread accumulates tasks over its lifetime; at most one task
 * is "active" at a time (non-terminal status with the highest seq).
 * "Ship & continue" closes the current task and starts the next,
 * keeping the conversation continuous while the agent's cwd swaps to
 * the new worktree.
 *
 * <p>Per-execution fields ({@code processPid}, {@code logPath}) are
 * populated while a CLI agent subprocess is alive and cleared on
 * exit. The CLI's {@code --resume} id lives one level up on the
 * {@link Thread} — the conversation persists across task switches,
 * so the resume id does too.
 *
 * @param seq monotonically increasing within the thread (1, 2, 3...)
 * @param baseBranch the branch this task was cut from — 'main',
 *                   'upstream/master', or the previous task's branch
 *                   when stacked.
 * @param workingDir the repo root that {@code worktreePath} was cut
 *                   from; useful when the worktree itself is reaped
 *                   after the PR opens.
 * @param firstMsgSeq inclusive lower bound of {@code thread_messages.seq}
 *                    this task covers (the first prompt sent after the
 *                    task was created).
 * @param lastMsgSeq inclusive upper bound — set when the task closes.
 */
public record Task(
        String id,
        String threadId,
        long seq,
        TaskStatus status,
        String branchName,
        String worktreePath,
        String baseBranch,
        String workingDir,
        Integer processPid,
        String logPath,
        Integer prNumber,
        String prState,
        String ciState,
        String taskType,
        Integer linkedPrNumber,
        Integer linkedIssueNumber,
        long costUsdMilli,
        long tokensIn,
        long tokensOut,
        Long firstMsgSeq,
        Long lastMsgSeq,
        Instant createdAt,
        Instant endedAt,
        String errorMessage)
{
    /**
     * Resolves the directory the agent process should run in for this
     * task. Prefers {@code worktreePath} (set when a worktree was cut
     * for the task) and falls back to {@code workingDir} (the parent
     * repo) once the worktree is reaped after the PR opens.
     */
    public String agentCwd()
    {
        if (worktreePath != null && !worktreePath.isBlank()) {
            return worktreePath;
        }
        return workingDir;
    }
}
