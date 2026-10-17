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
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;

/** A task log that keeps envelopes in memory, enough for tests that fold metrics from persisted events. */
class InMemoryTaskLogStore
        implements TaskLogStore
{
    private final List<AgentEventEnvelope> events = new ArrayList<>();

    @Override
    public synchronized void append(AgentEventEnvelope envelope)
    {
        events.add(envelope);
    }

    @Override
    public synchronized List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
    {
        return events.stream().filter(event -> event.taskId().equals(taskId) && event.sequence() > afterSequence).limit(limit).collect(ImmutableList.toImmutableList());
    }

    @Override
    public synchronized AgentHistoryPage readHistory(TaskId taskId, OptionalLong before, OptionalLong after)
    {
        long latest = lastSequence(taskId);
        return new AgentHistoryPage(readAfter(taskId, Math.max(0, latest - 1000), 1000), OptionalLong.empty(), OptionalLong.empty(), latest);
    }

    @Override
    public synchronized Optional<AgentEventEnvelope> findLatest(TaskId taskId, Predicate<AgentEventEnvelope> matches, int maxRecords)
    {
        return Lists.reverse(events).stream().filter(event -> event.taskId().equals(taskId)).limit(maxRecords).filter(matches).findFirst();
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
