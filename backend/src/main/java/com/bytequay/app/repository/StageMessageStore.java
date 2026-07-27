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

import com.bytequay.app.domain.ThreadMessage;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for per-stage transcripts (the {@code stage_messages} table),
 * decoupled from the shared per-thread {@link ThreadStore} log. Each work
 * stage owns its own {@code seq} space so its exact transcript can be read
 * and streamed independently while one Task-owned agent serves its stages.
 *
 * <p>Reuses the {@link ThreadMessage} domain record (its {@code stageId} is
 * the partition key); {@code scope} is implicitly STAGE for every row. No-op
 * defaults so test stores can opt in; the SQLite store overrides.
 */
public interface StageMessageStore
{
    /** Append a message to its stage's transcript. {@code message.stageId()}
     *  must be set; {@code message.seq()} is the per-stage sequence the caller
     *  allocated (unique within the stage). */
    default void appendMessage(ThreadMessage message)
    {
    }

    /** A stage's full transcript, oldest-first by per-stage seq. */
    default List<ThreadMessage> listMessages(String stageId)
    {
        return List.of();
    }

    /** Every message across all of a task's stages, oldest-first by seq.
     *  Used for per-task token/cost aggregation. */
    default List<ThreadMessage> listMessagesByTask(String taskId)
    {
        return List.of();
    }

    /** Highest seq in a stage, or empty when the stage has no messages yet —
     *  the seed for the Task agent's active-stage next-seq counter. */
    default Optional<Long> maxMessageSeq(String stageId)
    {
        return Optional.empty();
    }

    /** Sum of {@code tokensIn + tokensOut} across the inclusive per-stage seq
     *  range. */
    default long sumTokensBetween(String stageId, long firstSeq, long lastSeq)
    {
        return 0L;
    }

    /** Inclusive-range slice of a stage's transcript, oldest-first. */
    default List<ThreadMessage> listMessagesBetween(String stageId, long firstSeq, long lastSeq)
    {
        return List.of();
    }

    /** Drop a stage's transcript (cascade also fires on stage delete). */
    default void deleteByStage(String stageId)
    {
    }
}
