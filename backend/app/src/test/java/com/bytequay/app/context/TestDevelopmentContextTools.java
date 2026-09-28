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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.context.ContextNotebookLookup.Hit;
import com.bytequay.app.context.ContextNotebookLookup.Read;
import com.bytequay.app.context.ContextNotebookLookup.ScopeState;
import com.bytequay.app.context.ContextNotebookLookup.SearchQuery;
import com.bytequay.app.context.ContextNotebookLookup.SearchResult;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.DevelopmentContextReceipt;
import com.bytequay.core.context.DevelopmentContextReceiptStore;
import com.bytequay.core.review.AgentReviewStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDevelopmentContextTools
{
    private static final String WORKSPACE = "workspace";
    private final TaskId taskId = TaskId.random();
    private final TurnId turnId = TurnId.random();
    private AgentTask task;
    private AgentTurn turn;
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final ContextNotebookLookup lookup = mock(ContextNotebookLookup.class);
    private final ContextUsageRecorder usage = mock(ContextUsageRecorder.class);
    private final DevelopmentContextUsageObserver observer = mock(DevelopmentContextUsageObserver.class);
    private final AgentUnitOfWork unitOfWork = new AgentUnitOfWork()
    {
        @Override
        public <T> T execute(Work<T> work)
        {
            return work.run();
        }
    };
    private final MemoryReceipts receipts = new MemoryReceipts();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());
    private final DevelopmentContextTools service = new DevelopmentContextTools(tasks, turns, reviews, lookup,
            receipts, usage, observer, unitOfWork, mapper, Clock.systemUTC());

    @BeforeEach
    void ownedTurn()
    {
        TaskWorktree worktree = new TaskWorktree(taskId, WORKSPACE, new RepositoryIdentity("example", "project"),
                Path.of("/tmp/bytequay-context", taskId.toString()), "bytequay/task/" + taskId, "a".repeat(40));
        task = AgentTask.created(taskId, true, "Development", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH)
                .claimWorktreeMutation(WorktreeMutationClaim.turn(turnId, Instant.EPOCH), Instant.EPOCH);
        turn = AgentTurn.accepted(turnId, taskId, "turn", settings(true), Instant.EPOCH);
        when(tasks.find(taskId)).thenAnswer(ignored -> Optional.of(task));
        when(turns.findActive(taskId)).thenAnswer(ignored -> Optional.of(turn));
        when(reviews.findReviewByTask(taskId.toString())).thenReturn(Optional.empty());
    }

    @Test
    void enabledTurnGetsLookupInstructionsWithoutOfferingNotes()
    {
        String first = service.prepare(task, turn, "Review snapshot isolation");
        assertThat(first).contains("Review snapshot isolation", "LIST", "SEARCH", "READ")
                .doesNotContain("knowledge:project", "Snapshot isolation");
        assertThat(service.prepare(task, turn, "Review snapshot isolation")).isEqualTo(first);
        assertThat(receipts.listTurn(taskId.toString(), turnId.toString())).isEmpty();
        verifyNoInteractions(lookup);

        turn = turn.markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("provider-turn"), Optional.empty());
        service.delivered(task, turn);
        org.mockito.Mockito.verify(usage, org.mockito.Mockito.never()).confirm(eq(WORKSPACE), any(), any());
    }

    @Test
    void oldPreparedOfferReplaysExactlyAndConfirmsWhenDelivered()
    {
        String message = "Review snapshot isolation";
        String ref = "context-offer:" + turnId;
        String payload = """
                {"offerRef":"%s","notebookRevision":7,"packet":"Legacy exact offer", \
                "entries":[{"entryId":"knowledge:project","entryVersion":3,"kind":"KNOWLEDGE", \
                "scopeId":"project","title":"Project","included":true,"matchReasons":[]}], \
                "scopeStates":[],"omittedCount":0,"omittedScopes":0}
                """.formatted(ref);
        receipts.insert(new DevelopmentContextReceipt(taskId.toString(), turnId.toString(), "offer", ref,
                DevelopmentContextReceipt.Kind.OFFER, Hashing.sha256().hashString(message, StandardCharsets.UTF_8).toString(),
                payload, Hashing.sha256().hashString(payload, StandardCharsets.UTF_8).toString(), Instant.EPOCH));

        assertThat(service.prepare(task, turn, message)).contains("Legacy exact offer");
        assertThatThrownBy(() -> service.prepare(task, turn, "Changed request")).hasMessageContaining("changed");
        turn = turn.markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("provider-turn"), Optional.empty());
        service.delivered(task, turn);
        verify(usage).confirm(WORKSPACE, ref, com.bytequay.core.context.ContextUsageEvent.Stage.PROVIDED);
    }

    @Test
    void taskBoundSearchAndReadReplayButChangedVersionIsUnavailable()
            throws Exception
    {
        turn = turn.markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("provider-turn"), Optional.empty());
        SearchResult found = new SearchResult(7, "MATCHES", ImmutableList.of(new Hit("knowledge:project", 3, ContextEntryKind.KNOWLEDGE,
                "project", "Project", "Snapshot isolation", ImmutableList.of("Topic match"), "UNVERIFIED")),
                ImmutableList.of(new ScopeState("project", "READY", false)), 0, 0);
        when(lookup.search(eq(WORKSPACE), any(SearchQuery.class))).thenReturn(found);

        String request = "{\"action\":\"SEARCH\",\"query\":\"snapshot isolation\",\"operationKey\":\"first\"}";
        String result = service.call(taskId.toString(), request);
        assertThat(result).contains("knowledge:project");
        assertThat(service.call(taskId.toString(), request)).isEqualTo(result);
        assertThat(receipts.listTurn(taskId.toString(), turnId.toString())).hasSize(1);
        assertThatThrownBy(() -> service.call(taskId.toString(),
                "{\"action\":\"SEARCH\",\"query\":\"different\",\"operationKey\":\"first\"}"))
                .hasMessageContaining("different search");

        String ref = mapper.readTree(result).path("searchRef").asText();
        String readRequest = "{\"action\":\"READ\",\"searchRef\":\"" + ref
                + "\",\"entryId\":\"knowledge:project\",\"entryVersion\":3}";
        when(lookup.readEnabled(WORKSPACE, "knowledge:project", 3)).thenReturn(Optional.of(new Read(7, entry(),
                new ContextNoteReader.Read("Saved exact note", ImmutableMap.of(), ImmutableMap.of()))));
        String read = service.call(taskId.toString(), readRequest);
        assertThat(read).contains("Saved exact note").contains("AVAILABLE");
        assertThat(receipts.listTurn(taskId.toString(), turnId.toString())).hasSize(2);
        when(lookup.readEnabled(WORKSPACE, "knowledge:project", 3)).thenReturn(Optional.empty());
        assertThat(service.call(taskId.toString(), readRequest)).isEqualTo(read);
        assertThatThrownBy(() -> service.call(taskId.toString(), readRequest.replace("\"entryVersion\":3", "\"entryVersion\":4")))
                .hasMessageContaining("not in that");

        service.call(taskId.toString(), "{\"action\":\"SEARCH\",\"query\":\"\",\"paths\":[\"plugin/trino-iceberg/\"],\"operationKey\":\"path-only\"}");
        verify(lookup).search(WORKSPACE, new SearchQuery("", ImmutableList.of("plugin/trino-iceberg/")));
    }

    @Test
    void listPagesUseCurrentNotebookRevisionAndRetainedSearchReceipts()
            throws Exception
    {
        turn = turn.markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("provider-turn"), Optional.empty());
        SearchResult firstPage = new SearchResult(7, "MATCHES", ImmutableList.of(new Hit("knowledge:project", 3, ContextEntryKind.KNOWLEDGE,
                "project", "Project", "Snapshot isolation", ImmutableList.of(), "UNVERIFIED")), ImmutableList.of(), 1, 0, 1, 2, 2);
        SearchResult secondPage = new SearchResult(7, "MATCHES", ImmutableList.of(new Hit("lesson:project", 4, ContextEntryKind.MEMORY,
                "project", "Lesson", "A retained lesson", ImmutableList.of(), "UNVERIFIED")), ImmutableList.of(), 0, 0, 2, null, 2);
        when(lookup.list(WORKSPACE, 1, Optional.empty())).thenReturn(firstPage);
        when(lookup.list(WORKSPACE, 2, Optional.of(7L))).thenReturn(secondPage);

        String first = service.call(taskId.toString(), "{\"action\":\"LIST\",\"operationKey\":\"first\"}");
        assertThat(mapper.readTree(first).at("/result/nextPage").intValue()).isEqualTo(2);
        assertThat(service.call(taskId.toString(), "{\"action\":\"LIST\",\"operationKey\":\"first\"}")).isEqualTo(first);
        assertThatThrownBy(() -> service.call(taskId.toString(), "{\"action\":\"LIST\",\"operationKey\":\"second\",\"page\":2}"))
                .hasMessageContaining("notebookRevision");
        String second = service.call(taskId.toString(),
                "{\"action\":\"LIST\",\"operationKey\":\"second\",\"page\":2,\"notebookRevision\":7}");
        assertThat(mapper.readTree(second).at("/result/hits/0/entryId").asText()).isEqualTo("lesson:project");
        assertThat(receipts.listTurn(taskId.toString(), turnId.toString())).hasSize(2);
        verify(lookup).list(WORKSPACE, 1, Optional.empty());
        verify(lookup).list(WORKSPACE, 2, Optional.of(7L));
    }

    @Test
    void exactReadNeedsNoPriorListOrSearch()
    {
        turn = turn.markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("provider-turn"), Optional.empty());
        when(lookup.readEnabled(WORKSPACE, "knowledge:project", 3)).thenReturn(Optional.of(new Read(7, entry(),
                new ContextNoteReader.Read("Saved exact note", ImmutableMap.of(), ImmutableMap.of()))));
        String request = "{\"action\":\"READ\",\"entryId\":\"knowledge:project\",\"entryVersion\":3}";
        String result = service.call(taskId.toString(), request);
        assertThat(result).contains("Saved exact note", "AVAILABLE");
        assertThat(receipts.listTurn(taskId.toString(), turnId.toString())).hasSize(1);
        when(lookup.readEnabled(WORKSPACE, "knowledge:project", 3)).thenReturn(Optional.empty());
        assertThat(service.call(taskId.toString(), request)).isEqualTo(result);
        assertThat(service.call(taskId.toString(), request.replace("\"entryVersion\":3", "\"entryVersion\":4")))
                .contains("VERSION_UNAVAILABLE");
    }

    @Test
    void offOrReviewOwnershipCannotUseTheDevelopmentTool()
    {
        turn = AgentTurn.accepted(turnId, taskId, "turn", settings(false), Instant.EPOCH);
        assertThat(service.prepare(task, turn, "Work on Iceberg")).isEqualTo("Work on Iceberg");
        verify(usage).record(argThat(event -> event.stage() == com.bytequay.core.context.ContextUsageEvent.Stage.ELIGIBLE
                && event.delivery() == com.bytequay.core.context.ContextUsageEvent.Delivery.CONFIRMED
                && event.contextEnabled().equals(Optional.of(false))));
        assertThat(service.resolve(taskId.toString())).isEmpty();
        turn = AgentTurn.accepted(turnId, taskId, "turn", settings(true), Instant.EPOCH);
        task = task.withLocalReviewHead(Optional.of("a".repeat(40)), Instant.EPOCH);
        assertThat(service.resolve(taskId.toString())).isEmpty();
        assertThatThrownBy(() -> service.call(taskId.toString(), "{\"action\":\"SEARCH\"}"))
                .hasMessageContaining("running development turn");
    }

    private static AgentTurnSettings settings(boolean enabled)
    {
        return new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO,
                Duration.ofMinutes(5), ImmutableList.of()).withRepositoryContext(enabled);
    }

    private static ContextEntry entry()
    {
        return new ContextEntry("knowledge:project", ContextEntryKind.KNOWLEDGE, "project", "Project", "Snapshot isolation",
                "Avoid stale snapshots", ImmutableList.of(), ImmutableList.of(), ContextEntryOrigin.USER, true, 3);
    }

    private static final class MemoryReceipts
            implements DevelopmentContextReceiptStore
    {
        private final Map<String, DevelopmentContextReceipt> saved = new LinkedHashMap<>();

        @Override
        public Optional<DevelopmentContextReceipt> find(String taskId, String turnId, String operationKey)
        {
            return Optional.ofNullable(saved.get(taskId + ":" + turnId + ":" + operationKey));
        }

        @Override
        public Optional<DevelopmentContextReceipt> findReference(String taskId, String turnId, String reference)
        {
            return saved.values().stream().filter(value -> value.taskId().equals(taskId) && value.turnId().equals(turnId)
                    && value.reference().equals(reference)).findFirst();
        }

        @Override
        public List<DevelopmentContextReceipt> listTurn(String taskId, String turnId)
        {
            return saved.values().stream().filter(value -> value.taskId().equals(taskId) && value.turnId().equals(turnId)).collect(ImmutableList.toImmutableList());
        }

        @Override
        public List<DevelopmentContextReceipt> listTask(String taskId, int limit)
        {
            return saved.values().stream().filter(value -> value.taskId().equals(taskId))
                    .sorted(java.util.Comparator.comparing(DevelopmentContextReceipt::createdAt).reversed()).limit(limit)
                    .collect(ImmutableList.toImmutableList());
        }

        @Override
        public List<String> listTaskIds(int limit)
        {
            return saved.values().stream().map(DevelopmentContextReceipt::taskId).distinct().limit(limit).collect(ImmutableList.toImmutableList());
        }

        @Override
        public long taskBytes(String taskId)
        {
            return saved.values().stream().filter(value -> value.taskId().equals(taskId)).mapToLong(value -> value.payloadJson().length()).sum();
        }

        @Override
        public void insert(DevelopmentContextReceipt receipt)
        {
            saved.put(receipt.taskId() + ":" + receipt.turnId() + ":" + receipt.operationKey(), receipt);
        }

        @Override
        public void deleteTask(String taskId)
        {
            saved.values().removeIf(value -> value.taskId().equals(taskId));
        }
    }
}
