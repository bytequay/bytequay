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
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.WorkItem;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalConversationService
{
    private static final GoalExchange.Reply QUESTION = new GoalExchange.Reply("You want to explain reads.",
            Optional.of(new GoalExchange.Question("Do you know the Iceberg specification?", ImmutableList.of("Yes", "Not yet"))), Optional.empty(), Optional.empty(),
            Optional.empty());
    private static final GoalExchange.Reply RESEARCH = new GoalExchange.Reply("Read the specification before the connector.", Optional.empty(), Optional.empty(),
            Optional.of(new GoalRecommendation.Card(GoalRecommendation.Kind.RESEARCH, "Read the Iceberg specification", "You start from the specification",
                    "Table metadata, snapshots and manifests", ImmutableList.of("https://iceberg.apache.org/spec/"), Optional.empty())), Optional.empty());
    private static final GoalExchange.Reply PROPOSAL = new GoalExchange.Reply("I understand the goal.", Optional.empty(),
            Optional.of(new GoalExchange.Proposal("Iceberg reads", "Explain how reads plan files", "Trace a scan end to end", "Knows the specification", "Five hours a week")),
            Optional.empty(), Optional.empty());
    private static final GoalExchange.Reply COURSE = new GoalExchange.Reply("Here is a course from the notes.", Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(new GoalCourse.Draft(ImmutableList.of(
                    new GoalCourse.DraftPart("Iceberg tables", ImmutableList.of(new GoalCourse.DraftLesson("What a table is", "Explain metadata, snapshots and manifests"),
                            new GoalCourse.DraftLesson("Follow a snapshot", "Trace a snapshot to its data files"))),
                    new GoalCourse.DraftPart("The Trino connector", ImmutableList.of(new GoalCourse.DraftLesson("Plan a scan", "Explain how splits are planned")))))));

    private final InMemoryGoalStore goalStore = new InMemoryGoalStore();
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final InMemoryGoalRecommendationStore cards = new InMemoryGoalRecommendationStore();
    private final GoalService goals = new GoalServiceImpl(goalStore, workspaces, work, mock(GoalReferenceReader.class), cards, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private final Exchanges exchanges = new Exchanges();
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final GoalPlanner planner = mock(GoalPlanner.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC);
    private final GoalConversationService service = new GoalConversationService(goals, work, exchanges, cards, models, planner, clock, queued::add);

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

        doReturn(QUESTION).when(planner).answer(any(), any(), anyList(), any());
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

        doThrow(new AgentUnavailableException("Codex is not signed in")).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(service.list("workspace", goal.id())).singleElement().satisfies(exchange -> {
            assertThat(exchange.state()).isEqualTo(GoalExchange.State.FAILED);
            assertThat(exchange.failure()).contains("Codex is not signed in");
            assertThat(exchange.model()).contains("codex/gpt");
        });

        service.retry("workspace", goal.id(), "op-1");
        service.retry("workspace", goal.id(), "op-1");
        assertThat(queued).hasSize(1);
        doReturn(QUESTION).when(planner).answer(any(), any(), anyList(), any());
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
        }).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(service.list("workspace", goal.id())).extracting(GoalExchange::state).containsExactly(GoalExchange.State.ANSWERED, GoalExchange.State.ANSWERED);
    }

    @Test
    void acceptingAProposalSavesTheDefinitionOnceAndAStaleOneIsRefused()
    {
        GoalView goal = goals.create("workspace", "Iceberg", "");
        service.ask("workspace", goal.id(), "op-1", "Reads first");
        doReturn(QUESTION).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThatThrownBy(() -> service.accept("workspace", goal.id(), "op-1", Optional.empty()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("proposes no definition");

        service.ask("workspace", goal.id(), "op-2", "Yes");
        doReturn(PROPOSAL).when(planner).answer(any(), any(), anyList(), any());
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
        assertThat(queued).as("accepting tells the agent once").hasSize(1);
        queued.removeFirst().run();

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
    void theAgentFloatsACardOnlyOnceTheGoalIsDefinedAndNeverStacksOrRepeatsADismissedOne()
    {
        GoalView goal = goals.create("workspace", "Iceberg", "");
        service.ask("workspace", goal.id(), "op-1", "Reads first");
        doReturn(RESEARCH).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(cards.list(goal.id())).as("an undefined goal gets no card").isEmpty();

        service.ask("workspace", goal.id(), "op-2", "Yes");
        doReturn(PROPOSAL).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        service.accept("workspace", goal.id(), "op-2", Optional.empty());
        // Accepting tells the agent, which decides the next step without the user asking.
        assertThat(exchanges.find("accepted:op-2")).hasValueSatisfying(event -> assertThat(event.automatic()).isTrue());
        doReturn(RESEARCH).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(goals.list("workspace").getFirst().recommendations()).singleElement().satisfies(card -> {
            assertThat(card.card()).isEqualTo(RESEARCH.recommendation().orElseThrow());
            assertThat(card.state()).isEqualTo(GoalRecommendation.State.SHOWN);
        });

        service.ask("workspace", goal.id(), "op-3", "Anything else?");
        queued.removeFirst().run();
        assertThat(cards.list(goal.id())).as("cards of one kind do not stack").hasSize(1);

        GoalRecommendation shown = cards.list(goal.id()).getFirst();
        assertThat(service.dismiss("workspace", goal.id(), shown.id()).state()).isEqualTo(GoalRecommendation.State.DISMISSED);
        assertThat(goals.list("workspace").getFirst().recommendations()).isEmpty();
        service.ask("workspace", goal.id(), "op-4", "What next?");
        queued.removeFirst().run();
        assertThat(cards.list(goal.id())).as("a dismissed card does not return unchanged").hasSize(1);
        assertThatThrownBy(() -> service.dismiss("workspace", goal.id(), "missing")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Card not found");
    }

    @Test
    void acceptingACourseSavesItsLessonsAsOrderedLearnItemsOnce()
    {
        GoalView goal = goals.create("workspace", "Iceberg", "");
        service.ask("workspace", goal.id(), "op-1", "What next?");
        doReturn(COURSE).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();

        GoalView planned = service.acceptCourse("workspace", goal.id(), "op-1");
        assertThat(planned.course()).hasValueSatisfying(course -> {
            assertThat(course.exchangeId()).isEqualTo("op-1");
            assertThat(course.parts()).extracting(GoalCourse.Part::title).containsExactly("Iceberg tables", "The Trino connector");
            assertThat(course.parts().get(0).lessons()).hasSize(2);
        });
        String first = planned.course().orElseThrow().parts().get(0).lessons().get(0);
        verify(work).create("workspace", first, Optional.of(goal.id()), "What a table is", WorkItem.Kind.LEARN, "Explain metadata, snapshots and manifests",
                WorkItem.CompletionRule.ACCEPTED_RESULT);
        assertThat(exchanges.find("course:op-1")).hasValueSatisfying(event -> assertThat(event.automatic()).isTrue());

        // The same lessons of the same proposal are the same work items, and the goal keeps one course.
        assertThat(service.acceptCourse("workspace", goal.id(), "op-1").course()).isEqualTo(planned.course());
        verify(work, times(2)).create(eq("workspace"), eq(first), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.acceptCourse("workspace", goal.id(), "missing")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aLessonCardMustNameAnUnfinishedLessonOfTheCourse()
    {
        GoalView goal = goals.create("workspace", "Iceberg", "");
        service.ask("workspace", goal.id(), "op-1", "Reads first");
        doReturn(PROPOSAL).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        service.accept("workspace", goal.id(), "op-1", Optional.empty());
        GoalExchange.Reply unknown = new GoalExchange.Reply("Take the next lesson.", Optional.empty(), Optional.empty(), Optional.of(new GoalRecommendation.Card(
                GoalRecommendation.Kind.LESSON, "What a table is", "You are ready", "Table metadata", ImmutableList.of(), Optional.of("not-a-lesson"))), Optional.empty());
        doReturn(unknown).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(cards.list(goal.id())).as("a card for a lesson outside the course is dropped").isEmpty();
    }

    @Test
    void anEventIsToldOnceAndNeverWhileTheAgentAnswers()
    {
        GoalView goal = goals.create("workspace", "Iceberg", "");
        service.tell("workspace", goal.id(), "research:task-1", "The research run saved 2 notes: Manifest lists; Snapshots.");
        service.tell("workspace", goal.id(), "research:task-1", "The research run saved 2 notes: Manifest lists; Snapshots.");
        service.tell("workspace", goal.id(), "research:task-2", "Another event while the first is answered");
        assertThat(queued).hasSize(1);
        assertThat(service.list("workspace", goal.id())).singleElement().satisfies(event -> {
            assertThat(event.automatic()).isTrue();
            assertThat(event.message()).startsWith("The research run saved 2 notes");
        });
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
        doReturn(QUESTION).when(planner).answer(any(), any(), anyList(), any());
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
