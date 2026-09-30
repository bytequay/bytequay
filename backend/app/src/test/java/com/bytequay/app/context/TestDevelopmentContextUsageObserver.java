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
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
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
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Flow;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
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
    private final ContextWebLookups lookups = mock(ContextWebLookups.class);
    private final DevelopmentContextUsageObserver observer = new DevelopmentContextUsageObserver(events, tasks, turns, receipts, usage, lookups);

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

    @Test
    void keepsObservingWhenAnEarlierTurnsCompletionReplays()
    {
        TurnId next = TurnId.random();
        String reference = "context-note:" + UUID.randomUUID();
        when(tasks.find(taskId)).thenReturn(Optional.of(task()));
        when(turns.findActive(taskId)).thenReturn(Optional.of(AgentTurn.accepted(next, taskId, "next",
                new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of()), Instant.EPOCH)));
        when(receipts.findReference(taskId.toString(), next.toString(), reference)).thenReturn(Optional.of(receipt(reference, DevelopmentContextReceipt.Kind.READ)));
        List<AgentEventEnvelope> history = ImmutableList.of(
                envelope(1, turnId, new AgentEventPayload.Lifecycle(AgentEventPayload.LifecyclePhase.TURN_COMPLETED, "Done")),
                envelope(2, next, new AgentEventPayload.ToolActivity("mcp__bytequay__repository_context_lookup", ActivityState.COMPLETED,
                        "context-receipt:" + reference, Optional.empty())));
        when(events.readHistory(taskId, OptionalLong.empty(), OptionalLong.empty())).thenReturn(new AgentHistoryPage(history, OptionalLong.empty(), OptionalLong.empty(), 2));
        when(events.subscribe(eq(taskId), anyLong())).thenAnswer(call -> replay(history, call.getArgument(1)));

        observer.watch(taskId);

        verify(usage, timeout(5_000)).confirm("workspace", reference, ContextUsageEvent.Stage.OPENED);
        verify(events, times(1)).subscribe(eq(taskId), anyLong());
    }

    @Test
    void countsATurnsCompletedWebStepsOnceAndRecordsThemWhenItSettles()
    {
        when(tasks.find(taskId)).thenReturn(Optional.of(task()));
        AgentEventEnvelope search = envelope(1, turnId, new AgentEventPayload.ToolActivity("Web search", ActivityState.COMPLETED, "Opened https://example.com/spec",
                Optional.empty()));
        observer.count(search);
        observer.count(search);
        observer.count(envelope(2, turnId, new AgentEventPayload.ToolActivity("WebFetch", ActivityState.COMPLETED, "https://example.com/spec", Optional.empty())));
        observer.count(envelope(3, turnId, new AgentEventPayload.ToolActivity("WebFetch", ActivityState.STARTED, "https://example.com/other", Optional.empty())));
        observer.count(envelope(4, turnId, new AgentEventPayload.ToolActivity("Bash", ActivityState.COMPLETED, "mvn verify", Optional.empty())));
        observer.count(envelope(5, TurnId.random(), new AgentEventPayload.ToolActivity("WebSearch", ActivityState.COMPLETED, "delta", Optional.empty())));

        observer.count(envelope(6, turnId, new AgentEventPayload.Lifecycle(AgentEventPayload.LifecyclePhase.TURN_COMPLETED, "Done")));

        verify(lookups).record("workspace", new ContextWebLookups.Turn(taskId.toString(), turnId.toString(), 2, Instant.EPOCH));
    }

    /** Replays the events after {@code after} on request, then stays open like a live stream. */
    private static Flow.Publisher<AgentEventEnvelope> replay(List<AgentEventEnvelope> history, long after)
    {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription()
        {
            private int next = (int) Math.min(history.size(), after);

            @Override
            public synchronized void request(long count)
            {
                for (long sent = 0; sent < count && next < history.size(); sent++) {
                    subscriber.onNext(history.get(next++));
                }
            }

            @Override
            public void cancel() {}
        });
    }

    private AgentEventEnvelope envelope(long sequence, TurnId turn, AgentEventPayload payload)
    {
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), sequence, taskId, Optional.of(turn), AgentEventSource.root(),
                Instant.EPOCH, payload);
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
