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

import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestContextLearningService
{
    private static final String WORKSPACE = "workspace";
    private static final String REPORT = "11111111-1111-4111-8111-111111111111";
    private static final String SHA = "a".repeat(40);
    private static final RepoRef REPO = RepoRef.of("owner", "repository");
    private static final Path LOCAL = Path.of("/tmp/repository");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final ContextDiscoveryReportStore reports = new ContextDiscoveryReportStore(settings, mapper);
    private final ContextLearningPlanStore plans = new ContextLearningPlanStore(settings, mapper);
    private final ContextBuildStore buildStore = new ContextBuildStore(settings, mock(AgentTaskStore.class), mapper);
    private final ContextBuildService builds = mock(ContextBuildService.class);
    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final GitCommandRunner git = mock(GitCommandRunner.class);
    private final AgentUnitOfWork unitOfWork = mock(AgentUnitOfWork.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final AtomicReference<RepositoryNotebook> notebook = new AtomicReference<>(RepositoryNotebook.empty());
    private final AtomicReference<ContextBuildService.View> view = new AtomicReference<>(view(0));
    private ContextLearningService learning;

    @BeforeEach
    void setup()
    {
        when(notebooks.getNotebook(WORKSPACE)).thenAnswer(ignored -> notebook.get());
        when(workspaces.requireReadableRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(
                WORKSPACE, LOCAL, 1, REPO, OptionalLong.of(1), REPO, "origin", "origin", "main", ImmutableList.of()));
        when(git.run(LOCAL, "rev-parse", "--verify", SHA + "^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, SHA + "\n", false));
        when(builds.get(WORKSPACE)).thenAnswer(ignored -> view.get());
        when(builds.learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any())).thenAnswer(ignored -> view.get());
        when(unitOfWork.execute(any())).thenAnswer(call -> ((AgentUnitOfWork.Work<?>) call.getArgument(0)).run());
        when(notebooks.registerDiscovered(eq(WORKSPACE), anyLong(), anyList(), anyList(), anyList())).thenAnswer(call -> {
            RepositoryNotebook current = notebook.get();
            List<ContextScope> scopes = call.getArgument(2);
            List<RegisteredContextSource> sources = call.getArgument(3);
            List<ContextSourceEvidence> evidence = call.getArgument(4);
            RepositoryNotebook updated = new RepositoryNotebook(1, current.revision() + 1, current.useInReviews(), current.useInDevelopment(),
                    ImmutableList.<RegisteredContextSource>builder().addAll(current.sources()).addAll(sources).build(),
                    ImmutableList.<ContextScope>builder().addAll(current.scopes()).addAll(scopes).build(), current.entries(),
                    ImmutableList.<ContextSourceEvidence>builder().addAll(current.sourceEvidence()).addAll(evidence).build(), Optional.empty());
            notebook.set(updated);
            return updated;
        });
        learning = new ContextLearningService(reports, plans, buildStore, builds, notebooks, workspaces, git, unitOfWork, mapper, clock);
    }

    @Test
    void acceptsSelectedTopicsOnceAndContinuesAcrossTopicsBeforeLaterQuestions()
    {
        reports.save(Optional.empty(), report(ImmutableList.of(
                topic("topic:1", "Project structure", ImmutableList.of(), ImmutableList.of()),
                topic("topic:2", "Build requirements", ImmutableList.of(), ImmutableList.of())), ImmutableList.of()));
        ContextLearningService.StartRequest request = new ContextLearningService.StartRequest(
                "accept", 0, 0, REPORT, ImmutableList.of("topic:1", "topic:2"), ImmutableList.of());

        learning.start(WORKSPACE, request);

        ContextLearningPlan plan = plans.find(WORKSPACE).orElseThrow();
        assertThat(plan.topics()).extracting(ContextLearningPlan.Topic::scopeId).containsExactly("project", "project");
        assertThat(plan.topics().getFirst().repositoryFiles()).containsExactly("README.md");
        assertThat(plan.topics().getFirst().questions()).extracting(ContextLearningPlan.Question::status)
                .containsExactly(ContextLearningPlan.Status.PENDING, ContextLearningPlan.Status.PENDING);
        ArgumentCaptor<ContextBuild.Assignment> first = ArgumentCaptor.forClass(ContextBuild.Assignment.class);
        verify(builds).learn(eq(WORKSPACE), any(), eq(SHA), eq(ImmutableList.of()), first.capture());
        assertThat(first.getValue().topicId()).isEqualTo("topic:1");
        assertThat(first.getValue().questionIndex()).isZero();
        assertThat(first.getValue().progress()).isEmpty();
        ContextBuild bound = task(plan, "topic:1", 0);
        buildStore.save(Optional.empty(), bound);
        learning.start(WORKSPACE, request);
        verify(builds, times(1)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any());

        learning.progress(bound, "Structure inspected.", true, ImmutableList.of("README was partial"));
        ContextLearningPlan answered = plans.find(WORKSPACE).orElseThrow();
        assertThat(answered.topics().getFirst().questions().getFirst().status()).isEqualTo(ContextLearningPlan.Status.COMPLETED);
        assertThat(answered.next()).contains(new ContextLearningPlan.Target("topic:2", 0));
        view.set(view(1));
        learning.continueLearning(WORKSPACE, new ContextLearningService.ContinueRequest("continue", 0, 1, plan.id()));
        ArgumentCaptor<ContextBuild.Assignment> assignments = ArgumentCaptor.forClass(ContextBuild.Assignment.class);
        verify(builds, times(2)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), assignments.capture());
        assertThat(assignments.getAllValues().getLast().topicId()).isEqualTo("topic:2");
        assertThat(plans.find(WORKSPACE).orElseThrow().gaps()).contains("README was partial");
    }

    @Test
    void resumesAnUnfinishedQuestionWithItsSavedProgress()
    {
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Project structure", ImmutableList.of(), ImmutableList.of())), ImmutableList.of()));
        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of()));
        ContextLearningPlan plan = plans.find(WORKSPACE).orElseThrow();
        ContextBuild bound = task(plan, "topic:1", 0);
        buildStore.save(Optional.empty(), bound);

        learning.progress(bound, "Modules are listed in the root build; read the test layout next.", false, ImmutableList.of());

        ContextLearningPlan saved = plans.find(WORKSPACE).orElseThrow();
        assertThat(saved.topics().getFirst().questions().getFirst().status()).isEqualTo(ContextLearningPlan.Status.UNFINISHED);
        assertThat(saved.next()).contains(new ContextLearningPlan.Target("topic:1", 0));
        view.set(view(1));
        learning.continueLearning(WORKSPACE, new ContextLearningService.ContinueRequest("continue", 0, 1, plan.id()));
        ArgumentCaptor<ContextBuild.Assignment> assignments = ArgumentCaptor.forClass(ContextBuild.Assignment.class);
        verify(builds, times(2)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), assignments.capture());
        assertThat(assignments.getAllValues().getLast().questionIndex()).isZero();
        assertThat(assignments.getAllValues().getLast().progress()).startsWith("Modules are listed");
    }

    @Test
    void addsTopicsFromTheSameReportToThePlanAndStartsOnTheFirstNewQuestion()
    {
        reports.save(Optional.empty(), report(ImmutableList.of(
                topic("topic:1", "Project structure", ImmutableList.of(), ImmutableList.of()),
                topic("topic:2", "Build requirements", ImmutableList.of(), ImmutableList.of())), ImmutableList.of()));
        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of()));
        ContextLearningPlan first = plans.find(WORKSPACE).orElseThrow();
        buildStore.save(Optional.empty(), task(first, "topic:1", 0));
        view.set(view(1));

        learning.start(WORKSPACE, new ContextLearningService.StartRequest("add", 0, 1, REPORT, ImmutableList.of("topic:2"), ImmutableList.of()));

        ContextLearningPlan extended = plans.find(WORKSPACE).orElseThrow();
        assertThat(extended.id()).isEqualTo(first.id());
        assertThat(extended.topics()).extracting(ContextLearningPlan.Topic::id).containsExactly("topic:1", "topic:2");
        assertThat(extended.acceptedTopicIds()).containsExactly("topic:2");
        ArgumentCaptor<ContextBuild.Assignment> assignments = ArgumentCaptor.forClass(ContextBuild.Assignment.class);
        verify(builds, times(2)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), assignments.capture());
        assertThat(assignments.getAllValues().getLast().topicId()).isEqualTo("topic:2");
        assertThatIllegalArgumentException().isThrownBy(() -> learning.start(WORKSPACE,
                new ContextLearningService.StartRequest("again", 0, 1, REPORT, ImmutableList.of("topic:2"), ImmutableList.of())))
                .withMessageContaining("already in the learning plan");
    }

    @Test
    void registersOnlyAcceptedPathScopeAndDocumentationWithCapturedEvidence()
    {
        ContextDiscoveryReport.Source source = new ContextDiscoveryReport.Source("source:1",
                ContextDiscoveryReport.Source.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/", "Iceberg docs",
                ImmutableList.of("context-read:readme"), "README links to the docs");
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Iceberg snapshots",
                ImmutableList.of("plugin/iceberg/"), ImmutableList.of("source:1"))), ImmutableList.of(source)));

        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of()));

        ContextLearningPlan plan = plans.find(WORKSPACE).orElseThrow();
        assertThat(plan.topics().getFirst().scopeId()).isEqualTo(notebook.get().scopes().getLast().id());
        assertThat(plan.topics().getFirst().paths()).containsExactly("plugin/iceberg/");
        assertThat(plan.documentationSources()).hasSize(1);
        assertThat(plan.documentationSources().getFirst().origin()).isEqualTo(ContextEntryOrigin.GENERATED);
        assertThat(plan.documentInventory().items()).hasSize(1);
        assertThat(plan.documentInventory().items().getFirst().status()).isEqualTo(ContextDocumentInventory.Status.PENDING);
        assertThat(notebook.get().scopes().getLast().match())
                .isEqualTo(new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of()));
        assertThat(notebook.get().sourceEvidence()).hasSize(1);
    }

    @Test
    void keepsConnectorNotesSeparateWhileAllowingTheSameSupportingReadPaths()
    {
        List<String> reads = ImmutableList.of("plugin/iceberg/", "plugin/delta/", "plugin/hive/");
        ContextScope iceberg = new ContextScope("path:plugin/iceberg/", "Iceberg",
                new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of()), true, Optional.empty(), Optional.empty());
        ContextEntry saved = new ContextEntry(ContextEntry.knowledgeId(iceberg.id()), ContextEntryKind.KNOWLEDGE, iceberg.id(),
                "Iceberg metadata", "Iceberg uses snapshots.", "An existing approved Iceberg note.", ImmutableList.of(), ImmutableList.of(),
                ContextEntryOrigin.USER, true, 1);
        notebook.set(new RepositoryNotebook(1, 0, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project(), iceberg),
                ImmutableList.of(saved), ImmutableList.of(), Optional.empty()));
        reports.save(Optional.empty(), report(ImmutableList.of(
                topic("topic:1", "Iceberg file selection", reads, Optional.of(ImmutableList.of("plugin/iceberg/")), ImmutableList.of()),
                topic("topic:2", "Delta transaction log", reads, Optional.of(ImmutableList.of("plugin/delta/")), ImmutableList.of())), ImmutableList.of()));

        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT,
                ImmutableList.of("topic:1", "topic:2"), ImmutableList.of()));

        ContextLearningPlan plan = plans.find(WORKSPACE).orElseThrow();
        assertThat(plan.topic("topic:1").scopeId()).isEqualTo(iceberg.id());
        assertThat(plan.topic("topic:1").notePaths()).contains(ImmutableList.of("plugin/iceberg/"));
        assertThat(plan.topic("topic:2").notePaths()).contains(ImmutableList.of("plugin/delta/"));
        ContextScope delta = notebook.get().scopes().stream().filter(scope -> scope.id().equals(plan.topic("topic:2").scopeId())).findFirst().orElseThrow();
        assertThat(delta.match()).isEqualTo(new ContextMatch.Selected(ImmutableList.of("plugin/delta/"), ImmutableList.of()));
        assertThat(notebook.get().scopes()).containsExactly(ContextScope.project(), iceberg, delta);
        assertThat(notebook.get().entries()).containsExactly(saved);
        ArgumentCaptor<ContextBuild.Assignment> assignments = ArgumentCaptor.forClass(ContextBuild.Assignment.class);
        verify(builds).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), assignments.capture());
        assertThat(assignments.getValue().paths()).isEqualTo(reads);
        ContextBuild bound = task(plan, "topic:1", 0);
        assertThat(learning.require(bound)).isEqualTo(plan);
        learning.progress(bound, "Established Iceberg file selection from its metadata implementation.", true, ImmutableList.of());
        learning.continueLearning(WORKSPACE, new ContextLearningService.ContinueRequest("continue", notebook.get().revision(), 0, plan.id()));
        verify(builds, times(2)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), assignments.capture());
        assertThat(assignments.getAllValues().getLast().topicId()).isEqualTo("topic:2");
        assertThat(assignments.getAllValues().getLast().paths()).isEqualTo(reads);
        assertThat(notebook.get().entries()).containsExactly(saved);
    }

    @Test
    void givesDifferentFullPathSetsTheirOwnScopesAndReusesReorderedPaths()
    {
        ContextScope iceberg = new ContextScope("path:plugin/iceberg/", "Iceberg",
                new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of()), true, Optional.empty(), Optional.empty());
        ContextScope withTerms = new ContextScope("legacy", "Legacy topic",
                new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/", "plugin/delta/"), ImmutableList.of("snapshots")),
                true, Optional.empty(), Optional.empty());
        notebook.set(new RepositoryNotebook(1, 0, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project(), iceberg, withTerms),
                ImmutableList.of(), ImmutableList.of(), Optional.empty()));
        reports.save(Optional.empty(), report(ImmutableList.of(
                topic("topic:1", "Shared storage", ImmutableList.of("plugin/iceberg/", "plugin/delta/"), ImmutableList.of()),
                topic("topic:2", "Shared catalogs", ImmutableList.of("plugin/delta/", "plugin/iceberg/"), ImmutableList.of()),
                topic("topic:3", "Hive comparison", ImmutableList.of("plugin/iceberg/", "plugin/hive/"), ImmutableList.of())), ImmutableList.of()));

        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT,
                ImmutableList.of("topic:1", "topic:2", "topic:3"), ImmutableList.of()));

        ContextLearningPlan plan = plans.find(WORKSPACE).orElseThrow();
        assertThat(notebook.get().scopes()).hasSize(5).contains(iceberg, withTerms);
        String shared = plan.topic("topic:1").scopeId();
        assertThat(shared).isNotEqualTo(iceberg.id()).isNotEqualTo(withTerms.id());
        assertThat(plan.topic("topic:2").scopeId()).isEqualTo(shared);
        assertThat(plan.topic("topic:3").scopeId()).isNotEqualTo(shared).isNotEqualTo(iceberg.id());
        assertThat(notebook.get().scopes().stream().filter(scope -> scope.id().equals(shared)).findFirst().orElseThrow().match())
                .isEqualTo(new ContextMatch.Selected(ImmutableList.of("plugin/delta/", "plugin/iceberg/"), ImmutableList.of()));
    }

    @Test
    void reusesAnEnabledLegacyScopeWithoutChangingItsRuleOrBreakingContinuation()
    {
        ContextMatch match = new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/", "plugin/delta/"), ImmutableList.of());
        ContextScope disabled = new ContextScope("disabled", "Disabled duplicate", match, false, Optional.empty(), Optional.empty());
        ContextScope legacy = new ContextScope("path:plugin/iceberg/", "Shared storage", match, true, Optional.empty(), Optional.empty());
        notebook.set(new RepositoryNotebook(1, 0, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project(), disabled, legacy),
                ImmutableList.of(), ImmutableList.of(), Optional.empty()));
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Storage",
                ImmutableList.of("plugin/delta/", "plugin/iceberg/"), ImmutableList.of())), ImmutableList.of()));

        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of()));

        ContextLearningPlan plan = plans.find(WORKSPACE).orElseThrow();
        assertThat(plan.topic("topic:1").scopeId()).isEqualTo(legacy.id());
        assertThat(notebook.get().scopes()).containsExactly(ContextScope.project(), disabled, legacy);
        verify(notebooks, never()).registerDiscovered(eq(WORKSPACE), anyLong(), anyList(), anyList(), anyList());
        ContextBuild bound = task(plan, "topic:1", 0);
        buildStore.save(Optional.empty(), bound);
        learning.progress(bound, "Read the next source.", false, ImmutableList.of());
        view.set(view(1));
        learning.continueLearning(WORKSPACE, new ContextLearningService.ContinueRequest("continue", 0, 1, plan.id()));
        verify(builds, times(2)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any());
    }

    @Test
    void doesNotCreateAReplacementForADisabledExactScope()
    {
        ContextScope disabled = new ContextScope("path:plugin/iceberg/", "Shared storage",
                new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/", "plugin/delta/"), ImmutableList.of()), false, Optional.empty(), Optional.empty());
        notebook.set(new RepositoryNotebook(1, 0, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project(), disabled),
                ImmutableList.of(), ImmutableList.of(), Optional.empty()));
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Storage",
                ImmutableList.of("plugin/delta/", "plugin/iceberg/"), ImmutableList.of())), ImmutableList.of()));

        assertThatIllegalArgumentException().isThrownBy(() -> learning.start(WORKSPACE,
                new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of())))
                .withMessageContaining("Learning scope is disabled");
        verify(notebooks, never()).registerDiscovered(eq(WORKSPACE), anyLong(), anyList(), anyList(), anyList());
    }

    @Test
    void rejectsMismatchedNoteApplicabilityBeforeStartingOrUsingALearningTask()
    {
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Iceberg",
                ImmutableList.of("plugin/iceberg/"), ImmutableList.of())), ImmutableList.of()));
        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of()));
        ContextLearningPlan original = plans.find(WORKSPACE).orElseThrow();
        ContextLearningPlan.Topic topic = original.topic("topic:1");
        ContextLearningPlan mismatched = original.withProgress(ImmutableList.of(new ContextLearningPlan.Topic(topic.id(), topic.title(),
                topic.scopeId(), topic.scopeMatchDigest(), topic.paths(), Optional.of(ImmutableList.of("plugin/iceberg/", "plugin/delta/")),
                topic.repositoryFiles(), topic.sourceIds(), topic.questions())), original.gaps());
        plans.save(Optional.of(original), mismatched);
        RepositoryNotebook saved = notebook.get();

        assertThatIllegalArgumentException().isThrownBy(() -> learning.continueLearning(WORKSPACE,
                new ContextLearningService.ContinueRequest("continue", saved.revision(), 0, mismatched.id())))
                .withMessageContaining("Discard this learning plan").withMessageContaining("saved notes are kept");
        assertThatIllegalArgumentException().isThrownBy(() -> learning.require(task(mismatched, "topic:1", 0)))
                .withMessageContaining("Saved note paths do not match");
        verify(builds, times(1)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any());
        assertThat(notebook.get()).isEqualTo(saved);
        assertThat(plans.find(WORKSPACE)).contains(mismatched);
    }

    @Test
    void rejectsDiscoveryWithoutNoteApplicabilityBeforeAllocatingAnything()
    {
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Lakehouse connectors",
                ImmutableList.of("plugin/iceberg/", "plugin/delta/"), Optional.empty(), ImmutableList.of())), ImmutableList.of()));
        RepositoryNotebook saved = notebook.get();

        assertThatIllegalArgumentException().isThrownBy(() -> learning.start(WORKSPACE,
                new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of())))
                .withMessageContaining("note applicability");

        assertThat(plans.find(WORKSPACE)).isEmpty();
        assertThat(notebook.get()).isEqualTo(saved);
        verify(notebooks, never()).registerDiscovered(eq(WORKSPACE), anyLong(), anyList(), anyList(), anyList());
        verify(builds, never()).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any());
    }

    @Test
    void keepsTheNotebookButRejectsContinuingOrUsingAPlanWithoutNoteApplicability()
    {
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Iceberg",
                ImmutableList.of("plugin/iceberg/"), ImmutableList.of())), ImmutableList.of()));
        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of()));
        ContextLearningPlan original = plans.find(WORKSPACE).orElseThrow();
        ContextLearningPlan.Topic topic = original.topic("topic:1");
        ContextLearningPlan legacy = original.withProgress(ImmutableList.of(new ContextLearningPlan.Topic(topic.id(), topic.title(),
                topic.scopeId(), topic.scopeMatchDigest(), topic.paths(), Optional.empty(),
                topic.repositoryFiles(), topic.sourceIds(), topic.questions())), original.gaps());
        plans.save(Optional.of(original), legacy);
        RepositoryNotebook current = notebook.get();
        ContextEntry note = new ContextEntry(ContextEntry.knowledgeId(topic.scopeId()), ContextEntryKind.KNOWLEDGE, topic.scopeId(),
                "Iceberg metadata", "Iceberg uses snapshots.", "Keep this approved Iceberg note.", ImmutableList.of(), ImmutableList.of(),
                ContextEntryOrigin.USER, true, 1);
        notebook.set(new RepositoryNotebook(1, current.revision(), true, true, current.sources(), current.scopes(),
                ImmutableList.of(note), current.sourceEvidence(), Optional.empty()));
        RepositoryNotebook saved = notebook.get();

        assertThatIllegalArgumentException().isThrownBy(() -> learning.continueLearning(WORKSPACE,
                new ContextLearningService.ContinueRequest("continue", saved.revision(), 0, legacy.id())))
                .withMessageContaining("note applicability").withMessageContaining("saved notes are kept");
        assertThatIllegalArgumentException().isThrownBy(() -> learning.require(task(legacy, "topic:1", 0)))
                .withMessageContaining("note applicability");

        verify(builds, times(1)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any());
        assertThat(notebook.get()).isEqualTo(saved);
        assertThat(plans.find(WORKSPACE)).contains(legacy);
    }

    @Test
    void rejectsAnEditedScopeEvenWhenTheApprovedNotePathsStillMatch()
    {
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Shared storage",
                ImmutableList.of("plugin/iceberg/", "plugin/delta/"), ImmutableList.of())), ImmutableList.of()));
        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of()));
        ContextLearningPlan plan = plans.find(WORKSPACE).orElseThrow();
        RepositoryNotebook current = notebook.get();
        ContextScope scope = current.scopes().getLast();
        ContextScope reordered = new ContextScope(scope.id(), scope.title(),
                new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/", "plugin/delta/"), ImmutableList.of()),
                true, scope.preparation(), scope.discoveryEvidenceRef());
        notebook.set(new RepositoryNotebook(1, current.revision(), true, true, current.sources(), ImmutableList.of(ContextScope.project(), reordered),
                current.entries(), current.sourceEvidence(), Optional.empty()));

        assertThatIllegalArgumentException().isThrownBy(() -> learning.require(task(plan, "topic:1", 0)))
                .withMessageContaining("matching rule changed");
    }

    @Test
    void leavesUncheckedProposedSourcesUnregisteredAndOutOfTheTopic()
    {
        ContextDiscoveryReport.Source source = new ContextDiscoveryReport.Source("source:1",
                ContextDiscoveryReport.Source.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/", "Iceberg docs",
                ImmutableList.of("context-read:readme"), "README links to the docs");
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Iceberg snapshots",
                ImmutableList.of(), ImmutableList.of("source:1"))), ImmutableList.of(source)));

        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of("source:1")));

        ContextLearningPlan plan = plans.find(WORKSPACE).orElseThrow();
        assertThat(plan.documentationSources()).isEmpty();
        assertThat(plan.topics().getFirst().sourceIds()).isEmpty();
        verify(notebooks, never()).registerDiscovered(eq(WORKSPACE), anyLong(), anyList(), anyList(), anyList());
    }

    @Test
    void approvesSuggestedUrlsAtTheChosenBoundaryAndDeclinesWithoutRegistering()
    {
        String rootUrl = "https://other.example.org/spec/";
        String pageUrl = "https://other.example.org/one-page.html";
        String declinedUrl = "https://third.example.org/guide.html";
        RegisteredContextSource original = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                "https://example.com/docs/", ContextEntryOrigin.USER, Optional.empty());
        notebook.set(new RepositoryNotebook(1, 0, true, true, ImmutableList.of(original), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(), ImmutableList.of(), Optional.empty()));
        ContextDiscoveryReport.Source source = new ContextDiscoveryReport.Source("source:1",
                ContextDiscoveryReport.Source.Kind.DOCUMENTATION_ROOT, original.url(), "Docs",
                ImmutableList.of("context-read:readme"), "README links to the docs");
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Concepts", ImmutableList.of(), ImmutableList.of("source:1"))),
                ImmutableList.of(source)));
        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of()));
        ContextLearningPlan accepted = plans.find(WORKSPACE).orElseThrow();
        ContextDocumentInventory initial = accepted.documentInventory();
        ContextDocumentInventory inventory = initial.read(initial.items().getFirst(), "context-read:root", "hash")
                .offer("docs", original.url(), ImmutableList.of(new DocumentationHttpReader.Link(original.url() + "child", Optional.of("child"))), false)
                .suggest(ImmutableList.of(rootUrl, pageUrl, declinedUrl), original.url(), "topic:1", 0, "task", Instant.EPOCH);
        plans.save(Optional.of(accepted), accepted.withInventory(inventory));

        assertThatIllegalArgumentException().isThrownBy(() -> learning.allowSource(WORKSPACE,
                new ContextLearningService.SourceRequest(accepted.id(), "https://forged.example.org/", ContextLearningService.Boundary.PAGE, 0, 0)))
                .withMessageContaining("suggested by a recorded document read");
        assertThatIllegalArgumentException().isThrownBy(() -> learning.allowSource(WORKSPACE,
                new ContextLearningService.SourceRequest(accepted.id(), pageUrl, ContextLearningService.Boundary.ROOT, 0, 0)))
                .withMessageContaining("ending in /");
        assertThat(notebook.get().sources()).containsExactly(original);

        ContextLearningPlan beforeStaleApproval = plans.find(WORKSPACE).orElseThrow();
        RepositoryNotebook beforeSourceEdit = notebook.get();
        RegisteredContextSource edited = new RegisteredContextSource(original.id(), original.kind(),
                "https://example.com/changed/", ContextEntryOrigin.USER, Optional.empty());
        notebook.set(new RepositoryNotebook(1, 1, true, true, ImmutableList.of(edited), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(), ImmutableList.of(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> learning.allowSource(WORKSPACE,
                new ContextLearningService.SourceRequest(accepted.id(), rootUrl, ContextLearningService.Boundary.ROOT, 1, 0)))
                .withMessageContaining("Approved documentation source changed");
        assertThat(plans.find(WORKSPACE)).contains(beforeStaleApproval);
        verify(notebooks, never()).configure(eq(WORKSPACE), anyLong(), any());
        notebook.set(beforeSourceEdit);

        when(notebooks.configure(eq(WORKSPACE), anyLong(), any())).thenAnswer(call -> {
            EditContextConfiguration edit = call.getArgument(2);
            List<RegisteredContextSource> registered = edit.sources().orElseThrow().stream()
                    .map(value -> new RegisteredContextSource(value.id(), value.kind(), value.url(),
                            ContextEntryOrigin.USER, Optional.empty())).toList();
            RepositoryNotebook updated = new RepositoryNotebook(1, notebook.get().revision() + 1, true, true, registered,
                    notebook.get().scopes(), ImmutableList.of(), ImmutableList.of(), Optional.empty());
            notebook.set(updated);
            return updated;
        });
        ContextLearningPlan expanded = learning.allowSource(WORKSPACE,
                new ContextLearningService.SourceRequest(accepted.id(), rootUrl, ContextLearningService.Boundary.ROOT, 0, 0));
        RegisteredContextSource approvedRoot = expanded.documentationSources().getLast();
        assertThat(approvedRoot.url()).isEqualTo(rootUrl);
        assertThat(approvedRoot.kind()).isEqualTo(RegisteredContextSource.Kind.DOCUMENTATION_ROOT);
        assertThat(expanded.documentInventory().choose(approvedRoot.id(), Optional.empty()).depth()).isZero();
        assertThat(expanded.documentInventory().choose("docs", Optional.of("child")).depth()).isEqualTo(1);
        assertThat(expanded.topics().getFirst().sourceIds()).containsExactly(accepted.topics().getFirst().sourceIds().getFirst(), approvedRoot.id());
        assertThat(expanded.documentInventory().suggestions()).extracting(ContextDocumentInventory.Suggestion::url).containsExactly(pageUrl, declinedUrl);

        ContextLearningPlan pageExpanded = learning.allowSource(WORKSPACE,
                new ContextLearningService.SourceRequest(accepted.id(), pageUrl, ContextLearningService.Boundary.PAGE, 1, 0));
        RegisteredContextSource approvedPage = pageExpanded.documentationSources().getLast();
        assertThat(approvedPage.kind()).isEqualTo(RegisteredContextSource.Kind.DOCUMENTATION_PAGE);
        assertThat(pageExpanded.documentInventory().choose(approvedPage.id(), Optional.empty()).depth()).isZero();

        ContextLearningPlan declined = learning.declineSource(WORKSPACE, new ContextLearningService.DeclineRequest(accepted.id(), declinedUrl));
        assertThat(declined.documentInventory().suggestions()).isEmpty();
        assertThat(notebook.get().sources()).extracting(RegisteredContextSource::url).doesNotContain(declinedUrl);
        assertThat(notebook.get().revision()).isEqualTo(2);
        verify(builds, times(1)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any());
    }

    @Test
    void acceptsTheRecordedDiscoveryRevisionAfterTheBranchMovesButNotAMissingOne()
    {
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Project",
                ImmutableList.of(), ImmutableList.of())), ImmutableList.of()));
        when(git.run(LOCAL, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, "b".repeat(40) + "\n", false));
        when(git.run(LOCAL, "rev-parse", "--verify", SHA + "^{commit}")).thenReturn(new GitCommandRunner.Result(128, "", false));

        assertThatIllegalArgumentException().isThrownBy(() -> learning.start(WORKSPACE,
                new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of())))
                .withMessageContaining("no longer available");
        assertThat(plans.find(WORKSPACE)).isEmpty();
        verify(notebooks, never()).registerDiscovered(eq(WORKSPACE), anyLong(), anyList(), anyList(), anyList());

        when(git.run(LOCAL, "rev-parse", "--verify", SHA + "^{commit}")).thenReturn(new GitCommandRunner.Result(0, SHA + "\n", false));
        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"), ImmutableList.of()));
        assertThat(plans.find(WORKSPACE).orElseThrow().baselineSha()).isEqualTo(SHA);
    }

    private ContextDiscoveryReport report(List<ContextDiscoveryReport.Topic> topics, List<ContextDiscoveryReport.Source> sources)
    {
        return new ContextDiscoveryReport(REPORT, WORKSPACE, 1, REPO, SHA, "Project overview", topics, sources,
                ImmutableList.of(new ContextDiscoveryReport.Evidence("context-read:readme", "README.md", SHA, 1, 1,
                        "1  Read the project guide.\n", false)), ImmutableList.of(), ImmutableList.of(), false,
                ContextDiscoveryReport.Coverage.none(), Instant.EPOCH);
    }

    private static ContextDiscoveryReport.Topic topic(String id, String title, List<String> paths, List<String> sources)
    {
        return topic(id, title, paths, Optional.of(paths), sources);
    }

    private static ContextDiscoveryReport.Topic topic(String id, String title, List<String> paths, Optional<List<String>> notePaths, List<String> sources)
    {
        return new ContextDiscoveryReport.Topic(id, title,
                ImmutableList.of(id.equals("topic:1") && paths.isEmpty() ? "How is the project structured?" : "What does this area require?",
                        "Where are its tests?"),
                paths, notePaths, sources, ImmutableList.of("context-read:readme"), "Repository guide identifies this area");
    }

    private static ContextBuild task(ContextLearningPlan plan, String topicId, int question)
    {
        ContextLearningPlan.Topic topic = plan.topic(topicId);
        ContextLearningPlan.Question asked = topic.questions().get(question);
        return new ContextBuild(1, "accept", "sha256:" + "b".repeat(64), WORKSPACE,
                "22222222-2222-4222-8222-222222222222", new RepositoryIdentity("owner", "repository"), 1,
                REPO, SHA, "branch", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                ImmutableList.of(topic.scopeId()), ImmutableList.of(), ImmutableList.of(), 0, Instant.EPOCH,
                ContextBuild.Capture.empty(), ContextBuild.Mode.LEARN,
                Optional.of(new ContextBuild.Assignment(plan.id(), topicId, topic.title(), question, asked.text(), asked.progress(),
                        topic.paths(), topic.repositoryFiles())), Optional.empty(), Optional.empty());
    }

    private static ContextBuildService.View view(long sequence)
    {
        return new ContextBuildService.View(sequence, 0, true, Optional.empty(), ImmutableList.of());
    }
}
