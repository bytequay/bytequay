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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.DevelopmentContextReceipt;
import com.bytequay.core.context.DevelopmentContextReceiptStore;
import com.google.common.collect.ImmutableSet;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

/** Confirms a notebook search or read only after its exact provider tool result enters the durable task log. */
@Component
public class DevelopmentContextUsageObserver
{
    private static final Logger log = LoggerFactory.getLogger(DevelopmentContextUsageObserver.class);
    private static final int REPLAY_TASKS = 1000;
    private static final long REPLAY_EVENTS = 5000;
    private static final String RECEIPT_PREFIX = "context-receipt:";
    private static final Set<String> TOOL_NAMES = ImmutableSet.of("bytequay/repository_context_lookup", "mcp__bytequay__repository_context_lookup");
    private static final Set<AgentEventPayload.LifecyclePhase> SETTLING = EnumSet.of(
            AgentEventPayload.LifecyclePhase.TURN_COMPLETED,
            AgentEventPayload.LifecyclePhase.INTERRUPTED,
            AgentEventPayload.LifecyclePhase.TASK_COMPLETED,
            AgentEventPayload.LifecyclePhase.TASK_FAILED);

    private final AgentEventService events;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final DevelopmentContextReceiptStore receipts;
    private final ContextUsageRecorder usage;
    private final Set<TaskId> watched = ConcurrentHashMap.newKeySet();
    private final Map<TaskId, Flow.Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("development-context-usage-", 0).factory());

    public DevelopmentContextUsageObserver(AgentEventService events, AgentTaskStore tasks, AgentTurnStore turns,
            DevelopmentContextReceiptStore receipts, ContextUsageRecorder usage)
    {
        this.events = requireNonNull(events, "events is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.receipts = requireNonNull(receipts, "receipts is null");
        this.usage = requireNonNull(usage, "usage is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore()
    {
        executor.execute(() -> {
            List<String> taskIds = receipts.listTaskIds(REPLAY_TASKS + 1);
            if (taskIds.size() > REPLAY_TASKS) {
                log.warn("Development context delivery replay covers only the {} most recent receipt-owning tasks", REPLAY_TASKS);
            }
            for (String taskId : taskIds.stream().limit(REPLAY_TASKS).toList()) {
                TaskId id = TaskId.valueOf(taskId);
                if (tasks.find(id).filter(task -> task.state() == TaskState.OPEN).isPresent() && turns.findActive(id).isPresent()) {
                    watch(id);
                }
                else {
                    replay(id);
                }
            }
        });
    }

    private void replay(TaskId taskId)
    {
        try {
            long latest = events.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty()).latestSequence();
            long cursor = Math.max(0, latest - REPLAY_EVENTS);
            while (cursor < latest) {
                List<AgentEventEnvelope> page = events.readAfter(taskId, cursor, 200);
                if (page.isEmpty()) {
                    return;
                }
                page.forEach(this::confirm);
                cursor = page.getLast().sequence();
            }
        }
        catch (RuntimeException failure) {
            log.debug("Could not replay development context delivery for task {}: {}", taskId, failure.toString());
        }
    }

    /** Starts gap-free replay before the first provider turn is launched; repeated calls are harmless. */
    public void watch(TaskId taskId)
    {
        if (!watched.add(taskId)) {
            return;
        }
        try {
            long latest = events.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty()).latestSequence();
            events.subscribe(taskId, Math.max(0, latest - REPLAY_EVENTS)).subscribe(new Subscriber(taskId));
        }
        catch (RuntimeException failure) {
            watched.remove(taskId);
            log.debug("Could not observe development context delivery for task {}: {}", taskId, failure.toString());
        }
    }

    void confirm(AgentEventEnvelope event)
    {
        if (!(event.payload() instanceof AgentEventPayload.ToolActivity tool) || tool.state() != ActivityState.COMPLETED
                || !TOOL_NAMES.contains(tool.name()) || !tool.summary().startsWith(RECEIPT_PREFIX) || event.turnId().isEmpty()) {
            return;
        }
        String reference = tool.summary().substring(RECEIPT_PREFIX.length());
        if (reference.length() > 100) {
            return;
        }
        DevelopmentContextReceipt receipt = receipts.findReference(event.taskId().toString(), event.turnId().orElseThrow().toString(), reference)
                .orElse(null);
        if (receipt == null) {
            return;
        }
        AgentTask task = tasks.find(event.taskId()).orElse(null);
        if (task == null || task.workspaceId().isEmpty()) {
            return;
        }
        String workspaceId = task.workspaceId().orElseThrow();
        if (receipt.kind() == DevelopmentContextReceipt.Kind.SEARCH && reference.startsWith("context-search:")) {
            usage.confirm(workspaceId, reference, ContextUsageEvent.Stage.FOUND);
            usage.confirm(workspaceId, reference, ContextUsageEvent.Stage.NO_MATCH);
            usage.confirm(workspaceId, reference, ContextUsageEvent.Stage.SCOPE_DEMAND);
        }
        else if (receipt.kind() == DevelopmentContextReceipt.Kind.READ && reference.startsWith("context-note:")) {
            usage.confirm(workspaceId, reference, ContextUsageEvent.Stage.OPENED);
        }
    }

    @PreDestroy
    public void close()
    {
        subscriptions.values().forEach(Flow.Subscription::cancel);
        subscriptions.clear();
        watched.clear();
        executor.shutdownNow();
    }

    private final class Subscriber
            implements Flow.Subscriber<AgentEventEnvelope>
    {
        private final TaskId taskId;
        private Flow.Subscription subscription;

        private Subscriber(TaskId taskId)
        {
            this.taskId = taskId;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription)
        {
            this.subscription = subscription;
            subscriptions.put(taskId, subscription);
            subscription.request(1);
        }

        @Override
        public void onNext(AgentEventEnvelope item)
        {
            executor.execute(() -> {
                try {
                    confirm(item);
                }
                catch (RuntimeException failure) {
                    log.debug("Could not confirm development context delivery for task {}: {}", taskId, failure.toString());
                }
                if (item.payload() instanceof AgentEventPayload.Lifecycle lifecycle && SETTLING.contains(lifecycle.phase())) {
                    if (subscriptions.remove(taskId, subscription)) {
                        watched.remove(taskId);
                        subscription.cancel();
                        if (tasks.find(taskId).filter(task -> task.state() == TaskState.OPEN).isPresent()
                                && turns.findActive(taskId).filter(turn -> item.turnId().map(id -> !id.equals(turn.id())).orElse(true)).isPresent()) {
                            watch(taskId);
                        }
                    }
                }
                else if (subscriptions.get(taskId) == subscription) {
                    subscription.request(1);
                }
            });
        }

        @Override
        public void onError(Throwable failure)
        {
            if (subscriptions.remove(taskId, subscription)) {
                watched.remove(taskId);
            }
            log.debug("Development context task {} stream ended with an error: {}", taskId, failure.toString());
        }

        @Override
        public void onComplete()
        {
            if (subscriptions.remove(taskId, subscription)) {
                watched.remove(taskId);
            }
        }
    }
}
