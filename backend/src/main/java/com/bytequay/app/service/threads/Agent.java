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

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.bytequay.app.service.skills.ManagedSkill;
import com.bytequay.app.service.skills.ManagedSkillBundle;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One running or paused agent runtime.
 */
public interface Agent
{
    String id();

    ThreadKind kind();

    String provider();

    String model();

    /** Update the model used by the next provider subprocess turn. */
    default void updateWorkModel(WorkModel workModel)
    {
    }

    String workingDir();

    String branchName();

    ThreadStatus status();

    default String lastErrorDetail()
    {
        return null;
    }

    AgentMetrics metrics();

    List<ThreadMessage> history();

    CompletionStage<Void> send(String userInput);

    /** Task whose messages and tools the next turn belong to. */
    default void setActiveTask(String taskId)
    {
    }

    /** Current task attribution, used to locate shared task-brain sessions. */
    default String activeTaskId()
    {
        return null;
    }

    default void setActiveStage(String stageId)
    {
    }

    /** Authoritative scope of the scheduler turn about to run. */
    default void setActiveScope(ThreadScope scope)
    {
    }

    /** Current stage attribution, used to locate shared task-brain sessions. */
    default String activeStageId()
    {
        return null;
    }

    default void setActiveAgentRun(String agentRunId)
    {
    }

    default void setManagedSkillBundle(ManagedSkillBundle bundle)
    {
    }

    default void setActiveManagedSkillNames(List<String> names)
    {
    }

    default void setActiveManagedSkills(List<ManagedSkill> skills)
    {
    }

    default void setActiveToolNames(Set<String> names)
    {
    }

    default void setResolvedAgentContext(ResolvedAgentContext context)
    {
        if (context != null) {
            if (context.skills().isEmpty()) {
                setActiveManagedSkillNames(context.skillNames());
            }
            else {
                setActiveManagedSkills(context.skills());
            }
            setActiveToolNames(context.toolNames());
        }
    }

    default void setMcpAgentKey(String agentKey)
    {
    }

    void interrupt();

    void resume();

    void stop();

    void notifyPermissionRequested(String callId, String toolName, String summary);

    boolean decide(String callId, PermissionDecision decision);

    void grantToolBudget(String toolName, int count);

    OptionalInt tryConsumeToolBudget(String toolName);

    void notifyPermissionAutoAllowed(String callId, String toolName, int remaining);

    Runnable subscribeToEvents(Consumer<StreamEvent> listener);
}
