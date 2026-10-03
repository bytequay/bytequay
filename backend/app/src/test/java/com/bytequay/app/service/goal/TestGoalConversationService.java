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
package com.bytequay.app.service.goal;

import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.GoalExchangeStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalConversationService
{
    private static final GoalExchange.Reply QUESTION = new GoalExchange.Reply("You want to explain reads.",
            Optional.of(new GoalExchange.Question("Do you know the Iceberg specification?", ImmutableList.of("Yes", "Not yet"))), Optional.empty());
    private static final GoalExchange.Reply PROPOSAL = new GoalExchange.Reply("I understand the goal.", Optional.empty(),
            Optional.of(new GoalExchange.Proposal("Iceberg reads", "Explain how reads plan files", "Trace a scan end to end", "Knows the specification", "Five hours a week")));

    private final InMemoryGoalStore goalStore = new InMemoryGoalStore();
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final GoalService goals = new GoalServiceImpl(goalStore, workspaces, work, mock(GoalReferenceReader.class), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private final Exchanges exchanges = new Exchanges();
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final GoalPlanner planner = mock(GoalPlanner.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC);
    private final GoalConversationService service = new GoalConversationService(goals, exchanges, models, planner, clock, queued::add);

    @BeforeEach
    void setUp()
    {
        when(workspaces.findById("workspace")).thenReturn(Optional.of(mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE))));
        when(work.list("workspace")).thenReturn(ImmutableList.of());
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.of("workspace")))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "codex", "Codex", "gpt", "GPT", Optional.empty()));
    }

    @Test
    void theFirstAnswerStartsAPinnedGoalAndAsksItsAgentOnce()
    {
        GoalView goal = service.start("workspace", "op-1", "  I want to become the Iceberg connector expert\nwith reads first");
        assertThat(goal.title()).isEqualTo("I want to become the Iceberg connector expert");
        assertThat(goal.pinned()).isTrue();
        assertThat(service.start("workspace", "op-1", "I want to become the Iceberg connector expert").id()).isEqualTo(goal.id());
        assertThat(goals.list("workspace")).hasSize(1);
        assertThat(queued).hasSize(1);
        assertThat(service.list("workspace", goal.id())).singleElement().satisfies(exchange -> {
            assertThat(exchange.state()).isEqualTo(GoalExchange.State.PENDING);
            assertThat(exchange.message()).isEqualTo("I want to become the Iceberg connector expert\nwith reads first");
        });

        doReturn(QUESTION).when(planner).answer(any(), any(), anyList(), anyString());
        queued.removeFirst().run();
        assertThat(service.list("workspace", goal.id())).singleElement().satisfies(exchange -> {
            assertThat(exchange.state()).isEqualTo(GoalExchange.State.ANSWERED);
            assertThat(exchange.reply()).contains(QUESTION);
            assertThat(exchange.model()).contains("codex/gpt");
        });
    }

    @Test
    void oneMessageAtATimeAndOnlyTheLastFailureIsAskedAgain()
    {
        GoalView goal = goals.create("workspace", "Iceberg", "");
        service.ask("workspace", goal.id(), "op-1", "Reads first");
        assertThatThrownBy(() -> service.ask("workspace", goal.id(), "op-2", "Writes too"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("still answering");
        assertThat(service.ask("workspace", goal.id(), "op-1", "Reads first")).hasSize(1);

        doThrow(new AgentUnavailableException("Codex is not signed in")).when(planner).answer(any(), any(), anyList(), anyString());
        queued.removeFirst().run();
        assertThat(service.list("workspace", goal.id())).singleElement().satisfies(exchange -> {
            assertThat(exchange.state()).isEqualTo(GoalExchange.State.FAILED);
            assertThat(exchange.failure()).contains("Codex is not signed in");
            assertThat(exchange.model()).contains("codex/gpt");
        });

        service.retry("workspace", goal.id(), "op-1");
        service.retry("workspace", goal.id(), "op-1");
        assertThat(queued).hasSize(1);
        doReturn(QUESTION).when(planner).answer(any(), any(), anyList(), anyString());
        queued.removeFirst().run();
        assertThat(service.list("workspace", goal.id())).singleElement().satisfies(exchange -> assertThat(exchange.state()).isEqualTo(GoalExchange.State.ANSWERED));
        assertThatThrownBy(() -> service.retry("workspace", goal.id(), "op-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only the last message can be asked again");

        // The next request is built from every earlier exchange.
        service.ask("workspace", goal.id(), "op-2", "Yes, I know the specification");
        doAnswer(invocation -> {
            assertThat(invocation.<List<GoalExchange>>getArgument(2)).extracting(GoalExchange::id).containsExactly("op-1");
            return PROPOSAL;
        }).when(planner).answer(any(), any(), anyList(), anyString());
        queued.removeFirst().run();
        assertThat(service.list("workspace", goal.id())).extracting(GoalExchange::state).containsExactly(GoalExchange.State.ANSWERED, GoalExchange.State.ANSWERED);
    }

    @Test
    void acceptingAProposalSavesTheDefinitionOnceAndAStaleOneIsRefused()
    {
        GoalView goal = goals.create("workspace", "Iceberg", "");
        service.ask("workspace", goal.id(), "op-1", "Reads first");
        doReturn(QUESTION).when(planner).answer(any(), any(), anyList(), anyString());
        queued.removeFirst().run();
        assertThatThrownBy(() -> service.accept("workspace", goal.id(), "op-1", Optional.empty()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("proposes no definition");

        service.ask("workspace", goal.id(), "op-2", "Yes");
        doReturn(PROPOSAL).when(planner).answer(any(), any(), anyList(), anyString());
        queued.removeFirst().run();
        GoalView defined = service.accept("workspace", goal.id(), "op-2", Optional.of("Iceberg reads, then writes"));
        assertThat(defined.title()).isEqualTo("Iceberg reads, then writes");
        assertThat(defined.description()).isEqualTo("Explain how reads plan files");
        assertThat(defined.definition()).hasValueSatisfying(definition -> {
            assertThat(definition.successDefinition()).isEqualTo("Trace a scan end to end");
            assertThat(definition.startingKnowledge()).isEqualTo("Knows the specification");
            assertThat(definition.exchangeId()).isEqualTo("op-2");
        });
        assertThat(service.accept("workspace", goal.id(), "op-2", Optional.empty()).title()).isEqualTo("Iceberg reads, then writes");

        // A proposal asked before the user's own rename cannot overwrite it.
        service.ask("workspace", goal.id(), "op-3", "Change the title");
        goals.update("workspace", goal.id(), Optional.of("My title"), Optional.empty(), false);
        queued.removeFirst().run();
        assertThatThrownBy(() -> service.accept("workspace", goal.id(), "op-3", Optional.empty()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("The goal changed after this was proposed");
        assertThat(goals.list("workspace").getFirst().title()).isEqualTo("My title");
    }

    @Test
    void aRestartSettlesOnlyRequestsNobodyIsAnswering()
    {
        GoalView goal = goals.create("workspace", "Iceberg", "");
        exchanges.save(GoalExchange.ask("op-0", goal.id(), "workspace", "Asked before the restart", 0, Instant.EPOCH));
        service.settleInterrupted();
        assertThat(service.list("workspace", goal.id())).singleElement().satisfies(exchange -> {
            assertThat(exchange.state()).isEqualTo(GoalExchange.State.FAILED);
            assertThat(exchange.failure()).contains(GoalConversationService.INTERRUPTED);
        });

        service.retry("workspace", goal.id(), "op-0");
        service.settleInterrupted();
        assertThat(service.list("workspace", goal.id())).singleElement().satisfies(exchange -> assertThat(exchange.state()).isEqualTo(GoalExchange.State.PENDING));
        // An answer that arrives after its request was settled elsewhere does not overwrite that settlement.
        GoalExchange pending = exchanges.find("op-0").orElseThrow();
        exchanges.save(pending.failed(Optional.empty(), GoalConversationService.INTERRUPTED, Instant.EPOCH));
        doReturn(QUESTION).when(planner).answer(any(), any(), anyList(), anyString());
        queued.removeFirst().run();
        assertThat(exchanges.find("op-0")).map(GoalExchange::state).contains(GoalExchange.State.FAILED);
    }

    @Test
    void aLongFirstAnswerBecomesAShortWorkingTitle()
    {
        String title = GoalConversationService.title("Become someone who can explain every part of the Iceberg connector, review its pull requests, fix its "
                + "correctness bugs and eventually become a committer");
        assertThat(title).endsWith("…").hasSizeLessThanOrEqualTo(GoalConversationService.MAX_TITLE + 1);
        assertThat(title).doesNotContain("  ");
        assertThat(GoalConversationService.title("Iceberg\nsecond line")).isEqualTo("Iceberg");
    }

    private static final class Exchanges
            implements GoalExchangeStore
    {
        private final Map<String, GoalExchange> exchanges = new LinkedHashMap<>();

        @Override
        public Optional<GoalExchange> find(String exchangeId)
        {
            return Optional.ofNullable(exchanges.get(exchangeId));
        }

        @Override
        public List<GoalExchange> list(String goalId)
        {
            return exchanges.values().stream().filter(exchange -> exchange.goalId().equals(goalId))
                    .sorted(Comparator.comparing(GoalExchange::askedAt).thenComparing(GoalExchange::id)).collect(toImmutableList());
        }

        @Override
        public List<GoalExchange> pending()
        {
            return exchanges.values().stream().filter(exchange -> exchange.state() == GoalExchange.State.PENDING).collect(toImmutableList());
        }

        @Override
        public void save(GoalExchange exchange)
        {
            exchanges.put(exchange.id(), exchange);
        }
    }
}
