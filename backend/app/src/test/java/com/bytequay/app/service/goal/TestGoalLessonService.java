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

import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalLesson;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.store.GoalKnowledgeNoteStore;
import com.bytequay.core.store.GoalLessonStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGoalLessonService
{
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private static final GoalLesson.Content CONTENT = new GoalLesson.Content(Optional.of("A table is a tree of metadata."),
            ImmutableList.of("A snapshot never changes once written."),
            ImmutableList.of(new GoalLesson.Section("Metadata", "Each snapshot lists its manifests.", Optional.of(new GoalLesson.Diagram(GoalLesson.Diagram.Kind.TREE,
                    "Table metadata", ImmutableList.of(new GoalLesson.Node("table", "Table", Optional.empty(), Optional.empty(), Optional.empty()),
                            new GoalLesson.Node("snapshot", "Snapshot", Optional.of("Never changes"), Optional.of("table"), Optional.empty())), ImmutableList.of())))),
            Optional.empty(), "Run one test.", "It passes.", "What is a snapshot?");
    private static final GoalLesson.Diagram MAP = new GoalLesson.Diagram(GoalLesson.Diagram.Kind.COMPONENTS, "Iceberg reads", ImmutableList.of(
            new GoalLesson.Node("scan", "TableScan", Optional.of("Plans the files"), Optional.empty(), Optional.of("Engine")),
            new GoalLesson.Node("manifest", "ManifestFile", Optional.empty(), Optional.empty(), Optional.of("Metadata"))),
            ImmutableList.of(new GoalLesson.Edge("scan", "manifest", Optional.of("reads"))));

    private final GoalService goals = mock(GoalService.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final Lessons lessons = new Lessons();
    private final InMemoryGoalRecommendationStore cards = new InMemoryGoalRecommendationStore();
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final GoalPlanner planner = mock(GoalPlanner.class);
    private final GoalConversationService conversation = mock(GoalConversationService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final List<Runnable> queued = new ArrayList<>();
    private final GoalLessonService service = new GoalLessonService(goals, work, lessons, mock(GoalKnowledgeNoteStore.class), cards, models, planner,
            beans.getBeanProvider(GoalConversationService.class), Clock.fixed(NOW, ZoneOffset.UTC), queued::add);

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("goalConversationService", conversation);
        goal(WorkItem.State.PLANNED);
        when(models.goalModel("goal-1", "workspace"))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "codex", "Codex", "gpt", "GPT", Optional.empty()));
    }

    @Test
    void aLessonIsWrittenOnceAndOpeningItStartsItsCard()
    {
        cards.save(new GoalRecommendation("card-1", "goal-1", "workspace", new GoalRecommendation.Card(GoalRecommendation.Kind.LESSON, "What a table is",
                "You are ready", "Table metadata", ImmutableList.of(), Optional.of("lesson-1"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                GoalRecommendation.State.SHOWN,
                Optional.empty(), Optional.empty(), Optional.empty(), "op-1", NOW, NOW));
        assertThat(service.open("workspace", "goal-1", "lesson-1").state()).isEqualTo(GoalLesson.State.WRITING);
        assertThat(service.open("workspace", "goal-1", "lesson-1").state()).isEqualTo(GoalLesson.State.WRITING);
        assertThat(queued).hasSize(1);
        assertThat(cards.find("card-1")).map(GoalRecommendation::state).contains(GoalRecommendation.State.STARTED);

        doReturn(CONTENT).when(planner).lesson(any(), any(), any(), eq("Iceberg tables"), any());
        queued.removeFirst().run();
        assertThat(service.find("workspace", "goal-1", "lesson-1")).hasValueSatisfying(lesson -> {
            assertThat(lesson.content()).contains(CONTENT);
            assertThat(lesson.model()).contains("codex/gpt");
        });
        service.open("workspace", "goal-1", "lesson-1");
        assertThat(queued).as("a written lesson needs no model call").isEmpty();
        assertThatThrownBy(() -> service.open("workspace", "goal-1", "not-a-lesson")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aFailedOrInterruptedLessonIsWrittenAgainWhenOpened()
    {
        service.open("workspace", "goal-1", "lesson-1");
        doThrow(new IllegalStateException("Codex is not signed in")).when(planner).lesson(any(), any(), any(), anyString(), any());
        queued.removeFirst().run();
        assertThat(lessons.find("lesson-1")).hasValueSatisfying(lesson -> assertThat(lesson.failure()).contains("Codex is not signed in"));

        service.open("workspace", "goal-1", "lesson-1");
        assertThat(queued).hasSize(1);
        lessons.save(GoalLesson.writing("lesson-2", "goal-1", "workspace", NOW));
        service.settleInterrupted();
        assertThat(lessons.find("lesson-2")).map(GoalLesson::failure).contains(Optional.of(GoalLessonService.INTERRUPTED));
        assertThat(lessons.find("lesson-1")).map(GoalLesson::state).contains(GoalLesson.State.WRITING);
    }

    @Test
    void aLessonWrittenBeforeChaptersIsWrittenAgainAsAChapterWhenOpened()
    {
        lessons.save(GoalLesson.writing("lesson-1", "goal-1", "workspace", NOW).written("codex/gpt", new GoalLesson.Content(Optional.empty(), null, null,
                Optional.of("# Tables"), "Run one test.", "It passes.", "What is a snapshot?"), NOW));
        assertThat(service.open("workspace", "goal-1", "lesson-1").state()).isEqualTo(GoalLesson.State.WRITING);
        doReturn(CONTENT).when(planner).lesson(any(), any(), any(), anyString(), any());
        queued.removeFirst().run();
        assertThat(lessons.find("lesson-1")).flatMap(GoalLesson::content).contains(CONTENT);
    }

    @Test
    void writeAgainRewritesAWrittenChapterButLeavesOneBeingWritten()
    {
        when(goals.get("workspace", "goal-1")).thenReturn(goalView(WorkItem.State.PLANNED, Optional.of(MAP)));
        assertThat(service.writeAgain("workspace", "goal-1", "lesson-1").state()).isEqualTo(GoalLesson.State.WRITING);
        assertThat(service.writeAgain("workspace", "goal-1", "lesson-1").state()).as("a chapter being written is left to finish").isEqualTo(GoalLesson.State.WRITING);
        assertThat(queued).hasSize(1);
        doReturn(CONTENT).when(planner).lesson(any(), any(), any(), anyString(), any());
        queued.removeFirst().run();
        assertThat(lessons.find("lesson-1")).map(GoalLesson::state).contains(GoalLesson.State.READY);

        service.open("workspace", "goal-1", "lesson-1");
        assertThat(queued).as("opening a written chapter writes nothing").isEmpty();
        service.writeAgain("workspace", "goal-1", "lesson-1");
        assertThat(queued).hasSize(1);
        assertThat(lessons.find("lesson-1")).map(GoalLesson::state).contains(GoalLesson.State.WRITING);
    }

    @Test
    void theFirstChapterDrawsTheCourseMapAndIsWrittenWithIt()
    {
        service.open("workspace", "goal-1", "lesson-1");
        when(goals.get("workspace", "goal-1")).thenReturn(goalView(WorkItem.State.PLANNED, Optional.empty()), goalView(WorkItem.State.PLANNED, Optional.of(MAP)));
        doReturn(MAP).when(planner).courseMap(any(), any(), any());
        doReturn(CONTENT).when(planner).lesson(any(), any(), any(), anyString(), any());
        queued.removeFirst().run();
        verify(goals).mapCourse("workspace", "goal-1", "op-0", MAP);
        verify(planner).lesson(any(), argThat(goal -> goal.course().flatMap(GoalCourse::map).isPresent()), any(), anyString(), any());
        assertThat(lessons.find("lesson-1")).flatMap(GoalLesson::content).contains(CONTENT);
    }

    @Test
    void aMappedCourseIsNotDrawnAgainAndAFailedMapStillWritesTheChapter()
    {
        when(goals.get("workspace", "goal-1")).thenReturn(goalView(WorkItem.State.PLANNED, Optional.of(MAP)));
        service.open("workspace", "goal-1", "lesson-1");
        doReturn(CONTENT).when(planner).lesson(any(), any(), any(), anyString(), any());
        queued.removeFirst().run();
        verify(planner, never()).courseMap(any(), any(), any());

        goal(WorkItem.State.PLANNED);
        lessons.save(lessons.find("lesson-1").orElseThrow().failed(Optional.empty(), "Write it again", NOW));
        service.open("workspace", "goal-1", "lesson-1");
        doThrow(new IllegalStateException("Codex is not signed in")).when(planner).courseMap(any(), any(), any());
        queued.removeFirst().run();
        verify(goals, never()).mapCourse(any(), any(), any(), any());
        assertThat(lessons.find("lesson-1")).flatMap(GoalLesson::content).contains(CONTENT);
    }

    @Test
    void interruptingTheMapDoesNotStartWritingTheLesson()
    {
        service.open("workspace", "goal-1", "lesson-1");
        doAnswer(call -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The model was interrupted");
        }).when(planner).courseMap(any(), any(), any());
        try {
            queued.removeFirst().run();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(planner, never()).lesson(any(), any(), any(), anyString(), any());
            assertThat(lessons.find("lesson-1")).map(GoalLesson::state).contains(GoalLesson.State.FAILED);
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void finishingAcceptsTheLessonCompletesItsCardAndTellsTheAgentOnce()
    {
        cards.save(new GoalRecommendation("card-1", "goal-1", "workspace", new GoalRecommendation.Card(GoalRecommendation.Kind.LESSON, "What a table is",
                "You are ready", "Table metadata", ImmutableList.of(), Optional.of("lesson-1"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                GoalRecommendation.State.STARTED,
                Optional.empty(), Optional.empty(), Optional.empty(), "op-1", NOW, NOW));
        service.finish("workspace", "goal-1", "lesson-1", "op-done");
        verify(work).change(eq("workspace"), eq("lesson-1"), any(WorkItemCommand.class));
        assertThat(cards.find("card-1")).map(GoalRecommendation::state).contains(GoalRecommendation.State.DONE);
        verify(conversation).tellDecision("workspace", "goal-1", "lesson:lesson-1", "The user finished the lesson \"What a table is\".");

        goal(WorkItem.State.DONE);
        service.finish("workspace", "goal-1", "lesson-1", "op-again");
        // A finished lesson is not accepted twice.
        verify(work).change(eq("workspace"), eq("lesson-1"), any(WorkItemCommand.class));
    }

    @Test
    void finishingTheLastLessonOfAPartSaysWhatComesNext()
    {
        WorkItemView lesson = new WorkItemView("lesson-1", "workspace", Optional.of("goal-1"), "What a table is", WorkItem.Kind.LEARN, "Explain table metadata",
                WorkItem.CompletionRule.ACCEPTED_RESULT, WorkItem.State.DONE, 2, ImmutableList.of(), ImmutableList.of(), NOW, NOW, WorkItem.Priority.NORMAL, 0,
                ImmutableList.of(), Optional.empty(), 0);
        WorkItemView next = new WorkItemView("lesson-2", "workspace", Optional.of("goal-1"), "Plan a scan", WorkItem.Kind.LEARN, "Explain splits",
                WorkItem.CompletionRule.ACCEPTED_RESULT, WorkItem.State.PLANNED, 1, ImmutableList.of(), ImmutableList.of(), NOW, NOW, WorkItem.Priority.NORMAL, 1,
                ImmutableList.of(), Optional.empty(), 0);
        when(goals.get("workspace", "goal-1")).thenReturn(new GoalView("goal-1", "workspace", "Iceberg", "", true, Optional.empty(), Optional.empty(), 1,
                Optional.of(new GoalCourse("op-0", ImmutableList.of(new GoalCourse.Part("Iceberg tables", ImmutableList.of("lesson-1")),
                        new GoalCourse.Part("The connector", ImmutableList.of("lesson-2"))), NOW, Optional.empty())), false,
                ImmutableList.of(lesson, next), ImmutableList.of(), ImmutableList.of(), NOW, NOW));
        service.finish("workspace", "goal-1", "lesson-1", "op-done");
        verify(conversation).tellDecision("workspace", "goal-1", "lesson:lesson-1",
                "The user finished the lesson \"What a table is\". That completes part 1, \"Iceberg tables\"; the next part is \"The connector\".");
    }

    private void goal(WorkItem.State state)
    {
        when(goals.get("workspace", "goal-1")).thenReturn(goalView(state, Optional.empty()));
    }

    private static GoalView goalView(WorkItem.State state, Optional<GoalLesson.Diagram> map)
    {
        WorkItemView lesson = new WorkItemView("lesson-1", "workspace", Optional.of("goal-1"), "What a table is", WorkItem.Kind.LEARN, "Explain table metadata",
                WorkItem.CompletionRule.ACCEPTED_RESULT, state, 1, ImmutableList.of(), ImmutableList.of(), NOW, NOW, WorkItem.Priority.NORMAL, 0, ImmutableList.of(),
                Optional.empty(), 0);
        return new GoalView("goal-1", "workspace", "Iceberg", "", true, Optional.empty(), Optional.empty(), 1,
                Optional.of(new GoalCourse("op-0", ImmutableList.of(new GoalCourse.Part("Iceberg tables", ImmutableList.of("lesson-1"))), NOW, map)), false,
                ImmutableList.of(lesson), ImmutableList.of(), ImmutableList.of(), NOW, NOW);
    }

    private static final class Lessons
            implements GoalLessonStore
    {
        private final Map<String, GoalLesson> lessons = new LinkedHashMap<>();

        @Override
        public Optional<GoalLesson> find(String lessonId)
        {
            return Optional.ofNullable(lessons.get(lessonId));
        }

        @Override
        public List<GoalLesson> writing()
        {
            return lessons.values().stream().filter(lesson -> lesson.state() == GoalLesson.State.WRITING).collect(toImmutableList());
        }

        @Override
        public void save(GoalLesson lesson)
        {
            lessons.put(lesson.id(), lesson);
        }
    }
}
