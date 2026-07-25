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

import com.bytequay.app.domain.AgentRun;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for {@link AgentRun}. */
public interface AgentRunStore
{
    /** Full-row upsert. Legacy write path — it rewrites {@code status},
     *  so lifecycle callers migrate to {@link #insert} plus the targeted
     *  methods below, which cannot clobber a concurrent transition. */
    AgentRun save(AgentRun run);

    /** Insert a freshly opened run — the one write that legitimately
     *  carries an initial status. */
    default AgentRun insert(AgentRun run)
    {
        return save(run);
    }

    /** Compare-and-set the status column (with {@code finishedAt}
     *  stamped or cleared to match): move to {@code to} only while the
     *  row still holds {@code expected}. Never touches metadata.
     *
     *  @return true when the row was updated */
    default boolean updateStatusIf(String runId, String expected, String to, Instant finishedAt)
    {
        throw new UnsupportedOperationException("updateStatusIf");
    }

    /** Targeted progress write (iterations + usage); never touches
     *  status. */
    default void updateProgress(String runId, int iterations, long costUsdMilli, long tokensIn, long tokensOut)
    {
        throw new UnsupportedOperationException("updateProgress");
    }

    /** Targeted budget write; never touches status. */
    default void updateBudget(String runId, Integer budget)
    {
        throw new UnsupportedOperationException("updateBudget");
    }

    /** Targeted headline/outcome write; never touches status. */
    default void updateHeadline(String runId, String headline, String outcome)
    {
        throw new UnsupportedOperationException("updateHeadline");
    }

    Optional<AgentRun> findById(String id);

    /** Public Sessions feed for one workspace, newest first. */
    default List<AgentRun> findByWorkspace(String workspaceId)
    {
        return List.of();
    }

    /** Every session owned by one trunk, newest first. */
    default List<AgentRun> findByThread(String threadId)
    {
        return List.of();
    }

    /** Every run owned by one review round, including its verifier. */
    List<AgentRun> findByReviewRound(String reviewRoundId);

    /** All runs for a task, newest-first. {@code kind} / {@code
     *  parentStageId} narrow when non-null; both null returns every run. */
    List<AgentRun> findByTask(String taskId, String kind, String parentStageId);

    /** The task's live runs ({@code running} / {@code awaiting_gate}),
     *  newest-first — what the rail endpoint renders sub-rows from. */
    List<AgentRun> findLiveByTask(String taskId);

    /** The task's most recent run of {@code kind} still live, if any —
     *  the idempotent "already open" check before starting a new one. */
    Optional<AgentRun> findLiveByTaskAndKind(String taskId, String kind);
}
