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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskTitleService;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentLaneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static java.util.Objects.requireNonNull;

/** Optional naming starts only after the first turn has been accepted; it never delays Send. */
@Component
public class InteractiveTaskNaming
{
    private static final Logger log = LoggerFactory.getLogger(InteractiveTaskNaming.class);
    private static final Logger timing = LoggerFactory.getLogger("com.bytequay.agent.SendTiming");

    private final AgentTaskTitleService names;
    private final AgentTaskStore tasks;
    private final AgentRuntimeRegistry runtime;
    private final ObjectProvider<DevelopmentBranchService> branches;
    private final Executor executor;
    private final Clock clock;
    private final AgentUnitOfWork unitOfWork;
    private final AgentEventOutboxStore outbox;
    private final AgentEventService events;
    private final Set<TaskId> running = ConcurrentHashMap.newKeySet();

    public InteractiveTaskNaming(AgentTaskTitleService names, AgentTaskStore tasks, AgentRuntimeRegistry runtime,
            ObjectProvider<DevelopmentBranchService> branches, @Qualifier(IO_EXECUTOR) Executor executor, Clock clock,
            AgentUnitOfWork unitOfWork, AgentEventOutboxStore outbox, AgentEventService events)
    {
        this.names = requireNonNull(names, "names is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.branches = requireNonNull(branches, "branches is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.outbox = requireNonNull(outbox, "outbox is null");
        this.events = requireNonNull(events, "events is null");
    }

    public void schedule(TaskId taskId, AgentLaneId laneId, AgentTaskDefaults defaults, String context)
    {
        if (!running.add(taskId)) {
            return;
        }
        try {
            executor.execute(() -> generate(taskId, laneId, defaults, context));
        }
        catch (RuntimeException failure) {
            running.remove(taskId);
            log.debug("Keeping the provisional name for task {}", taskId, failure);
        }
    }

    private void generate(TaskId taskId, AgentLaneId laneId, AgentTaskDefaults defaults, String context)
    {
        try {
            if (tasks.find(taskId).filter(task -> task.state() == TaskState.OPEN && task.titleSource() == TitleSource.PROVISIONAL).isEmpty()) {
                return;
            }
            timing.debug("send-timing phase=task.naming.background.begin epochMs={} taskId={} turnId=-", System.currentTimeMillis(), taskId);
            names.generate(laneId, defaults, context).ifPresent(generated -> {
                synchronized (runtime.taskLock(taskId)) {
                    tasks.find(taskId).filter(task -> task.state() == TaskState.OPEN && task.titleSource() == TitleSource.PROVISIONAL).ifPresent(task -> {
                        Instant now = Instant.now(clock);
                        AgentEventOutboxEntry event = AgentEventOutboxEntry.pendingLifecycle(EventId.random(), taskId, Optional.empty(),
                                AgentEventPayload.LifecyclePhase.TITLE_UPDATED, "Task title updated", now);
                        unitOfWork.execute(() -> {
                            tasks.update(task.withTitle(generated.title(), TitleSource.AGENT, now));
                            outbox.insert(event);
                            return null;
                        });
                        branches.getObject().propose(taskId, generated.branch());
                        events.drain(event.eventId());
                    });
                }
            });
        }
        catch (RuntimeException failure) {
            log.debug("Keeping the current name for task {}", taskId, failure);
        }
        finally {
            timing.debug("send-timing phase=task.naming.background.end epochMs={} taskId={} turnId=-", System.currentTimeMillis(), taskId);
            running.remove(taskId);
        }
    }
}
