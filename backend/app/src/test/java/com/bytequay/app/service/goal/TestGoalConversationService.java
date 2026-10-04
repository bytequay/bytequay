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
import com.bytequay.app.context.ContextSpecModule;
import com.bytequay.app.jira.JiraIssue;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.GoalTopic;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.GoalExchangeStore;
import com.bytequay.core.store.GoalTopicStore;
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
import java.util.Arrays;
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
            Optional.empty(), ImmutableList.of(), Optional.empty());
    private static final GoalExchange.Reply RESEARCH = new GoalExchange.Reply("Read the specification before the connector.", Optional.empty(), Optional.empty(),
            Optional.of(new GoalRecommendation.Card(GoalRecommendation.Kind.RESEARCH, "Read the Iceberg specification", "You start from the specification",
                    "Table metadata, snapshots and manifests", ImmutableList.of("https://iceberg.apache.org/spec/"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())), Optional.empty(), ImmutableList.of(), Optional.empty());
    private static final GoalExchange.Reply PROPOSAL = new GoalExchange.Reply("I understand the goal.", Optional.empty(),
            Optional.of(new GoalExchange.Proposal("Iceberg reads", "Explain how reads plan files", "Trace a scan end to end", "Knows the specification", "Five hours a week")),
            Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.empty());
    private static final GoalExchange.Reply COURSE = new GoalExchange.Reply("Here is a course from the notes.", Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.of(new GoalCourse.Draft(ImmutableList.of(
                    new GoalCourse.DraftPart("Iceberg tables", ImmutableList.of(new GoalCourse.DraftLesson("What a table is", "Explain metadata, snapshots and manifests"),
                            new GoalCourse.DraftLesson("Follow a snapshot", "Trace a snapshot to its data files"))),
                    new GoalCourse.DraftPart("The Trino connector", ImmutableList.of(new GoalCourse.DraftLesson("Plan a scan", "Explain how splits are planned")))))),
            ImmutableList.of(), Optional.empty());

    private final InMemoryGoalStore goalStore = new InMemoryGoalStore();
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final InMemoryGoalRecommendationStore cards = new InMemoryGoalRecommendationStore();
    private final GoalCandidates candidates = mock(GoalCandidates.class);
    private final GoalResearchService research = mock(GoalResearchService.class);
    private final GoalSpecChecks specChecks = mock(GoalSpecChecks.class);
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
    private final GoalConversationService service = new GoalConversationService(goals, work, exchanges, cards, workspaces, candidates,
            beans.getBeanProvider(GoalResearchService.class), beans.getBeanProvider(GoalSpecChecks.class), questions, topics, models, planner, clock, queued::add);

    @BeforeEach
    void setUp()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.monitoredRepositoryId()).thenReturn(1L);
        when(workspace.monitoredRepository()).thenReturn(RepoRef.of("trinodb", "trino"));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        when(candidates.pool(any())).thenReturn(pool());
        beans.registerSingleton("goalResearchService", research);
        beans.registerSingleton("goalSpecChecks", specChecks);
        when(work.list("workspace")).thenReturn(ImmutableList.of());
        when(models.goalModel(any(), eq("workspace")))
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
                GoalRecommendation.Kind.LESSON, "What a table is", "You are ready", "Table metadata", ImmutableList.of(), Optional.of("not-a-lesson"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty())),
                Optional.empty(), ImmutableList.of(), Optional.empty());
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
        assertThat(cards.list(goal.id())).as("an issue the agent was not offered as a candidate is dropped").isEmpty();

        when(candidates.pool(any())).thenReturn(pool("#31252"));
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

    @Test
    void aPracticeCardWhoseSourceIsNoLongerOpenIsSetAsideAndTheAgentAskedForAnother()
    {
        GoalView goal = goals.create("workspace", "Delta Lake", "");
        service.ask("workspace", goal.id(), "op-1", "Reads first");
        doReturn(PROPOSAL).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        service.accept("workspace", goal.id(), "op-1", Optional.empty());
        when(candidates.pool(any())).thenReturn(pool("#31252"));
        when(candidates.unready(any(), any())).thenReturn(Optional.of("Issue #31252 already has pull request #31260 linked to close it, open."));
        doReturn(work(31252)).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();

        assertThat(cards.list(goal.id())).singleElement().as("set aside, so the candidates leave it out").extracting(GoalRecommendation::state)
                .isEqualTo(GoalRecommendation.State.DISMISSED);
        assertThat(exchanges.list(goal.id()).getLast()).satisfies(told -> {
            assertThat(told.automatic()).isTrue();
            assertThat(told.state()).isEqualTo(GoalExchange.State.PENDING);
            assertThat(told.message()).isEqualTo("Issue #31252 already has pull request #31260 linked to close it, open. Recommend another candidate.");
        });
    }

    @Test
    void aWorkCardNamingAMappedTicketKeepsItsReferenceAndStartAdoptsTheTicket()
    {
        GoalView goal = goals.create("workspace", "Delta Lake", "");
        service.ask("workspace", goal.id(), "op-1", "Reads first");
        doReturn(PROPOSAL).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        service.accept("workspace", goal.id(), "op-1", Optional.empty());
        JiraIssue ticket = mock(JiraIssue.class, withSettings().mockMaker(MockMakers.INLINE));
        when(ticket.connectionId()).thenReturn("connection");
        when(ticket.cloudId()).thenReturn("cloud");
        when(ticket.id()).thenReturn("10412");
        when(candidates.pool(any())).thenReturn(pool("TRINO-12"));
        when(candidates.ticket("workspace", "TRINO-12")).thenReturn(Optional.of(ticket));
        doReturn(new GoalExchange.Reply("Practice on the ticket.", Optional.empty(), Optional.empty(), Optional.of(new GoalRecommendation.Card(GoalRecommendation.Kind.WORK,
                "Fix TRINO-12", "You learned checkpoints", "Keep rows across checkpoints.", ImmutableList.of(), Optional.empty(), Optional.empty(),
                Optional.of("TRINO-12"), Optional.empty(), Optional.empty())),
                Optional.empty(), ImmutableList.of(), Optional.empty())).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        GoalRecommendation card = cards.list(goal.id()).getFirst();
        assertThat(card.card().ticket()).as("the ticket is kept by its stable reference").contains("connection:cloud:10412");

        when(work.adopt(eq("workspace"), eq(goal.id()), any(), eq("Fix TRINO-12"), eq("Keep rows across checkpoints."), eq(GoalItemKind.JIRA),
                eq("connection:cloud:10412"))).thenReturn(new WorkItemView("work-2", "workspace", Optional.of(goal.id()), "Fix TRINO-12", WorkItem.Kind.DEVELOP,
                "Keep rows across checkpoints.", WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(),
                Instant.EPOCH, Instant.EPOCH));
        assertThat(service.startCard("workspace", goal.id(), card.id()).workItemId()).contains("work-2");
    }

    @Test
    void stoppingASlowReplyCancelsItsTurnAndLeavesTheMessageToAskAgain()
    {
        GoalView goal = goals.create("workspace", "Delta Lake", "");
        service.ask("workspace", goal.id(), "op-1", "Reads first");
        assertThat(service.stop("workspace", goal.id(), "op-1")).singleElement().satisfies(stopped -> {
            assertThat(stopped.state()).isEqualTo(GoalExchange.State.FAILED);
            assertThat(stopped.failure()).contains(GoalConversationService.STOPPED);
        });
        verify(planner).cancel("op-1");
        doReturn(QUESTION).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(exchanges.list(goal.id()).getFirst().state()).as("the stopped turn's late answer is not saved").isEqualTo(GoalExchange.State.FAILED);

        service.stop("workspace", goal.id(), "op-1");
        verify(planner, times(1)).cancel("op-1");
        service.retry("workspace", goal.id(), "op-1");
        queued.removeFirst().run();
        assertThat(exchanges.list(goal.id()).getFirst().reply()).as("Ask again sends it once more").contains(QUESTION);
    }

    @Test
    void aPausedGoalHearsNothingAutomaticButTheUserCanStillAskForAnUpdate()
    {
        GoalView goal = goals.create("workspace", "Delta Lake", "");
        goals.pause("workspace", goal.id(), true);
        service.tell("workspace", goal.id(), "turn:1", "The task's turn finished.");
        assertThat(exchanges.list(goal.id())).as("a paused goal is told nothing").isEmpty();

        service.reassess("workspace", goal.id(), "op-1");
        assertThat(service.reassess("workspace", goal.id(), "op-1")).singleElement().satisfies(update -> {
            assertThat(update.automatic()).isTrue();
            assertThat(update.message()).isEqualTo(GoalConversationService.REASSESS);
        });
        assertThat(queued).as("Update now asks the agent once").hasSize(1);
        assertThatThrownBy(() -> service.reassess("workspace", goal.id(), "op-2")).hasMessageContaining("still answering");

        doReturn(QUESTION).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        goals.pause("workspace", goal.id(), false);
        service.tell("workspace", goal.id(), "turn:2", "The task's turn finished.");
        assertThat(exchanges.list(goal.id())).as("a resumed goal is told again").hasSize(2);
    }

    @Test
    void theAgentNamesAGoalItsFirstWordsStartedButNeverOverANameTheUserChose()
    {
        GoalView started = service.start("workspace", "op-1", "I want to become the Trino engine execution and scheduler master");
        assertThat(started.title()).isEqualTo("I want to become the Trino engine execution and scheduler master");
        doReturn(named("Master Trino's execution engine")).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(title(started.id())).isEqualTo("Master Trino's execution engine");
        assertThat(goals.list("workspace").getFirst().revision()).as("a name changes nothing the goal means").isEqualTo(started.revision());

        service.ask("workspace", started.id(), "op-2", "Mostly the scheduler");
        doReturn(named("Master Trino's scheduler")).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(title(started.id())).as("the agent may rename its own name").isEqualTo("Master Trino's scheduler");

        goals.update("workspace", started.id(), Optional.of("Scheduler deep dive"), Optional.empty(), false);
        service.ask("workspace", started.id(), "op-3", "And split assignment");
        doReturn(named("Master Trino's split scheduling")).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(title(started.id())).as("a name the user chose stays").isEqualTo("Scheduler deep dive");
    }

    private String title(String goalId)
    {
        return goals.list("workspace").stream().filter(goal -> goal.id().equals(goalId)).findFirst().orElseThrow().title();
    }

    private static GoalExchange.Reply named(String title)
    {
        return new GoalExchange.Reply("Noted.", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.of(title));
    }

    @Test
    void aSpecCheckCardNamesAModuleTheAgentWasGivenAndStartRunsTheCheck()
    {
        GoalView goal = goals.create("workspace", "Delta Lake", "");
        service.ask("workspace", goal.id(), "op-1", "Correctness");
        doReturn(PROPOSAL).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        service.accept("workspace", goal.id(), "op-1", Optional.empty());
        when(specChecks.modules("workspace")).thenReturn(ImmutableList.of(new ContextSpecModule("plugin/trino-delta-lake/", Optional.empty(), 42, 7)));
        doReturn(check("plugin/trino-hive/")).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(cards.list(goal.id())).as("a module the agent was not given is dropped").isEmpty();

        GoalGap open = new GoalGap("check-0", "c-1", "plugin/trino-delta-lake/", "Checkpoint keys actions by path only", "", "", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        when(specChecks.gaps("workspace", goal.id())).thenReturn(ImmutableList.of(open));
        service.ask("workspace", goal.id(), "op-2", "Find more gaps");
        doReturn(check("plugin/trino-delta-lake/")).when(planner).answer(any(), any(), anyList(), any());
        queued.removeFirst().run();
        assertThat(cards.list(goal.id())).as("a gap of the last check is still open").isEmpty();

        when(specChecks.gaps("workspace", goal.id())).thenReturn(ImmutableList.of());
        service.ask("workspace", goal.id(), "op-3", "Find gaps");
        queued.removeFirst().run();
        GoalRecommendation card = cards.list(goal.id()).getFirst();
        assertThat(card.card().module()).contains("plugin/trino-delta-lake/");
        GoalRecommendation checking = card.checking("check-1", Instant.EPOCH);
        when(specChecks.start("workspace", goal.id(), card.id())).thenReturn(checking);
        assertThat(service.startCard("workspace", goal.id(), card.id())).isEqualTo(checking);
    }

    private static GoalExchange.Reply check(String module)
    {
        return new GoalExchange.Reply("Check the checkpoint writer.", Optional.empty(), Optional.empty(), Optional.of(new GoalRecommendation.Card(
                GoalRecommendation.Kind.SPEC_CHECK, "Check the Delta checkpoint writer", "You know how checkpoints reconcile actions", "Action reconciliation",
                ImmutableList.of("https://github.com/delta-io/delta/blob/master/PROTOCOL.md"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(module),
                Optional.of(3))), Optional.empty(), ImmutableList.of(), Optional.empty());
    }

    private static GoalCandidates.Pool pool(String... ids)
    {
        return new GoalCandidates.Pool(Arrays.stream(ids).map(id -> new GoalCandidates.Candidate(id, "Rows lost", ImmutableList.of(), Optional.empty(),
                Optional.empty(), Instant.EPOCH)).collect(toImmutableList()), new GoalCandidates.Coverage(ids.length, ids.length, Optional.empty(), 0));
    }

    private static GoalExchange.Reply work(int issue)
    {
        return new GoalExchange.Reply("Practice on a real gap.", Optional.empty(), Optional.empty(), Optional.of(new GoalRecommendation.Card(GoalRecommendation.Kind.WORK,
                "Fix #" + issue, "You learned checkpoints", "Key add and remove actions by path and deletion vector.", ImmutableList.of(), Optional.empty(),
                Optional.of(issue), Optional.empty(), Optional.empty(), Optional.empty())), Optional.empty(), ImmutableList.of(), Optional.empty());
    }

    private static GoalExchange.Reply levels(GoalTopic.Change... changes)
    {
        return new GoalExchange.Reply("You asked about manifests.", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.copyOf(changes), Optional.empty());
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
