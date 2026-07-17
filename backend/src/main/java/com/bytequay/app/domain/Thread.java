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
 * <p>A thread accumulates one or more {@link Task}s over its lifetime
 * and may run several at once — each on its own branch + worktree.
 * There is no single "active task" projection on Thread: callers that
 * need a task go through {@link com.bytequay.app.repository.TaskStore}
 * (the active set, or a specific task id stamped on the running turn).
 *
 * <p>Several fields are conditional on {@link #kind}:
 * <ul>
 *   <li>{@code agentSessionId} is populated for
 *       {@link ThreadKind#CLI_AGENT} threads while a child process
 *       is alive, and {@code null} for {@link ThreadKind#LOGIC_LOOP}.</li>
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
 */
@Concept(
        name = "thread",
        kind = ConceptKind.NOUN,
        definition = "A long-lived AI conversation about one piece of work. Owns the agent "
                + "loop and persistent metadata; accumulates one or more tasks over its "
                + "lifetime, any number of which may run at once.",
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
        /** Per-thread override on the work-model cascade. Null means
         *  "no override" — the resolver falls back to the workspace
         *  pick, then to the global default. See V95 for the column
         *  and {@link WorkModel} for the value shape. */
        WorkModel workModel,
        /** The review pass this thread was spawned from ("→ Spawn build
         *  thread"), or null. Set at creation on the spawned BUILD
         *  thread; powers the "← from review of PR #N" breadcrumb and
         *  lets the resolver flip the parent pass's findings to
         *  RESOLVED when this thread's work ships. */
        String parentReviewPassId,
        /** Concurrent compute slots the thread's tasks may occupy
         *  (V110). The field exists so unlocking wider parallelism is a
         *  config flip, not a re-migration; floors at 1. */
        int parallelSlots,
        /** For a {@link ThreadKind#BRAIN_AGENT} thread, the dev task this
         *  brain answers questions about (V122). Null for every other
         *  thread. A partial unique index enforces one brain thread per
         *  task. Entity-managed; the store maps it on INSERT. */
        String parentTaskId,
        /** Public review-trunk identity, e.g. owner/repo#123. Null for dev
         *  trunks and old unlinked review history. */
        String prRef)
{
    /** parallelSlots floors at 1. */
    public Thread
    {
        if (parallelSlots < 1) {
            parallelSlots = 1;
        }
    }

    /** Back-compat shape predating review-trunk PR ownership. */
    public Thread(
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
            String workspaceId,
            WorkModel workModel,
            String parentReviewPassId,
            int parallelSlots,
            String parentTaskId)
    {
        this(id, kind, provider, agentSessionId, title, status, model,
                costUsdMilli, tokensIn, tokensOut, createdAt, updatedAt,
                endedAt, errorMessage, flow, workspaceId, workModel,
                parentReviewPassId, parallelSlots, parentTaskId, null);
    }

    /** Back-compat constructor for the shape that predates the
     *  {@code parentTaskId} column (V122). Defaults it to null — correct
     *  for every thread except a brain thread; only the store's row mapper
     *  and the brain-thread creation path thread a real value through the
     *  canonical constructor. */
    public Thread(
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
            String workspaceId,
            WorkModel workModel,
            String parentReviewPassId,
            int parallelSlots)
    {
        this(id, kind, provider, agentSessionId, title, status, model, costUsdMilli,
                tokensIn, tokensOut, createdAt, updatedAt, endedAt, errorMessage, flow,
                workspaceId, workModel, parentReviewPassId, parallelSlots, null, null);
    }

    /** Thread with a review-pass parent but the default single slot —
     *  the shape a build thread spawned from a review pass is copied to. */
    public Thread(
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
            String workspaceId,
            WorkModel workModel,
            String parentReviewPassId)
    {
        this(id, kind, provider, agentSessionId, title, status, model, costUsdMilli,
                tokensIn, tokensOut, createdAt, updatedAt, endedAt, errorMessage, flow,
                workspaceId, workModel, parentReviewPassId, 1, null, null);
    }

    /** Thread with no review-pass parent — the default for every
     *  thread except one spawned from a review pass. Keeps the many
     *  existing construction sites unchanged. */
    public Thread(
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
            String workspaceId,
            WorkModel workModel)
    {
        this(id, kind, provider, agentSessionId, title, status, model, costUsdMilli,
                tokensIn, tokensOut, createdAt, updatedAt, endedAt, errorMessage, flow,
                workspaceId, workModel, null, 1, null, null);
    }
}
