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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.ThreadScope;

import java.util.Set;

/**
 * Decides what a caller may do at tool-call time. The
 * {@link AgentToolRegistry} doesn't gate calls itself — it just
 * publishes the catalog; the resolver is the seam where authorisation
 * happens.
 *
 * <p>Today the implementation is a role→permission map keyed off the
 * thread's active task. The real permissions cascade (global →
 * workspace → thread → task, plus the initiator / autonomy envelope)
 * swaps in here behind the same interface — no caller changes.
 */
public interface PermissionResolver
{
    /** The reserved runtime key the trunk (planning) agent connects under.
     *  A Task agent connects under its task id; the trunk uses this sentinel. */
    String TRUNK_AGENT_KEY = "trunk";

    /** Resolve the role of the caller identified by ({@code threadId},
     *  {@code agentKey}). {@code agentKey} is the task id of a Task-owned
     *  agent or {@link #TRUNK_AGENT_KEY} for the trunk. */
    AgentRole roleFor(String threadId, String agentKey);

    /** Capability axes the caller ({@code threadId}, {@code agentKey}) may
     *  exercise, resolved against that agent's own RUNNING turn. */
    Set<SecurityType> grants(String threadId, String agentKey);

    /** The task + stage the thread's in-flight turn is scoped to, read
     *  from the running turn's stamped ids. Both null when no turn is
     *  running or the running turn is a trunk turn. Tool handlers use this
     *  to resolve their task from the actual running turn rather than
     *  guessing the thread's active task. */
    /** The task + stage of the RUNNING turn for ({@code threadId},
     *  {@code agentKey}) — the calling agent's own scope under concurrency. */
    RunningScope runningScope(String threadId, String agentKey);

    /** The runtime key the running turn behind a {@link RunningScope}
     *  connects under: its task id for task/stage work, or the reserved
     *  trunk key. A stage is a transcript scope, not a provider-session
     *  identity. */
    static String agentKeyFor(ThreadScope scope, String taskId)
    {
        if (scope == null) {
            throw new IllegalArgumentException("scope is null");
        }
        return switch (scope) {
            case TRUNK -> {
                if (taskId != null && !taskId.isBlank()) {
                    throw new IllegalArgumentException("TRUNK scope forbids taskId");
                }
                yield TRUNK_AGENT_KEY;
            }
            case TASK, STAGE -> {
                if (taskId == null || taskId.isBlank()) {
                    throw new IllegalArgumentException(scope + " scope requires taskId");
                }
                yield taskId;
            }
        };
    }

    /** The explicit scope and stamped identifiers of a running turn. */
    record RunningScope(ThreadScope scope, String taskId, String stageId, String agentRunId)
    {
        /** No running turn. */
        public static final RunningScope NONE = new RunningScope(null, null, null, null);
    }
}
