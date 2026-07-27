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
import com.bytequay.app.service.skills.ManagedSkill;
import com.bytequay.app.service.skills.ManagedSkillBundle;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

final class AgentViews
{
    private AgentViews() {}

    static TrunkAgent trunk(ThreadAgent agent)
    {
        return new Trunk(agent);
    }

    static TaskBrainAgent taskBrain(ThreadAgent agent)
    {
        return new TaskBrain(agent);
    }

    static TaskAgent task(ThreadAgent agent)
    {
        return new Task(agent);
    }

    private abstract static class Forwarding
            implements ThreadAgent
    {
        private final ThreadAgent delegate;

        Forwarding(ThreadAgent delegate)
        {
            this.delegate = delegate;
        }

        @Override public String id() { return delegate.id(); }
        @Override public ThreadKind kind() { return delegate.kind(); }
        @Override public String provider() { return delegate.provider(); }
        @Override public String model() { return delegate.model(); }
        @Override public String workingDir() { return delegate.workingDir(); }
        @Override public String branchName() { return delegate.branchName(); }
        @Override public ThreadStatus status() { return delegate.status(); }
        @Override public String lastErrorDetail() { return delegate.lastErrorDetail(); }
        @Override public AgentMetrics metrics() { return delegate.metrics(); }
        @Override public List<ThreadMessage> history() { return delegate.history(); }
        @Override public CompletionStage<Void> send(String userInput) { return delegate.send(userInput); }
        @Override public void setActiveTask(String taskId) { delegate.setActiveTask(taskId); }
        @Override public String activeTaskId() { return delegate.activeTaskId(); }
        @Override public void setActiveStage(String stageId) { delegate.setActiveStage(stageId); }
        @Override public void setActiveScope(ThreadScope scope) { delegate.setActiveScope(scope); }
        @Override public String activeStageId() { return delegate.activeStageId(); }
        @Override public void setActiveAgentRun(String agentRunId) { delegate.setActiveAgentRun(agentRunId); }
        @Override public void setManagedSkillBundle(ManagedSkillBundle bundle) { delegate.setManagedSkillBundle(bundle); }
        @Override public void setActiveManagedSkillNames(List<String> names) { delegate.setActiveManagedSkillNames(names); }
        @Override public void setActiveManagedSkills(List<ManagedSkill> skills) { delegate.setActiveManagedSkills(skills); }
        @Override public void setActiveToolNames(Set<String> names) { delegate.setActiveToolNames(names); }
        @Override public void setMcpAgentKey(String agentKey) { delegate.setMcpAgentKey(agentKey); }
        @Override public void interrupt() { delegate.interrupt(); }
        @Override public void resume() { delegate.resume(); }
        @Override public void stop() { delegate.stop(); }
        @Override public void notifyPermissionRequested(String callId, String toolName, String summary)
        {
            delegate.notifyPermissionRequested(callId, toolName, summary);
        }
        @Override public boolean decide(String callId, PermissionDecision decision)
        {
            return delegate.decide(callId, decision);
        }
        @Override public void grantToolBudget(String toolName, int count)
        {
            delegate.grantToolBudget(toolName, count);
        }
        @Override public OptionalInt tryConsumeToolBudget(String toolName)
        {
            return delegate.tryConsumeToolBudget(toolName);
        }
        @Override public void notifyPermissionAutoAllowed(String callId, String toolName, int remaining)
        {
            delegate.notifyPermissionAutoAllowed(callId, toolName, remaining);
        }
        @Override public Runnable subscribeToEvents(Consumer<StreamEvent> listener)
        {
            return delegate.subscribeToEvents(listener);
        }
    }

    private static final class Trunk
            extends Forwarding
            implements TrunkAgent
    {
        Trunk(ThreadAgent delegate) { super(delegate); }
    }

    private static final class TaskBrain
            extends Forwarding
            implements TaskBrainAgent
    {
        TaskBrain(ThreadAgent delegate) { super(delegate); }
    }

    private static final class Task
            extends Forwarding
            implements TaskAgent
    {
        Task(ThreadAgent delegate) { super(delegate); }
    }
}
