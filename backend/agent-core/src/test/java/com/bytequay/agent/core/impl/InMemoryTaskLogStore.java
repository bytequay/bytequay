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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.google.common.collect.ImmutableList;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A task log that keeps envelopes in memory, enough for tests that fold metrics from persisted events. */
final class InMemoryTaskLogStore
        implements TaskLogStore
{
    private final List<AgentEventEnvelope> events = new ArrayList<>();

    @Override
    public synchronized void append(AgentEventEnvelope envelope)
    {
        events.add(envelope);
    }

    @Override
    public synchronized Optional<Long> findSequence(TaskId taskId, EventId eventId)
    {
        return events.stream().filter(event -> event.taskId().equals(taskId) && event.eventId().equals(eventId)).map(AgentEventEnvelope::sequence).findFirst();
    }

    @Override
    public synchronized List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
    {
        return events.stream().filter(event -> event.taskId().equals(taskId) && event.sequence() > afterSequence).limit(limit).collect(ImmutableList.toImmutableList());
    }

    @Override
    public synchronized long lastSequence(TaskId taskId)
    {
        return events.stream().filter(event -> event.taskId().equals(taskId)).mapToLong(AgentEventEnvelope::sequence).max().orElse(0);
    }

    @Override
    public BlobRef writeBlob(TaskId taskId, ByteBuffer content, String mediaType)
    {
        throw new UnsupportedOperationException("blobs are not part of this test");
    }

    @Override
    public ByteBuffer readBlob(TaskId taskId, BlobRef blobRef)
    {
        throw new UnsupportedOperationException("blobs are not part of this test");
    }
}
