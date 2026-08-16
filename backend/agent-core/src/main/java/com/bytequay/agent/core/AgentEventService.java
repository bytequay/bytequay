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
package com.bytequay.agent.core;

import java.util.List;
import java.util.concurrent.Flow;

/**
 * Sequences, persists, and publishes provider-neutral task events.
 *
 * <p>This service is the sole source of backend event sequence numbers. It
 * persists each complete envelope before publishing it and provides gap-free
 * replay followed by live delivery for SSE adapters.
 */
public interface AgentEventService
{
    /**
     * Assigns the next task sequence, persists an event, then publishes it.
     *
     * <p>Publication must never occur before the complete envelope is durably
     * appended.
     *
     * @param event normalized event without a backend sequence
     * @return persisted envelope with its assigned sequence
     * @throws AgentStorageException when the append fails
     */
    AgentEventEnvelope append(UnsequencedAgentEvent event);

    /**
     * Reads a bounded event page in ascending sequence order.
     *
     * @param taskId task whose history is requested
     * @param afterSequence exclusive lower sequence; zero starts at the beginning
     * @param limit positive maximum number of envelopes
     * @return ordered persisted events
     * @throws InvalidAgentRequestException when sequence or limit is invalid
     */
    List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit);

    /**
     * Opens a gap-free replay followed by a live event stream.
     *
     * <p>The publisher honors subscriber backpressure and closes when the task
     * becomes terminal or the subscriber cancels.
     *
     * @param taskId task to observe
     * @param afterSequence last sequence already received by the subscriber
     * @return replay-and-live publisher
     * @throws AgentTaskNotFoundException when the task does not exist
     */
    Flow.Publisher<AgentEventEnvelope> subscribe(TaskId taskId, long afterSequence);
}
