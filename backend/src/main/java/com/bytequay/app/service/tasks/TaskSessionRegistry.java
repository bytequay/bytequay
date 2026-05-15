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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static java.util.Objects.requireNonNull;

/**
 * Single source of truth for which tasks have a live in-memory
 * {@link AgentSession}. Controllers go through here to look up,
 * create, or replace sessions; they never instantiate a session
 * directly.
 *
 * <p>Sessions are created lazily — calling {@link #getOrCreate} for a
 * task that the registry hasn't seen yet builds a fresh session
 * around its persisted state. This means an app restart followed by
 * a UI poll seamlessly recreates the session map without us having
 * to eagerly walk the {@code tasks} table at boot.
 */
@Component
public class TaskSessionRegistry
{
    private final TaskStore store;
    private final StreamJsonParser parser;
    private final ObjectMapper mapper;
    private final McpPermissionGate gate;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public TaskSessionRegistry(TaskStore store, ObjectMapper mapper, McpPermissionGate gate)
    {
        this(store, new StreamJsonParser(mapper), mapper, gate, ClaudeCodeCliSession.defaultExecutor());
    }

    TaskSessionRegistry(
            TaskStore store,
            StreamJsonParser parser,
            ObjectMapper mapper,
            McpPermissionGate gate,
            ExecutorService executor)
    {
        this.store = requireNonNull(store, "store is null");
        this.parser = requireNonNull(parser, "parser is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    public Optional<AgentSession> find(String taskId)
    {
        return Optional.ofNullable(sessions.get(taskId));
    }

    /**
     * Build the session if it isn't already in the map, otherwise
     * return the existing one. The {@link Task} argument seeds the
     * fresh session's status / metrics, so callers should pass the
     * latest {@link TaskStore#findTaskById} result.
     */
    public AgentSession getOrCreate(Task task)
    {
        requireNonNull(task, "task is null");
        return sessions.computeIfAbsent(task.id(), id -> build(task));
    }

    /** Drop a session from the map. The session is not stopped — call
     *  {@link AgentSession#stop} first if you want that. */
    public void evict(String taskId)
    {
        sessions.remove(taskId);
    }

    private AgentSession build(Task task)
    {
        return switch (task.kind()) {
            case CLI_AGENT -> new ClaudeCodeCliSession(task, store, parser, mapper, gate, executor);
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
