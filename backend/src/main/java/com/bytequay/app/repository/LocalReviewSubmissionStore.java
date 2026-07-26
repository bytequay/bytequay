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

import com.bytequay.app.domain.LocalReviewSubmission;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for durable submitted-local-review batches.
 * Rows are insert-only identities with targeted outcome stamps; there
 * is no full-row update path.
 */
public interface LocalReviewSubmissionStore
{
    /** Insert one immutable submission row (written in the same
     *  transaction as its timeline event + watermark). */
    void insert(LocalReviewSubmission submission);

    /** The next task-wide {@code submission_seq} high-watermark. Call
     *  only under the task lock — the unique index is the backstop. */
    long nextSeq(String taskId);

    Optional<LocalReviewSubmission> findById(String id);

    /** Open (neither completed nor canceled) batches, oldest sequence
     *  first — the queue driver's work list. */
    List<LocalReviewSubmission> listOpenByTask(String taskId);

    /** Bind the admitted Development run + activation time. */
    void bindRun(String id, String agentRunId, Instant activatedAt);

    /** Bind every uncancelled submission covered by the accepted local-
     *  review watermark. This includes rows already stamped completed by
     *  the roots-closed acceptance command. */
    default void bindRunThrough(
            String taskId, long throughSequence, String agentRunId, Instant activatedAt)
    {
        throw new UnsupportedOperationException("bindRunThrough");
    }

    /** Stamp one batch completed (roots-closed validation accepted). */
    void markCompleted(String id, Instant at);

    /** Stamp every open batch of the task canceled — replan, local
     *  ship, authoritative remote open, and the terminal commands. */
    void cancelOpenForTask(String taskId, String reason, Instant at);

    /** Bump the bounded per-batch failure counter. */
    void incrementFailures(String id);
}
