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
package com.bytequay.agent.core.store;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.TaskId;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Persistence port for append-only task event history and external payloads.
 *
 * <p>Complete event envelopes are stored in sequence order in JSONL, while
 * bounded large payloads may be stored as task-relative blobs. This store does
 * not decide when a task has reached its retention deadline.
 */
public interface TaskLogStore
{
    /**
     * Appends and flushes one complete event envelope as a single JSONL record.
     *
     * @param envelope sequenced event to append
     * @throws com.bytequay.agent.core.AgentStorageException when append or flush fails
     */
    void append(AgentEventEnvelope envelope);

    /**
     * Reads a bounded page in ascending sequence order.
     *
     * <p>A partial final line left by a crash is ignored; corruption before the
     * final line is an error.
     *
     * @param taskId task whose log is read
     * @param afterSequence exclusive lower sequence
     * @param limit positive maximum record count
     * @return ordered complete envelopes
     * @throws com.bytequay.agent.core.AgentStorageException on non-tail corruption
     */
    List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit);

    /**
     * Returns the final complete record's sequence for restart recovery.
     *
     * @param taskId task whose log is inspected
     * @return zero for an empty log, otherwise the last complete sequence
     * @throws com.bytequay.agent.core.AgentStorageException on non-tail corruption
     */
    long lastSequence(TaskId taskId);

    /**
     * Stores a bounded payload outside JSONL.
     *
     * @param taskId task that owns the payload
     * @param content payload bytes; ownership remains with the caller
     * @param mediaType payload media type
     * @return task-relative reference safe to persist in an event
     * @throws com.bytequay.agent.core.AgentStorageException when storage fails
     */
    BlobRef writeBlob(TaskId taskId, ByteBuffer content, String mediaType);
}
