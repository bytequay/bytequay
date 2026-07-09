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
    /** The reserved agent key the trunk (planning) agent connects under.
     *  A stage agent connects under its registry stage key (stage id, else
     *  task id); the trunk has no task, so it uses this sentinel instead. */
    String TRUNK_AGENT_KEY = "trunk";

    /** Resolve the role of the caller on {@code threadId}. The MCP
     *  endpoint is per-thread, so this drives both the {@code
     *  tools/list} filter and the security-type check at tools/call.
     *
     *  <p>Resolves against the thread's first RUNNING turn — correct when
     *  the thread has a single agent in flight. With concurrent stage
     *  agents on one thread, prefer {@link #roleFor(String, String)} so the
     *  role resolves to the calling agent's own turn. */
    default AgentRole roleFor(String threadId)
    {
        return roleFor(threadId, null);
    }

    /** Resolve the role of the caller identified by ({@code threadId},
     *  {@code agentKey}). {@code agentKey} is the registry stage key of the
     *  calling agent ({@link #TRUNK_AGENT_KEY} for the trunk); null falls
     *  back to the thread's first RUNNING turn (legacy single-agent path). */
    AgentRole roleFor(String threadId, String agentKey);

    /** Set of capability axes the caller on {@code threadId} is
     *  allowed to exercise. The registry refuses any tool whose
     *  {@link ToolSpec#security()} isn't in this set. Thread-only overload —
     *  resolves against the first RUNNING turn. */
    default Set<SecurityType> grants(String threadId)
    {
        return grants(threadId, null);
    }

    /** Capability axes the caller ({@code threadId}, {@code agentKey}) may
     *  exercise, resolved against that agent's own RUNNING turn. */
    Set<SecurityType> grants(String threadId, String agentKey);

    /** The task + stage the thread's in-flight turn is scoped to, read
     *  from the running turn's stamped ids. Both null when no turn is
     *  running or the running turn is a trunk turn. Tool handlers use this
     *  to resolve their task from the actual running turn rather than
     *  guessing the thread's active task. */
    default RunningScope runningScope(String threadId)
    {
        return runningScope(threadId, null);
    }

    /** The task + stage of the RUNNING turn for ({@code threadId},
     *  {@code agentKey}) — the calling agent's own scope under concurrency. */
    RunningScope runningScope(String threadId, String agentKey);

    /** The registry stage key the running turn behind a {@link RunningScope}
     *  connects under: its stage id, else its task id, else the reserved
     *  trunk key. Tool handlers that hold a {@link ToolCall} (which carries
     *  the stamped task/stage) use this to address their own agent. */
    static String agentKeyFor(String taskId, String stageId)
    {
        if (stageId != null && !stageId.isBlank()) {
            return stageId;
        }
        if (taskId != null && !taskId.isBlank()) {
            return taskId;
        }
        return TRUNK_AGENT_KEY;
    }

    /** The stamped task/stage/run of a thread's running turn. */
    record RunningScope(String taskId, String stageId, String agentRunId)
    {
        /** No running turn, or a trunk turn — both ids null. */
        public static final RunningScope NONE = new RunningScope(null, null, null);
    }
}
