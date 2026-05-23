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
 * is foreground at a time (the others are parked but alive). "Next →"
 * parks the foreground task at AWAITING_REVIEW and starts a sibling
 * cut from a fresh main; Ship finalizes a task and returns to the
 * thread trunk.
 *
 * <p>Per-execution fields ({@code processPid}, {@code logPath}) are
 * populated while a CLI agent subprocess is alive and cleared on
 * exit. The CLI's {@code --resume} id lives <strong>on this Task</strong>
 * — each Task forks from the trunk planning session at creation and
 * thereafter owns its own conversation. {@link Thread#agentSessionId}
 * is the trunk planning session, which the foreground Task's session
 * forks from but never shares.
 *
 * @param seq monotonically increasing within the thread (1, 2, 3...)
 * @param baseBranch the branch this task was cut from — 'main',
 *                   'upstream/master', or a sibling task's branch when
 *                   stacked (rare escape hatch).
 * @param workingDir the repo root that {@code worktreePath} was cut
 *                   from; useful when the worktree itself is reaped
 *                   after the PR opens.
 * @param agentSessionId the CLI {@code --resume} id for this task's
 *                       worktree. {@code null} until the first turn
 *                       captures a session_started event; thereafter
 *                       sticky for the life of the task so reopens
 *                       continue the same conversation.
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
        String agentSessionId,
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
