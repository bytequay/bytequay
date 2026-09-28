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
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final AtomicReference<ContextBuildService.View> view = new AtomicReference<>(
            new ContextBuildService.View(0, 0, true, Optional.empty()));
    private ContextLearningService learning;

    @BeforeEach
    void setup()
    {
        when(notebooks.getNotebook(WORKSPACE)).thenAnswer(ignored -> notebook.get());
        when(workspaces.requireReadableRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(
                WORKSPACE, LOCAL, 1, REPO, OptionalLong.of(1), REPO, "origin", "origin", "main", ImmutableList.of()));
        when(git.run(LOCAL, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, SHA + "\n", false));
        when(builds.get(WORKSPACE)).thenAnswer(ignored -> view.get());
        when(builds.learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any())).thenAnswer(ignored -> view.get());
        when(unitOfWork.execute(any())).thenAnswer(call -> ((AgentUnitOfWork.Work<?>) call.getArgument(0)).run());
        learning = new ContextLearningService(reports, plans, buildStore, builds, notebooks, workspaces, git, unitOfWork, mapper, clock);
    }

    @Test
    void acceptsSelectedTopicsOnceAndContinuesFromSavedQuestion()
    {
        reports.save(Optional.empty(), report(ImmutableList.of(
                topic("topic:1", "Project structure", ImmutableList.of(), ImmutableList.of()),
                topic("topic:2", "Build requirements", ImmutableList.of(), ImmutableList.of())), ImmutableList.of()));
        ContextLearningService.StartRequest request = new ContextLearningService.StartRequest(
                "accept", 0, 0, REPORT, ImmutableList.of("topic:1", "topic:2"));

        learning.start(WORKSPACE, request);

        ContextLearningPlan plan = plans.find(WORKSPACE).orElseThrow();
        assertThat(plan.topics()).extracting(ContextLearningPlan.Topic::scopeId).containsExactly("project", "project");
        assertThat(plan.topics().getFirst().repositoryFiles()).containsExactly("README.md");
        ArgumentCaptor<ContextBuild.Assignment> first = ArgumentCaptor.forClass(ContextBuild.Assignment.class);
        verify(builds).learn(eq(WORKSPACE), any(), eq(SHA), eq(ImmutableList.of()), first.capture());
        assertThat(first.getValue().topicId()).isEqualTo("topic:1");
        ContextBuild bound = task(plan, "topic:1", "How is the project structured?");
        buildStore.save(Optional.empty(), bound);
        learning.start(WORKSPACE, request);
        verify(builds, times(1)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any());

        learning.progress(bound, "Structure inspected; next question is separate.", true, ImmutableList.of("README was partial"));
        assertThat(plans.find(WORKSPACE).orElseThrow().topics().getFirst().status()).isEqualTo(ContextLearningPlan.Status.COMPLETED);
        view.set(new ContextBuildService.View(1, 0, true, Optional.empty()));
        learning.continueLearning(WORKSPACE, new ContextLearningService.ContinueRequest("continue", 0, 1, plan.id()));
        ArgumentCaptor<ContextBuild.Assignment> assignments = ArgumentCaptor.forClass(ContextBuild.Assignment.class);
        verify(builds, times(2)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), assignments.capture());
        assertThat(assignments.getAllValues().getLast().topicId()).isEqualTo("topic:2");
        assertThat(plans.find(WORKSPACE).orElseThrow().gaps()).contains("README was partial");
    }

    @Test
    void registersOnlyAcceptedPathScopeAndDocumentationWithCapturedEvidence()
    {
        ContextDiscoveryReport.Source source = new ContextDiscoveryReport.Source("source:1",
                ContextDiscoveryReport.Source.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/", "Iceberg docs",
                ImmutableList.of("context-read:readme"), "README links to the docs");
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Iceberg snapshots",
                ImmutableList.of("plugin/iceberg/"), ImmutableList.of("source:1"))), ImmutableList.of(source)));
        when(notebooks.registerDiscovered(eq(WORKSPACE), eq(0L), anyList(), anyList(), anyList())).thenAnswer(call -> {
            List<ContextScope> scopes = call.getArgument(2);
            List<RegisteredContextSource> sources = call.getArgument(3);
            List<ContextSourceEvidence> evidence = call.getArgument(4);
            RepositoryNotebook updated = new RepositoryNotebook(1, 1, true, true, sources,
                    ImmutableList.<ContextScope>builder().add(ContextScope.project()).addAll(scopes).build(),
                    ImmutableList.of(), ImmutableList.of(), evidence, Optional.empty());
            notebook.set(updated);
            return updated;
        });

        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1")));

        ContextLearningPlan plan = plans.find(WORKSPACE).orElseThrow();
        assertThat(plan.topics().getFirst().scopeId()).isEqualTo("path:plugin/iceberg/");
        assertThat(plan.topics().getFirst().paths()).containsExactly("plugin/iceberg/");
        assertThat(plan.documentationSources()).hasSize(1);
        assertThat(plan.documentationSources().getFirst().origin()).isEqualTo(com.bytequay.core.context.ContextEntryOrigin.GENERATED);
        assertThat(plan.documentInventory().items()).hasSize(1);
        assertThat(plan.documentInventory().items().getFirst().status()).isEqualTo(ContextDocumentInventory.Status.PENDING);
        assertThat(notebook.get().scopes().getLast().match())
                .isEqualTo(new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of()));
        assertThat(notebook.get().sourceEvidence()).hasSize(1);
    }

    @Test
    void approvingAnotherSourcePreservesDepthAndDoesNotStartAnotherTurn()
    {
        ContextDiscoveryReport.Source source = new ContextDiscoveryReport.Source("source:1",
                ContextDiscoveryReport.Source.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/", "Docs",
                ImmutableList.of("context-read:readme"), "README links to the docs");
        RegisteredContextSource original = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                source.location(), com.bytequay.core.context.ContextEntryOrigin.USER, Optional.empty());
        RegisteredContextSource expansion = new RegisteredContextSource("extra", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                "https://other.example.org/spec/", com.bytequay.core.context.ContextEntryOrigin.USER, Optional.empty());
        notebook.set(new RepositoryNotebook(1, 0, true, true, ImmutableList.of(original, expansion), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty()));
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Concepts", ImmutableList.of(), ImmutableList.of("source:1"))),
                ImmutableList.of(source)));
        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1")));
        ContextLearningPlan accepted = plans.find(WORKSPACE).orElseThrow();
        ContextDocumentInventory initial = accepted.documentInventory();
        ContextDocumentInventory inventory = initial.read(initial.items().getFirst(), "context-read:root", "hash")
                .offer("docs", original.url(), ImmutableList.of(new DocumentationHttpReader.Link(original.url() + "child", Optional.of("child"))), false);
        plans.save(Optional.of(accepted), accepted.withInventory(inventory));

        ContextLearningPlan expanded = learning.allowSource(WORKSPACE, new ContextLearningService.SourceRequest(accepted.id(), "extra", 0, 0));

        assertThat(expanded.documentInventory().items()).startsWith(inventory.items().toArray(ContextDocumentInventory.Item[]::new));
        assertThat(expanded.documentInventory().items().getLast().depth()).isZero();
        ContextDocumentInventory.Item child = expanded.documentInventory().choose("docs", Optional.of("child"));
        assertThat(child.depth()).isEqualTo(1);
        ContextDocumentInventory afterChild = expanded.documentInventory().read(child, "context-read:child", "hash-child")
                .offer("docs", child.resolvedUrl(), ImmutableList.of(new DocumentationHttpReader.Link(original.url() + "grand", Optional.of("grand"))), false);
        ContextDocumentInventory.Item grand = afterChild.choose("docs", Optional.of("grand"));
        assertThat(grand.depth()).isEqualTo(2);
        ContextDocumentInventory afterGrand = afterChild.read(grand, "context-read:grand", "hash-grand")
                .offer("docs", grand.resolvedUrl(), ImmutableList.of(new DocumentationHttpReader.Link(original.url() + "too-deep", Optional.of("too-deep"))), false);
        assertThatIllegalArgumentException().isThrownBy(() -> afterGrand.choose("docs", Optional.of("too-deep")));
        assertThat(afterGrand.choose("extra", Optional.empty()).depth()).isZero();
        assertThat(expanded.topics().getFirst().sourceIds()).containsExactly("docs", "extra");
        verify(builds, times(1)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any());
        assertThatIllegalArgumentException().isThrownBy(() -> learning.allowSource(WORKSPACE,
                new ContextLearningService.SourceRequest(accepted.id(), "unregistered", 0, 0))).withMessageContaining("registered");
    }

    @Test
    void approvesOnlyPersistedSuggestedUrlsAndStartsEachExactUrlAtDepthZero()
    {
        String rootUrl = "https://other.example.org/spec/";
        String pageUrl = "https://other.example.org/one-page.html";
        RegisteredContextSource original = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                "https://example.com/docs/", com.bytequay.core.context.ContextEntryOrigin.USER, Optional.empty());
        notebook.set(new RepositoryNotebook(1, 0, true, true, ImmutableList.of(original), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty()));
        ContextDiscoveryReport.Source source = new ContextDiscoveryReport.Source("source:1",
                ContextDiscoveryReport.Source.Kind.DOCUMENTATION_ROOT, original.url(), "Docs",
                ImmutableList.of("context-read:readme"), "README links to the docs");
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Concepts", ImmutableList.of(), ImmutableList.of("source:1"))),
                ImmutableList.of(source)));
        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1")));
        ContextLearningPlan accepted = plans.find(WORKSPACE).orElseThrow();
        ContextDocumentInventory initial = accepted.documentInventory();
        ContextDocumentInventory inventory = initial.read(initial.items().getFirst(), "context-read:root", "hash")
                .offer("docs", original.url(), ImmutableList.of(new DocumentationHttpReader.Link(original.url() + "child", Optional.of("child"))), false)
                .suggest(ImmutableList.of(rootUrl, pageUrl));
        plans.save(Optional.of(accepted), accepted.withInventory(inventory));

        assertThatIllegalArgumentException().isThrownBy(() -> learning.allowSource(WORKSPACE,
                new ContextLearningService.SourceRequest(accepted.id(), Optional.empty(), Optional.of("https://forged.example.org/"), 0, 0)))
                .withMessageContaining("suggested by a recorded document read");
        assertThat(notebook.get().sources()).containsExactly(original);
        assertThat(plans.find(WORKSPACE).orElseThrow().documentInventory().suggestedExpansionUrls()).containsExactly(rootUrl, pageUrl);

        ContextLearningPlan beforeStaleApproval = plans.find(WORKSPACE).orElseThrow();
        RepositoryNotebook beforeSourceEdit = notebook.get();
        RegisteredContextSource edited = new RegisteredContextSource(original.id(), original.kind(),
                "https://example.com/changed/", com.bytequay.core.context.ContextEntryOrigin.USER, Optional.empty());
        notebook.set(new RepositoryNotebook(1, 1, true, true, ImmutableList.of(edited), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty()));
        assertThatIllegalArgumentException().isThrownBy(() -> learning.allowSource(WORKSPACE,
                new ContextLearningService.SourceRequest(accepted.id(), Optional.empty(), Optional.of(rootUrl), 1, 0)))
                .withMessageContaining("Approved documentation source changed");
        assertThat(notebook.get().sources()).containsExactly(edited);
        assertThat(plans.find(WORKSPACE)).contains(beforeStaleApproval);
        verify(notebooks, never()).configure(eq(WORKSPACE), anyLong(), any());
        notebook.set(beforeSourceEdit);

        when(notebooks.configure(eq(WORKSPACE), anyLong(), any())).thenAnswer(call -> {
            EditContextConfiguration edit = call.getArgument(2);
            List<RegisteredContextSource> registered = edit.sources().orElseThrow().stream()
                    .map(value -> new RegisteredContextSource(value.id(), value.kind(), value.url(),
                            com.bytequay.core.context.ContextEntryOrigin.USER, Optional.empty())).toList();
            RepositoryNotebook updated = new RepositoryNotebook(1, notebook.get().revision() + 1, true, true, registered,
                    notebook.get().scopes(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty());
            notebook.set(updated);
            return updated;
        });
        ContextLearningPlan expanded = learning.allowSource(WORKSPACE,
                new ContextLearningService.SourceRequest(accepted.id(), Optional.empty(), Optional.of(rootUrl), 0, 0));
        RegisteredContextSource approvedRoot = expanded.documentationSources().getLast();
        assertThat(approvedRoot.url()).isEqualTo(rootUrl);
        assertThat(approvedRoot.kind()).isEqualTo(RegisteredContextSource.Kind.DOCUMENTATION_ROOT);
        assertThat(approvedRoot.origin()).isEqualTo(com.bytequay.core.context.ContextEntryOrigin.USER);
        assertThat(expanded.documentInventory().choose(approvedRoot.id(), Optional.empty()).depth()).isZero();
        assertThat(expanded.documentInventory().choose("docs", Optional.of("child")).depth()).isEqualTo(1);
        assertThat(expanded.documentInventory().suggestedExpansionUrls()).containsExactly(pageUrl);
        assertThat(notebook.get().revision()).isEqualTo(1);

        ContextLearningPlan pageExpanded = learning.allowSource(WORKSPACE,
                new ContextLearningService.SourceRequest(accepted.id(), Optional.empty(), Optional.of(pageUrl), 1, 0));
        RegisteredContextSource approvedPage = pageExpanded.documentationSources().getLast();
        assertThat(approvedPage.url()).isEqualTo(pageUrl);
        assertThat(approvedPage.kind()).isEqualTo(RegisteredContextSource.Kind.DOCUMENTATION_PAGE);
        assertThat(pageExpanded.documentInventory().choose(approvedPage.id(), Optional.empty()).depth()).isZero();
        assertThat(pageExpanded.documentInventory().suggestedExpansionUrls()).isEmpty();
        assertThat(notebook.get().revision()).isEqualTo(2);
        verify(builds, times(1)).learn(eq(WORKSPACE), any(), eq(SHA), anyList(), any());
    }

    @Test
    void readsSavedPlanBeforeSuggestedUrlFieldExisted()
            throws Exception
    {
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Project", ImmutableList.of(), ImmutableList.of())), ImmutableList.of()));
        learning.start(WORKSPACE, new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1")));
        String key = ContextLearningPlanStore.PREFIX + WORKSPACE;
        ObjectNode stored = (ObjectNode) mapper.readTree(settings.get(key).orElseThrow());
        ((ObjectNode) stored.path("documentInventory")).remove("suggestedExpansionUrls");
        settings.put(key, mapper.writeValueAsString(stored));

        assertThat(plans.find(WORKSPACE).orElseThrow().documentInventory().suggestedExpansionUrls()).isEmpty();
    }

    @Test
    void refusesChangedMonitoredBaselineBeforeRegisteringAnything()
    {
        reports.save(Optional.empty(), report(ImmutableList.of(topic("topic:1", "Project",
                ImmutableList.of(), ImmutableList.of())), ImmutableList.of()));
        when(git.run(LOCAL, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, "b".repeat(40) + "\n", false));

        assertThatIllegalArgumentException().isThrownBy(() -> learning.start(WORKSPACE,
                new ContextLearningService.StartRequest("accept", 0, 0, REPORT, ImmutableList.of("topic:1"))))
                .withMessageContaining("baseline changed");
        assertThat(plans.find(WORKSPACE)).isEmpty();
        verify(notebooks, never()).registerDiscovered(eq(WORKSPACE), anyLong(), anyList(), anyList(), anyList());
    }

    private ContextDiscoveryReport report(List<ContextDiscoveryReport.Topic> topics, List<ContextDiscoveryReport.Source> sources)
    {
        return new ContextDiscoveryReport(REPORT, WORKSPACE, 1, REPO, SHA, "Project overview", topics, sources,
                ImmutableList.of(new ContextDiscoveryReport.Evidence("context-read:readme", "README.md", SHA, 1, 1,
                        "1  Read the project guide.\n", false)), ImmutableList.of(), ImmutableList.of(), false, Instant.EPOCH);
    }

    private static ContextDiscoveryReport.Topic topic(String id, String title, List<String> paths, List<String> sources)
    {
        return new ContextDiscoveryReport.Topic(id, title,
                id.equals("topic:1") && paths.isEmpty() ? "How is the project structured?" : "What does this area require?",
                paths, sources, ImmutableList.of("context-read:readme"), "Repository guide identifies this area");
    }

    private static ContextBuild task(ContextLearningPlan plan, String topicId, String question)
    {
        ContextLearningPlan.Topic topic = plan.topics().stream().filter(value -> value.id().equals(topicId)).findFirst().orElseThrow();
        return new ContextBuild(1, "accept", "sha256:" + "b".repeat(64), WORKSPACE,
                "22222222-2222-4222-8222-222222222222", new RepositoryIdentity("owner", "repository"), 1,
                REPO, SHA, "branch", "codex", AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                ImmutableList.of(topic.scopeId()), ImmutableList.of(), ImmutableList.of(), 0, Instant.EPOCH,
                ContextBuild.Capture.empty(), ContextBuild.Mode.LEARN,
                Optional.of(new ContextBuild.Assignment(plan.id(), topicId, question, topic.paths(), topic.repositoryFiles())));
    }
}
