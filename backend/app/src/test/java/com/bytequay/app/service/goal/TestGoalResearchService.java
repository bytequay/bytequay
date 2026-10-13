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

import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.context.ContextBuild;
import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.memory.MemoryService;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.GoalKnowledgeNote;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.store.GoalKnowledgeNoteStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalResearchService
{
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private static final String HEAD = "a".repeat(40);
    private static final String ICEBERG = "c".repeat(40);
    private static final GoalRecommendation CARD = new GoalRecommendation("card-1", "goal-1", "workspace", new GoalRecommendation.Card(GoalRecommendation.Kind.RESEARCH,
            "Read the Iceberg specification", "You start from the specification", "Snapshots and manifests", ImmutableList.of("https://iceberg.apache.org/spec/"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            GoalRecommendation.State.SHOWN, Optional.empty(), Optional.empty(), Optional.empty(), "op-1", NOW, NOW);

    private final GoalService goals = mock(GoalService.class);
    private final InMemoryGoalRecommendationStore cards = new InMemoryGoalRecommendationStore();
    private final GoalKnowledgeNoteStore notes = mock(GoalKnowledgeNoteStore.class);
    private final ContextBuildService builds = mock(ContextBuildService.class);
    private final WorkspaceRelationships relationships = mock(WorkspaceRelationships.class);
    private final WorkspaceRepositoryService repositories = mock(WorkspaceRepositoryService.class);
    private final GitCommandRunner git = mock(GitCommandRunner.class);
    private final GoalConversationService conversation = mock(GoalConversationService.class);
    private final MemoryService memories = mock(MemoryService.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final GoalResearchService service = new GoalResearchService(goals, cards, notes, builds, relationships, repositories, git,
            beans.getBeanProvider(GoalConversationService.class), memories, workspaces, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("goalConversationService", conversation);
        when(goals.list("workspace")).thenReturn(ImmutableList.of(new GoalView("goal-1", "workspace", "Iceberg connector expert", "Explain the connector", true,
                Optional.empty(), Optional.empty(), 1, Optional.empty(), false, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), NOW, NOW)));
        when(builds.baseline("workspace")).thenReturn(Optional.of(new ContextBuildService.Baseline("main", HEAD)));
        when(builds.get("workspace")).thenReturn(new ContextBuildService.View(3, true, Optional.empty()));
        cards.save(CARD);
    }

    @Test
    void startsTheCardsRunWithItsDependencyCheckoutsOnce()
    {
        when(relationships.list("workspace")).thenReturn(ImmutableList.of(new WorkspaceRelationships.Relationship("iceberg", WorkspaceRelationships.Kind.DEPENDENCY, "", false),
                new WorkspaceRelationships.Relationship("fork", WorkspaceRelationships.Kind.FORK, "", false)));
        Workspace iceberg = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(iceberg.id()).thenReturn("iceberg");
        when(iceberg.monitoredRepository()).thenReturn(RepoRef.of("apache", "iceberg"));
        when(relationships.requireRelated("workspace", "iceberg")).thenReturn(iceberg);
        when(repositories.requireLocalRepository("iceberg")).thenReturn(new WorkspaceRepositoryContext("iceberg", Path.of("/tmp/iceberg"), 1, RepoRef.of("apache", "iceberg"),
                OptionalLong.empty(), RepoRef.of("apache", "iceberg"), "origin", "origin", "main", ImmutableList.of()));
        doReturn(new GitCommandRunner.Result(0, ICEBERG + "\n", false)).when(git).run(eq(Path.of("/tmp/iceberg")), any(String[].class));
        when(builds.research(eq("workspace"), any(), eq(HEAD), any())).thenReturn(view(ContextBuildService.Outcome.RUNNING, Optional.empty()));

        GoalRecommendation started = service.start("workspace", "goal-1", "card-1");
        assertThat(started.state()).isEqualTo(GoalRecommendation.State.STARTED);
        assertThat(started.taskId()).contains("task-1");
        assertThat(service.start("workspace", "goal-1", "card-1")).isEqualTo(started);

        ArgumentCaptor<ContextBuildService.Request> request = ArgumentCaptor.forClass(ContextBuildService.Request.class);
        ArgumentCaptor<ContextBuild.Research> research = ArgumentCaptor.forClass(ContextBuild.Research.class);
        verify(builds, times(1)).research(eq("workspace"), request.capture(), eq(HEAD), research.capture());
        assertThat(request.getValue().expectedBuildSequence()).isEqualTo(3);
        assertThat(research.getValue().goalTitle()).isEqualTo("Iceberg connector expert");
        assertThat(research.getValue().pages()).containsExactly("https://iceberg.apache.org/spec/");
        assertThat(research.getValue().related()).containsExactly(new ContextBuild.Related("apache/iceberg", "/tmp/iceberg", ICEBERG));
    }

    @Test
    void aRunThatCannotStartLeavesTheCardShown()
    {
        when(builds.research(eq("workspace"), any(), eq(HEAD), any())).thenReturn(view(ContextBuildService.Outcome.FAILED, Optional.of("No worktree")));
        assertThatThrownBy(() -> service.start("workspace", "goal-1", "card-1")).hasMessage("The research run could not start: No worktree");
        assertThat(cards.find("card-1")).map(GoalRecommendation::state).contains(GoalRecommendation.State.SHOWN);
        cards.save(CARD.dismissed(NOW));
        assertThatThrownBy(() -> service.start("workspace", "goal-1", "card-1")).hasMessageContaining("already finished or dismissed");
    }

    @Test
    void aFinishedRunCompletesItsCardAndTellsTheAgentWhatItSaved()
    {
        cards.save(CARD.started(Optional.of("task-1"), NOW));
        when(notes.list("goal-1")).thenReturn(ImmutableList.of(note("Manifest lists", "task-1"), note("Snapshots", "task-1"), note("Older", "task-0")));
        service.finish(build("task-1"), true);
        assertThat(cards.find("card-1")).map(GoalRecommendation::state).contains(GoalRecommendation.State.DONE);
        verify(conversation).tell("workspace", "goal-1", "research:task-1", "The research run saved 2 notes: Manifest lists; Snapshots.");
    }

    @Test
    void aFailedRunShowsItsCardAgain()
    {
        cards.save(CARD.started(Optional.of("task-1"), NOW));
        service.finish(build("task-1"), false);
        assertThat(cards.find("card-1")).hasValueSatisfying(card -> {
            assertThat(card.state()).isEqualTo(GoalRecommendation.State.SHOWN);
            assertThat(card.taskId()).isEmpty();
        });
        verifyNoInteractions(conversation);
    }

    private static ContextBuildService.View view(ContextBuildService.Outcome outcome, Optional<String> detail)
    {
        return new ContextBuildService.View(4, false, Optional.of(new ContextBuildService.BuildView("RESEARCH", "research", "task-1", "card-1", Optional.empty(),
                Optional.empty(), 0, outcome, detail, ImmutableList.of())));
    }

    @Test
    void aNoteIsStaleOnceAFileItCitesChangedAtTheMonitoredHead()
    {
        String read = "b".repeat(40);
        when(relationships.list("workspace")).thenReturn(ImmutableList.of());
        when(repositories.requireLocalRepository("workspace")).thenReturn(new WorkspaceRepositoryContext("workspace", Path.of("/tmp/trino"), 1, RepoRef.of("trinodb", "trino"),
                OptionalLong.empty(), RepoRef.of("trinodb", "trino"), "origin", "origin", "main", ImmutableList.of()));
        doReturn(new GitCommandRunner.Result(0, HEAD + "\n", false)).when(git).run(eq(Path.of("/tmp/trino")), any(String[].class));
        doReturn(new GitCommandRunner.Result(0, "plugin/trino-delta-lake/Checkpoint.java\n", false)).when(git).run(eq(Path.of("/tmp/trino")), anyList());
        GoalKnowledgeNote moved = file("note-moved", "trinodb/trino:plugin/trino-delta-lake/Checkpoint.java", read);
        GoalKnowledgeNote kept = file("note-kept", "trinodb/trino:plugin/trino-delta-lake/Scan.java", read);
        GoalKnowledgeNote current = file("note-current", "trinodb/trino:plugin/trino-delta-lake/Checkpoint.java", HEAD);
        GoalKnowledgeNote elsewhere = file("note-elsewhere", "apache/iceberg:core/Snapshot.java", read);

        assertThat(service.stale("workspace", ImmutableList.of(moved, kept, current, elsewhere, note("Manifest lists", "task-1")))).containsExactly("note-moved");
        assertThat(service.stale("workspace", ImmutableList.of(moved, kept))).containsExactly("note-moved");
        verify(git, times(1)).run(eq(Path.of("/tmp/trino")), eq(ImmutableList.of("diff", "--name-only", "--no-renames", read, HEAD, "--",
                "plugin/trino-delta-lake/Checkpoint.java", "plugin/trino-delta-lake/Scan.java")));
    }

    @Test
    void rememberKeepsANoteCitingASpecificationPageAsTheUsersSpecMemory()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.monitoredRepository()).thenReturn(RepoRef.of("trinodb", "trino"));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        List<GoalKnowledgeNote.Source> cited = ImmutableList.of(new GoalKnowledgeNote.Source("https://iceberg.apache.org/spec/#manifest-lists", Optional.empty()),
                new GoalKnowledgeNote.Source("trinodb/trino:plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergSplitSource.java", Optional.of(HEAD)),
                new GoalKnowledgeNote.Source("apache/iceberg:core/src/main/java/org/apache/iceberg/ManifestLists.java", Optional.of(ICEBERG)));
        GoalKnowledgeNote spec = new GoalKnowledgeNote("note-1", "goal-1", "workspace", "Manifest lists", "A snapshot lists its manifests in one manifest list. "
                + "x".repeat(700), cited, "task-1", NOW, Optional.empty());
        GoalKnowledgeNote code = file("note-2", "trinodb/trino:plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java", HEAD);
        when(notes.list("goal-1")).thenReturn(ImmutableList.of(spec, code));
        Memory memory = mock(Memory.class, withSettings().mockMaker(MockMakers.INLINE));
        when(memory.id()).thenReturn("m7");
        when(memories.create(any(), any(), any(), any(), anyList(), anyList(), any())).thenReturn(memory);

        assertThat(service.remember("workspace", "goal-1", "note-1").memoryId()).contains("m7");
        // The text keeps whole sentences within a memory's size, the scope this repository's cited files, the sources the pages.
        verify(memories).create(eq("workspace"), eq(Memory.Kind.SPEC), eq("Manifest lists"), eq("A snapshot lists its manifests in one manifest list."),
                eq(ImmutableList.of("plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergSplitSource.java")),
                argThat(sources -> sources.size() == 1 && sources.getFirst().url().equals(Optional.of("https://iceberg.apache.org/spec/#manifest-lists"))),
                argThat(origin -> origin.user() && origin.taskId().isEmpty()));
        verify(notes).save(spec.remembered("m7"));

        // Asked again while its memory is kept, nothing changes; a note citing only code is refused.
        when(notes.list("goal-1")).thenReturn(ImmutableList.of(spec.remembered("m7"), code));
        when(memories.list("workspace")).thenReturn(ImmutableList.of(memory));
        assertThat(service.remember("workspace", "goal-1", "note-1").memoryId()).contains("m7");
        assertThat(service.notes("workspace", "goal-1")).extracting(GoalKnowledgeNoteView::memoryId).containsExactly(Optional.of("m7"), Optional.empty());
        verify(memories, times(1)).create(any(), any(), any(), any(), anyList(), anyList(), any());
        assertThatThrownBy(() -> service.remember("workspace", "goal-1", "note-2")).hasMessageContaining("cites a specification page");
    }

    private static GoalKnowledgeNote file(String id, String reference, String commit)
    {
        return new GoalKnowledgeNote(id, "goal-1", "workspace", id, "Body", ImmutableList.of(new GoalKnowledgeNote.Source(reference, Optional.of(commit))), "task-1", NOW,
                Optional.empty());
    }

    private static GoalKnowledgeNote note(String topic, String task)
    {
        return new GoalKnowledgeNote("note-" + topic, "goal-1", "workspace", topic, "Body", ImmutableList.of(new GoalKnowledgeNote.Source("https://iceberg.apache.org/spec/",
                Optional.empty())), task, NOW, Optional.empty());
    }

    private static ContextBuild build(String task)
    {
        return new ContextBuild(4, "research", "sha256:" + "b".repeat(64), "workspace", task, new RepositoryIdentity("trinodb", "trino"), 1, RepoRef.of("trinodb", "trino"),
                HEAD, "bytequay/research", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW, ContextBuild.Capture.empty(), Optional.empty(),
                Optional.empty(), Optional.of(new ContextBuild.Research("card-1", "goal-1", "Iceberg connector expert", "", "Snapshots", ImmutableList.of(), ImmutableList.of())));
    }
}
