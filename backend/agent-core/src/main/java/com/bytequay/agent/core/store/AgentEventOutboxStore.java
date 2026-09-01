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

import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;

import java.util.List;
import java.util.Optional;

public interface AgentEventOutboxStore
{
    void insert(AgentEventOutboxEntry entry);

    Optional<AgentEventOutboxEntry> find(EventId eventId);

    List<AgentEventOutboxEntry> listPending(int limit);

    boolean hasPending(TaskId taskId);

    void markAppended(EventId eventId, long sequence);
}
