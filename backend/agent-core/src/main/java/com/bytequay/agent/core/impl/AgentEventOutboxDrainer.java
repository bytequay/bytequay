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

import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentEventOutboxStore;

import java.util.List;

final class AgentEventOutboxDrainer
{
    private static final int BATCH_SIZE = 100;

    private AgentEventOutboxDrainer() {}

    static void drainPending(
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            TaskId taskId)
    {
        while (true) {
            List<AgentEventOutboxEntry> pending = outboxStore.listPending(taskId, BATCH_SIZE);
            if (pending.isEmpty()) {
                return;
            }
            pending.forEach(entry -> eventService.drain(entry.eventId()));
        }
    }
}
