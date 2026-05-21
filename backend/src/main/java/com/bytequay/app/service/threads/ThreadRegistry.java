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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Single source of truth for which threads have a live in-memory
 * {@link ThreadAgent}. Controllers go through here to look up,
 * create, or replace sessions; they never instantiate a session
 * directly.
 *
 * <p>Sessions are created lazily — calling {@link #getOrCreate} for a
 * thread that the registry hasn't seen yet builds a fresh session
 * around its persisted state. This means an app restart followed by
 * a UI poll seamlessly recreates the session map without us having
 * to eagerly walk the {@code threads} table at boot.
 */
@Component
public class ThreadRegistry
{
    private final ThreadStore store;
    private final TaskStore taskStore;
    private final StreamJsonParser parser;
    private final ObjectMapper mapper;
    private final McpPermissionGate gate;
    private final ExecutorService executor;
    private final CheckpointTrigger checkpointTrigger;
    private final Supplier<String> workspaceMemoryProvider;
    private final WorktreeLeaseService leaseService;
    private final ConcurrentHashMap<String, ThreadAgent> sessions = new ConcurrentHashMap<>();

    @Autowired
    public ThreadRegistry(
            ThreadStore store,
            TaskStore taskStore,
            ObjectMapper mapper,
            McpPermissionGate gate,
            CheckpointTrigger checkpointTrigger,
            WorkspaceService workspaces,
            WorktreeLeaseService leaseService)
    {
        this(store, taskStore, new StreamJsonParser(mapper), mapper, gate,
                ClaudeCodeCliThreadAgent.defaultExecutor(), checkpointTrigger,
                () -> workspaces.getMemory(WorkspaceService.DEFAULT_WORKSPACE_ID),
                leaseService);
    }

    ThreadRegistry(
            ThreadStore store,
            TaskStore taskStore,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor,
            CheckpointTrigger checkpointTrigger,
            Supplier<String> workspaceMemoryProvider,
            WorktreeLeaseService leaseService)
    {
        this.store = requireNonNull(store, "store is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.parser = requireNonNull(parser, "parser is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.checkpointTrigger = requireNonNull(checkpointTrigger, "checkpointTrigger is null");
        this.workspaceMemoryProvider = requireNonNull(workspaceMemoryProvider, "workspaceMemoryProvider is null");
        this.leaseService = requireNonNull(leaseService, "leaseService is null");
    }

    public Optional<ThreadAgent> find(String threadId)
    {
        return Optional.ofNullable(sessions.get(threadId));
    }

    /**
     * Build the session if it isn't already in the map, otherwise
     * return the existing one. The {@link Thread} argument seeds the
     * fresh session's status / metrics, so callers should pass the
     * latest {@link ThreadStore#findThreadById} result.
     */
    public ThreadAgent getOrCreate(Thread thread)
    {
        requireNonNull(thread, "thread is null");
        return sessions.computeIfAbsent(thread.id(), id -> build(thread));
    }

    /** Drop a session from the map. The session is not stopped — call
     *  {@link ThreadAgent#stop} first if you want that. */
    public void evict(String threadId)
    {
        sessions.remove(threadId);
    }

    private ThreadAgent build(Thread thread)
    {
        return switch (thread.kind()) {
            case CLI_AGENT -> new ClaudeCodeCliThreadAgent(
                    thread, store, taskStore, parser, mapper, gate, executor, checkpointTrigger,
                    workspaceMemoryProvider, leaseService);
            case LOGIC_LOOP -> throw new UnsupportedOperationException(
                    "LOGIC_LOOP sessions land in a later slice");
        };
    }

    @PreDestroy
    void shutdown()
    {
        executor.shutdownNow();
    }
}
