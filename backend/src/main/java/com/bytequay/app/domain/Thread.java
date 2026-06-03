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

import com.bytequay.app.service.concepts.Concept;
import com.bytequay.app.service.concepts.ConceptKind;

import java.time.Instant;

/**
 * Pure projection of one row in the {@code threads} table — the
 * top-level record for an AI coding thread. Lifecycle, ownership of the
 * agent loop, persistent metadata.
 *
 * <p>Bridge teardown complete: the work-unit columns that V72
 * moved onto {@code tasks} ({@code working_dir}, {@code branch_name},
 * {@code worktree_path}, {@code process_pid}, {@code log_path},
 * {@code task_type}, {@code linked_pr_number},
 * {@code linked_issue_number}, {@code metadata_json}) are no longer
 * surfaced as flattened scalars on Thread. Readers go through
 * {@link #activeTask} (a projected {@link Task}) for those, or
 * through {@link com.bytequay.app.repository.TaskStore} directly
 * when they need more than the active task.
 *
 * <p>Several fields are conditional on {@link #kind}:
 * <ul>
 *   <li>{@code agentSessionId} is populated for
 *       {@link ThreadKind#CLI_AGENT} threads while a child process
 *       is alive, and {@code null} for {@link ThreadKind#LOGIC_LOOP}.
 *       Per-process diagnostics ({@code process_pid}, {@code log_path})
 *       live on the active {@code tasks} row now, not here.</li>
 *   <li>{@code endedAt} / {@code errorMessage} only set on terminal
 *       transitions (COMPLETED / ERRORED).</li>
 * </ul>
 *
 * @param costUsdMilli running cost in USD × 1000; divide on read so
 *                     SQLite's lack of fixed-precision decimal type
 *                     doesn't cause display drift.
 * @param flow structural discriminator. Set at creation, never silently
 * flipped — the persistence layer refuses to rewrite it on an existing
 * row.
 * @param activeTask the most recent non-terminal {@link Task} for the
 * thread, projected at read time. Null on 0-Task brainstorm threads.
 * Carries the per-task execution surface (working dir, branch,
 * worktree path, PR/issue links, etc.) that used to be flattened
 * onto Thread before the bridge teardown.
 */
@Concept(
        name = "thread",
        kind = ConceptKind.NOUN,
        definition = "A long-lived AI conversation about one piece of work. Owns the agent "
                + "loop and persistent metadata; accumulates one or more tasks over its "
                + "lifetime, only one of which is foreground at a time.",
        examples = {
                "A review thread that produces N tasks as the user iterates on feedback.",
                "A build thread whose trunk plans + delegates each task to a worker."
        },
        relatedConcepts = {"task", "trunk"})
public record Thread(
        String id,
        ThreadKind kind,
        String provider,
        String agentSessionId,
        String title,
        ThreadStatus status,
        String model,
        long costUsdMilli,
        long tokensIn,
        long tokensOut,
        Instant createdAt,
        Instant updatedAt,
        Instant endedAt,
        String errorMessage,
        ThreadFlow flow,
        /** Owning workspace's id. The store stamps this on INSERT
         *  from the caller's NewTaskRequest; legacy rows are
         *  back-filled to "ws-default" by V73, and the store's
         *  fallback covers any null sneaking through. */
        String workspaceId,
        Task activeTask)
{
    /**
     * Resolves the directory the agent process should be spawned in
     * by delegating to {@link Task#agentCwd} on the {@link #activeTask}.
     * Returns null for 0-Task brainstorm threads — there's no agent
     * to spawn for those, so the caller has to handle the null.
     */
    public String agentCwd()
    {
        return activeTask == null ? null : activeTask.agentCwd();
    }
}
