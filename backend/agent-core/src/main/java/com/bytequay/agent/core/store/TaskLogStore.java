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
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.TaskId;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalLong;

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
     * <p>A partial final line left by a crash is ignored. Visited records are
     * validated; full-log validation is performed by {@link #lastSequence(TaskId)}.
     *
     * @param taskId task whose log is read
     * @param afterSequence exclusive lower sequence
     * @param limit positive maximum record count
     * @return ordered complete envelopes
     * @throws com.bytequay.agent.core.AgentStorageException on non-tail corruption
     */
    List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit);

    /**
     * Reads at most 1000 events and 2 MiB of serialized JSON in ascending order.
     *
     * <p>No cursor reads the latest page. A before or after cursor reads the adjacent
     * older or newer page. Cursors are committed JSONL byte boundaries; incomplete
     * tail bytes are ignored without modifying the file. Only visited records are validated.
     *
     * @param taskId task whose history is read
     * @param before exclusive end boundary for an older page
     * @param after inclusive start boundary for a newer page
     * @return bounded history and adjacent cursors
     * @throws com.bytequay.agent.core.InvalidAgentRequestException when both cursors are supplied or a boundary is invalid
     */
    AgentHistoryPage readHistory(TaskId taskId, OptionalLong before, OptionalLong after);

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

    /**
     * Reads a bounded payload owned by a task.
     *
     * @param taskId task that owns the payload
     * @param blobRef task-relative payload reference
     * @return read-only payload bytes positioned at the start
     * @throws com.bytequay.agent.core.AgentStorageException when the reference is unsafe, missing, or exceeds its bound
     */
    ByteBuffer readBlob(TaskId taskId, BlobRef blobRef);
}
