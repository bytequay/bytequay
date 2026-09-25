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
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.context.DevelopmentContextReceipt;
import com.bytequay.core.context.DevelopmentContextReceiptStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDevelopmentContextUsageObserver
{
    private final TaskId taskId = TaskId.random();
    private final TurnId turnId = TurnId.random();
    private final AgentEventService events = mock(AgentEventService.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final DevelopmentContextReceiptStore receipts = mock(DevelopmentContextReceiptStore.class);
    private final ContextUsageRecorder usage = mock(ContextUsageRecorder.class);
    private final DevelopmentContextUsageObserver observer = new DevelopmentContextUsageObserver(events, tasks, turns, receipts, usage);

    @AfterEach
    void close()
    {
        observer.close();
    }

    @Test
    void onlyExactCompletedSearchResultConfirmsTaskOwnedSearch()
    {
        String reference = "context-search:" + UUID.randomUUID();
        when(tasks.find(taskId)).thenReturn(Optional.of(task()));
        when(receipts.findReference(taskId.toString(), turnId.toString(), reference))
                .thenReturn(Optional.of(receipt(reference, DevelopmentContextReceipt.Kind.SEARCH)));

        observer.confirm(event("bytequay/repository_context_lookup", ActivityState.COMPLETED, reference, turnId));

        verify(usage).confirm("workspace", reference, ContextUsageEvent.Stage.FOUND);
        verify(usage).confirm("workspace", reference, ContextUsageEvent.Stage.NO_MATCH);
        verify(usage).confirm("workspace", reference, ContextUsageEvent.Stage.SCOPE_DEMAND);
    }

    @Test
    void readRequiresExactReceiptAndTurn()
    {
        String reference = "context-note:" + UUID.randomUUID();
        when(tasks.find(taskId)).thenReturn(Optional.of(task()));
        when(receipts.findReference(taskId.toString(), turnId.toString(), reference))
                .thenReturn(Optional.of(receipt(reference, DevelopmentContextReceipt.Kind.READ)));

        observer.confirm(event("mcp__bytequay__repository_context_lookup", ActivityState.COMPLETED, reference, TurnId.random()));
        observer.confirm(event("mcp__bytequay__repository_context_lookup", ActivityState.FAILED, reference, turnId));
        observer.confirm(event("other_tool", ActivityState.COMPLETED, reference, turnId));
        verifyNoInteractions(usage);

        observer.confirm(event("mcp__bytequay__repository_context_lookup", ActivityState.COMPLETED, reference, turnId));
        verify(usage).confirm("workspace", reference, ContextUsageEvent.Stage.OPENED);
    }

    private AgentTask task()
    {
        TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("example", "project"),
                Path.of("/tmp/bytequay-context", taskId.toString()), "bytequay/task/" + taskId, "a".repeat(40));
        return AgentTask.created(taskId, true, "Development", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
    }

    private DevelopmentContextReceipt receipt(String reference, DevelopmentContextReceipt.Kind kind)
    {
        return new DevelopmentContextReceipt(taskId.toString(), turnId.toString(), "operation", reference, kind,
                "request", "{}", "payload", Instant.EPOCH);
    }

    private AgentEventEnvelope event(String name, ActivityState state, String reference, TurnId turn)
    {
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, taskId,
                Optional.of(turn), AgentEventSource.root(), Instant.EPOCH,
                new AgentEventPayload.ToolActivity(name, state, "context-receipt:" + reference, Optional.empty()));
    }
}
