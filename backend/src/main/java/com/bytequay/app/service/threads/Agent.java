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
import com.bytequay.app.domain.ThreadStatus;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One running or paused agent runtime. Scope-specific entry points expose
 * this as {@link TrunkAgent}, {@link TaskBrainAgent}, or {@link StageAgent};
 * {@link ThreadAgent} remains the old compatibility name.
 */
public interface Agent
{
    String id();

    ThreadKind kind();

    String provider();

    String model();

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

    default void setActiveStage(String stageId)
    {
    }

    default void setActiveAgentRun(String agentRunId)
    {
    }

    default void setMcpAgentKey(String agentKey)
    {
    }

    void interrupt();

    void pause();

    void resume();

    void stop();

    void notifyPermissionRequested(String callId, String toolName, String summary);

    boolean decide(String callId, PermissionDecision decision);

    void grantToolBudget(String toolName, int count);

    OptionalInt tryConsumeToolBudget(String toolName);

    void notifyPermissionAutoAllowed(String callId, String toolName, int remaining);

    Runnable subscribeToEvents(Consumer<StreamEvent> listener);
}
