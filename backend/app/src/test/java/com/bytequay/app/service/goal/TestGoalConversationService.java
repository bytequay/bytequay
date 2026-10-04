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
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.GoalTopic;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.GoalExchangeStore;
import com.bytequay.core.store.GoalTopicStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalConversationService
{
    private static final GoalExchange.Reply QUESTION = new GoalExchange.Reply("You want to explain reads.",
            Optional.of(new GoalExchange.Question("Do you know the Iceberg specification?", ImmutableList.of("Yes", "Not yet"))), Optional.empty(), Optional.empty(),
            Optional.empty(), ImmutableList.of());
    private static final GoalExchange.Reply RESEARCH = new GoalExchange.Reply("Read the specification before the connector.", Optional.empty(), Optional.empty(),
            Optional.of(new GoalRecommendation.Card(GoalRecommendation.Kind.RESEARCH, "Read the Iceberg specification", "You start from the specification",
                    "Table metadata, snapshots and manifests", ImmutableList.of("https://iceberg.apache.org/spec/"), Optional.empty(), Optional.empty())), Optional.empty(), ImmutableList.of());
    private static final GoalExchange.Reply PROPOSAL = new GoalExchange.Reply("I understand the goal.", Optional.empty(),
            Optional.of(new GoalExchange.Proposal("Iceberg reads", "Explain how reads plan files", "Trace a scan end to end", "Knows the specification", "Five hours a week")),
            Optional.empty(), Optional.empty(), ImmutableList.of());
    private static final GoalExchange.Reply COURSE = new GoalExchange.Reply("Here is a course from the notes.", Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(new GoalCourse.Draft(ImmutableList.of(
                    new GoalCourse.DraftPart("Iceberg tables", ImmutableList.of(new GoalCourse.DraftLesson("What a table is", "Explain metadata, snapshots and manifests"),
                            new GoalCourse.DraftLesson("Follow a snapshot", "Trace a snapshot to its data files"))),
                    new GoalCourse.DraftPart("The Trino connector", ImmutableList.of(new GoalCourse.DraftLesson("Plan a scan", "Explain how splits are planned")))))),
            ImmutableList.of());

    private final InMemoryGoalStore goalStore = new InMemoryGoalStore();
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final InMemoryGoalRecommendationStore cards = new InMemoryGoalRecommendationStore();
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final GoalResearchService research = mock(GoalResearchService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final Topics topics = new Topics();
    private final GoalQuestions questions = mock(GoalQuestions.class);
    private final GoalService goals = new GoalServiceImpl(goalStore, workspaces, work, mock(GoalReferenceReader.class), cards, topics,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private final Exchanges exchanges = new Exchanges();
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final GoalPlanner planner = mock(GoalPlanner.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC);
    private final GoalConversationService service = new GoalConversationService(goals, work, exchanges, cards, workspaces, issues,
            beans.getBeanProvider(GoalResearchService.class), questions, topics, models, planner, clock, queued::add);

    @BeforeEach
    void setUp()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.monitoredRepositoryId()).thenReturn(1L);
        when(workspace.monitoredRepository()).thenReturn(RepoRef.of("trinodb", "trino"));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        beans.registerSingleton("goalResearchService", research);
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
                GoalRecommendation.Kind.LESSON, "What a table is", "You are ready", "Table metadata", ImmutableList.of(), Optional.of("not-a-lesson"), Optional.empty())),
                Optional.empty(), ImmutableList.of());
        doReturn(unknown).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(cards.list(goal.id())).as("a card for a lesson outside the course is dropped").isEmpty();
    }

    @Test
    void aWorkCardNamesAnOpenIssueAndStartMakesItTheGoalsWorkUntilTheUserDecides()
    {
        GoalView goal = goals.create("workspace", "Delta Lake", "");
        service.ask("workspace", goal.id(), "op-1", "Reads first");
        doReturn(PROPOSAL).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        service.accept("workspace", goal.id(), "op-1", Optional.empty());
        doReturn(work(404)).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(cards.list(goal.id())).as("an issue the workspace does not hold open is dropped").isEmpty();

        when(issues.find(1, 31252)).thenReturn(Optional.of(new RepositoryIssue(7, 31252, "Checkpoint drops rows", "", "bo", "",
                "https://github.com/trinodb/trino/issues/31252", ImmutableList.of(), ImmutableList.of(), 0, Instant.EPOCH, Instant.EPOCH)));
        service.ask("workspace", goal.id(), "op-2", "What can I practice?");
        doReturn(work(31252)).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        GoalRecommendation card = cards.list(goal.id()).getFirst();
        assertThat(card.card().issue()).contains(31252);

        WorkItemView practice = new WorkItemView("work-1", "workspace", Optional.of(goal.id()), "Fix #31252", WorkItem.Kind.DEVELOP,
                "Key add and remove actions by path and deletion vector.", WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(),
                ImmutableList.of(), Instant.EPOCH, Instant.EPOCH);
        when(work.adopt(eq("workspace"), eq(goal.id()), any(), eq("Fix #31252"), eq("Key add and remove actions by path and deletion vector."), eq(GoalItemKind.ISSUE),
                eq("trinodb/trino#31252"))).thenReturn(practice);
        GoalRecommendation started = service.startCard("workspace", goal.id(), card.id());
        assertThat(started.state()).isEqualTo(GoalRecommendation.State.STARTED);
        assertThat(started.workItemId()).as("the issue became the goal's work, whose task the user sends").contains("work-1");
        assertThat(started.taskId()).isEmpty();
        assertThat(service.startCard("workspace", goal.id(), card.id())).isEqualTo(started);
        verify(work, times(1)).adopt(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(research);

        service.ask("workspace", goal.id(), "op-3", "Anything else to practice?");
        queued.removeFirst().run();
        assertThat(cards.list(goal.id())).as("no other practice floats while this one is open").hasSize(1);

        WorkItemDecided accepted = new WorkItemDecided("workspace", goal.id(), "work-1", "Fix #31252", WorkItem.Kind.DEVELOP, WorkItemCommand.Action.ACCEPT,
                "decision-1", "User accepted all criteria: Rows survive a checkpoint");
        service.decided(accepted);
        service.decided(accepted);
        assertThat(cards.find(card.id())).map(GoalRecommendation::state).contains(GoalRecommendation.State.DONE);
        assertThat(exchanges.list(goal.id())).filteredOn(exchange -> exchange.message().startsWith("The user accepted the result")).singleElement()
                .satisfies(told -> {
                    assertThat(told.automatic()).isTrue();
                    assertThat(told.message()).isEqualTo("The user accepted the result of \"Fix #31252\": User accepted all criteria: Rows survive a checkpoint");
                });
        queued.removeFirst().run();
        assertThat(cards.list(goal.id())).as("the next practice may float once the last one is decided").hasSize(2);

        int told = exchanges.list(goal.id()).size();
        service.decided(new WorkItemDecided("workspace", goal.id(), "lesson-1", "What a table is", WorkItem.Kind.LEARN, WorkItemCommand.Action.ACCEPT, "decision-2",
                "Finished the lesson."));
        assertThat(exchanges.list(goal.id())).as("a finished lesson tells the agent itself").hasSize(told);
    }

    @Test
    void aPrivateQuestionChangesATopicLevelUnlessTheUserSetIt()
    {
        GoalView goal = goals.create("workspace", "Iceberg", "");
        when(questions.recent(GoalPlanner.MAX_QUESTIONS)).thenReturn(ImmutableList.of(new GoalQuestions.Question("discussion:q-1", "What is a manifest list?",
                "Task Fix scan planning", Instant.EPOCH)));
        service.ask("workspace", goal.id(), "op-1", "What next?");
        doReturn(levels(new GoalTopic.Change("Manifests", GoalTopic.Level.NEW, ImmutableList.of("discussion:q-1", "discussion:invented")),
                new GoalTopic.Change("Snapshots", GoalTopic.Level.STRONG, ImmutableList.of("discussion:invented")))).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(goals.list("workspace").getFirst().topics()).singleElement().satisfies(topic -> {
            assertThat(topic.topic()).isEqualTo("Manifests");
            assertThat(topic.level()).isEqualTo(GoalTopic.Level.NEW);
            assertThat(topic.evidence()).as("only evidence that exists is kept").containsExactly("discussion:q-1");
            assertThat(topic.userSet()).isFalse();
        });

        assertThat(service.correctTopic("workspace", goal.id(), "Manifests", GoalTopic.Level.LEARNING).topics())
                .singleElement().satisfies(topic -> assertThat(topic.userSet()).isTrue());
        service.ask("workspace", goal.id(), "op-2", "And now?");
        doReturn(levels(new GoalTopic.Change("manifests", GoalTopic.Level.STRONG, ImmutableList.of("discussion:q-1")))).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(goals.list("workspace").getFirst().topics()).singleElement().satisfies(topic -> assertThat(topic.level()).isEqualTo(GoalTopic.Level.LEARNING));
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

    private static GoalExchange.Reply work(int issue)
    {
        return new GoalExchange.Reply("Practice on a real gap.", Optional.empty(), Optional.empty(), Optional.of(new GoalRecommendation.Card(GoalRecommendation.Kind.WORK,
                "Fix #" + issue, "You learned checkpoints", "Key add and remove actions by path and deletion vector.", ImmutableList.of(), Optional.empty(),
                Optional.of(issue))), Optional.empty(), ImmutableList.of());
    }

    private static GoalExchange.Reply levels(GoalTopic.Change... changes)
    {
        return new GoalExchange.Reply("You asked about manifests.", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.copyOf(changes));
    }

    private static final class Topics
            implements GoalTopicStore
    {
        private final Map<String, GoalTopic> topics = new LinkedHashMap<>();

        @Override
        public Optional<GoalTopic> find(String topicId)
        {
            return Optional.ofNullable(topics.get(topicId));
        }

        @Override
        public List<GoalTopic> list(String goalId)
        {
            return topics.values().stream().filter(topic -> topic.goalId().equals(goalId)).collect(toImmutableList());
        }

        @Override
        public void save(GoalTopic topic)
        {
            topics.put(topic.id(), topic);
        }
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
