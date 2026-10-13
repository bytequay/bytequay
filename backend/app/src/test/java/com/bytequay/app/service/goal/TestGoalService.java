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

import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalDefinition;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalLesson;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.GoalTopicStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalService
{
    private final InMemoryGoalStore store = new InMemoryGoalStore();
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final GoalService service = new GoalService(store, workspaces, work, mock(GoalReferenceReader.class), new InMemoryGoalRecommendationStore(),
            mock(GoalTopicStore.class), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private final GoalSource source = new GoalSource(GoalItemKind.ISSUE, "org/repo#1");

    @BeforeEach
    void setUp()
    {
        when(workspaces.findById("workspace")).thenReturn(Optional.of(mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE))));
        when(work.list("workspace")).thenReturn(ImmutableList.of());
    }

    @Test
    void anAcceptedDefinitionIsRevisionCheckedAndRepeatable()
    {
        GoalView goal = service.create("workspace", "Iceberg", "");
        // Pinning leaves the revision a pending proposal was built from.
        service.update("workspace", goal.id(), Optional.empty(), Optional.empty(), true);
        GoalDefinition definition = new GoalDefinition("Explain a scan", "Knows the specification", "", "exchange-1", Instant.EPOCH);
        GoalView defined = service.define("workspace", goal.id(), 0, "Iceberg reads", "Explain reads", definition);
        assertThat(defined.title()).isEqualTo("Iceberg reads");
        assertThat(defined.description()).isEqualTo("Explain reads");
        assertThat(defined.definition()).contains(definition);
        assertThat(defined.revision()).isEqualTo(1);
        assertThat(service.define("workspace", goal.id(), 0, "Iceberg reads", "Explain reads", definition)).isEqualTo(defined);

        service.update("workspace", goal.id(), Optional.of("My own title"), Optional.empty(), false);
        GoalDefinition stale = new GoalDefinition("Explain a scan", "", "", "exchange-2", Instant.EPOCH);
        assertThatThrownBy(() -> service.define("workspace", goal.id(), 1, "Iceberg reads", "Explain reads", stale))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("The goal changed after this was proposed");
        assertThat(service.list("workspace").getFirst().title()).isEqualTo("My own title");
    }

    @Test
    void anAcceptedRevisionReplacesTheCourseWithoutChangingTheRevision()
    {
        GoalView goal = service.create("workspace", "Iceberg", "");
        GoalCourse course = new GoalCourse("exchange-1", ImmutableList.of(new GoalCourse.Part("Iceberg tables", ImmutableList.of("lesson-1"))), Instant.EPOCH, Optional.empty());
        assertThat(service.planCourse("workspace", goal.id(), course).course()).contains(course);
        assertThat(service.planCourse("workspace", goal.id(), course).course()).contains(course);
        GoalCourse revised = new GoalCourse("exchange-2", ImmutableList.of(new GoalCourse.Part("The connector", ImmutableList.of("lesson-1", "lesson-2"))), Instant.EPOCH,
                Optional.empty());
        assertThat(service.planCourse("workspace", goal.id(), revised).course()).contains(revised);
        assertThat(store.find(goal.id()).orElseThrow().revision()).isZero();
    }

    @Test
    void aMapIsSavedOnTheCourseItWasDrawnForAndOnlyOnce()
    {
        GoalView goal = service.create("workspace", "Iceberg", "");
        GoalCourse course = new GoalCourse("exchange-1", ImmutableList.of(new GoalCourse.Part("Iceberg tables", ImmutableList.of("lesson-1"))), Instant.EPOCH, Optional.empty());
        service.planCourse("workspace", goal.id(), course);
        GoalLesson.Diagram map = new GoalLesson.Diagram(GoalLesson.Diagram.Kind.COMPONENTS, "Iceberg reads", ImmutableList.of(
                new GoalLesson.Node("scan", "TableScan", Optional.of("Plans the files"), Optional.empty(), Optional.of("Engine")),
                new GoalLesson.Node("manifest", "ManifestFile", Optional.empty(), Optional.empty(), Optional.of("Metadata"))),
                ImmutableList.of(new GoalLesson.Edge("scan", "manifest", Optional.of("reads"))));
        service.mapCourse("workspace", goal.id(), "exchange-0", map);
        assertThat(store.find(goal.id()).orElseThrow().course().orElseThrow().map()).as("drawn for a course revised since").isEmpty();

        service.mapCourse("workspace", goal.id(), "exchange-1", map);
        assertThat(store.find(goal.id()).orElseThrow().course()).contains(course.withMap(map));
        GoalLesson.Diagram other = new GoalLesson.Diagram(GoalLesson.Diagram.Kind.COMPONENTS, "Other", map.nodes(), map.edges());
        service.mapCourse("workspace", goal.id(), "exchange-1", other);
        assertThat(store.find(goal.id()).orElseThrow().course().orElseThrow().map()).as("a mapped course keeps its map").contains(map);
        assertThatThrownBy(() -> course.withMap(new GoalLesson.Diagram(GoalLesson.Diagram.Kind.FLOW, "Steps", map.nodes(), ImmutableList.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a course map is a components diagram");
    }

    @Test
    void sourceLinkedGoalsCanBeArchivedIndependentlyAndRetainTheirHistory()
    {
        GoalView created = service.create("workspace", "Objective", "");
        Goal saved = store.find(created.id()).orElseThrow();
        store.save(new Goal(saved.id(), saved.workspaceId(), saved.title(), saved.description(), saved.pinned(), false, Optional.of(source),
                saved.definition(), saved.revision(), saved.course(), saved.paused(), saved.createdAt(), saved.updatedAt()));
        assertThat(service.list("workspace")).singleElement().satisfies(goal -> assertThat(goal.source()).contains(source));
        service.delete("workspace", saved.id());
        assertThat(service.list("workspace")).isEmpty();
        assertThat(store.find(saved.id())).hasValueSatisfying(goal -> {
            assertThat(goal.archived()).isTrue();
            assertThat(goal.source()).contains(source);
        });
    }
}
