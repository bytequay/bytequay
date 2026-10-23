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

import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.app.agent.runtime.TaskEventWatcher;
import com.bytequay.core.store.WorkspaceStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Objects.requireNonNull;

/** Observes only explicitly requested spec checks. Startup restores observation, never dispatches a check. */
@Component
public class ContextBuildCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(ContextBuildCoordinator.class);
    private final TaskEventWatcher watcher;
    private final WorkspaceStore workspaces;
    private final ContextBuildStore builds;
    private final ObjectProvider<ContextBuildService> service;
    private final ObjectProvider<ContextSpecCheckService> checks;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("context-build-reconcile-", 0).factory());

    public ContextBuildCoordinator(AgentEventService events, WorkspaceStore workspaces, ContextBuildStore builds, ObjectProvider<ContextBuildService> service,
            ObjectProvider<ContextSpecCheckService> checks)
    {
        this.watcher = new TaskEventWatcher(requireNonNull(events, "events is null"), "Spec check task");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.builds = requireNonNull(builds, "builds is null");
        this.service = requireNonNull(service, "service is null");
        this.checks = requireNonNull(checks, "checks is null");
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
                log.warn("Cannot restore the spec check of workspace {}: {}", workspace.id(), failure.toString());
            }
        });
    }

    /** Subscription only: the caller still owns the creation/first-dispatch lock. */
    public void watch(ContextBuild build)
    {
        watcher.watch(build.taskId(), TaskId.valueOf(build.taskId()), item -> {
            if (item.payload() instanceof AgentEventPayload.Lifecycle lifecycle) {
                switch (lifecycle.phase()) {
                    case TURN_COMPLETED, INTERRUPTED, TASK_COMPLETED, TASK_FAILED -> reconcileLater(build);
                    default -> {}
                }
            }
        }, () -> reconcileLater(build));
    }

    public void unwatch(String taskId)
    {
        watcher.unwatch(taskId);
    }

    /** Settles the turn, then lets its spec check start the next reproduction; one executor keeps them in order. */
    private void reconcileLater(ContextBuild build)
    {
        executor.execute(() -> {
            service.getObject().reconcile(build.workspaceId(), build.taskId());
            try {
                checks.getObject().continueCheck(build.workspaceId());
            }
            catch (RuntimeException failure) {
                log.warn("Spec check for workspace {} stopped: {}", build.workspaceId(), failure.toString());
            }
        });
    }

    @PreDestroy
    public void close()
    {
        watcher.close();
        executor.shutdownNow();
    }
}
