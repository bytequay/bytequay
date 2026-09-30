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
import com.bytequay.agent.core.TaskId;
import com.bytequay.core.store.WorkspaceStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

import static java.util.Objects.requireNonNull;

/** Observes only explicitly requested builds. Startup restores observation, never dispatches learning. */
@Component
public class ContextBuildCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(ContextBuildCoordinator.class);
    private final AgentEventService events;
    private final WorkspaceStore workspaces;
    private final ContextBuildStore builds;
    private final ObjectProvider<ContextBuildService> service;
    private final ObjectProvider<ContextLearningService> learning;
    private final Map<String, Flow.Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("context-build-reconcile-", 0).factory());

    public ContextBuildCoordinator(AgentEventService events, WorkspaceStore workspaces, ContextBuildStore builds, ObjectProvider<ContextBuildService> service,
            ObjectProvider<ContextLearningService> learning)
    {
        this.events = requireNonNull(events, "events is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.builds = requireNonNull(builds, "builds is null");
        this.service = requireNonNull(service, "service is null");
        this.learning = requireNonNull(learning, "learning is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore()
    {
        workspaces.findAllRetained().forEach(workspace -> {
            try {
                builds.find(workspace.id()).ifPresent(build -> {
                    watch(build);
                    reconcileLater(build);
                });
            }
            catch (RuntimeException failure) {
                log.warn("Cannot restore repository learning for workspace {}: {}", workspace.id(), failure.toString());
            }
        });
    }

    /** Subscription only: the caller still owns the creation/first-dispatch lock. */
    public void watch(ContextBuild build)
    {
        if (subscriptions.containsKey(build.taskId())) {
            return;
        }
        try {
            TaskId id = TaskId.valueOf(build.taskId());
            long after = events.readHistory(id, OptionalLong.empty(), OptionalLong.empty()).latestSequence();
            events.subscribe(id, after).subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription)
                {
                    Flow.Subscription previous = subscriptions.putIfAbsent(build.taskId(), subscription);
                    if (previous != null) {
                        subscription.cancel();
                    }
                    else {
                        subscription.request(Long.MAX_VALUE);
                    }
                }

                @Override
                public void onNext(AgentEventEnvelope item)
                {
                    if (item.payload() instanceof AgentEventPayload.Lifecycle lifecycle) {
                        switch (lifecycle.phase()) {
                            case TURN_COMPLETED, INTERRUPTED, TASK_COMPLETED, TASK_FAILED -> reconcileLater(build);
                            default -> {}
                        }
                    }
                }

                @Override
                public void onError(Throwable failure)
                {
                    subscriptions.remove(build.taskId());
                    log.warn("Repository learning {} lost its task stream: {}", build.taskId(), failure.toString());
                }

                @Override
                public void onComplete()
                {
                    subscriptions.remove(build.taskId());
                    reconcileLater(build);
                }
            });
        }
        catch (RuntimeException failure) {
            log.warn("Repository learning {} cannot subscribe to its task: {}", build.taskId(), failure.toString());
        }
    }

    public void unwatch(String taskId)
    {
        Flow.Subscription subscription = subscriptions.remove(taskId);
        if (subscription != null) {
            subscription.cancel();
        }
    }

    /** Settles the run, then lets its batch start the next question; one executor keeps both in order. */
    private void reconcileLater(ContextBuild build)
    {
        executor.execute(() -> {
            service.getObject().reconcile(build.workspaceId(), build.taskId());
            try {
                learning.getObject().continueBatch(build.workspaceId());
            }
            catch (RuntimeException failure) {
                log.warn("Repository learning batch for workspace {} stopped: {}", build.workspaceId(), failure.toString());
            }
        });
    }

    @PreDestroy
    public void close()
    {
        subscriptions.values().forEach(Flow.Subscription::cancel);
        subscriptions.clear();
        executor.shutdownNow();
    }
}
