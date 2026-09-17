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

import com.bytequay.app.context.RepositoryContextPreparation.Execution;
import com.bytequay.app.context.RepositoryContextPreparation.GeneratedEntry;
import com.bytequay.app.context.RepositoryContextPreparation.GeneratedReport;
import com.bytequay.app.context.RepositoryContextPreparation.ScopeReport;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextDocumentRead;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextHistoryRead;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.ContextSourceRead;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.bytequay.app.context.RepositoryContextPreparation.Status.DEFERRED;
import static com.bytequay.app.context.RepositoryContextPreparation.Status.JOINED;
import static com.bytequay.app.context.RepositoryContextPreparation.Status.OWNED;
import static com.bytequay.app.context.RepositoryContextPreparation.Status.REUSED;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.EMPTY;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.FAILED;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.READY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRepositoryContextPreparation
{
    private static final String SHA = "a".repeat(40);
    private static final String DIGEST = "sha256:" + "b".repeat(64);
    private static final String READ_ID = "context-read:" + UUID.randomUUID();
    private static final RepoRef REPOSITORY = RepoRef.of("example", "repository");
    private static final AgentReview REVIEW = new AgentReview("review", "workspace", REPOSITORY.fullName(), 1, OptionalLong.empty(), "task",
            AgentReview.State.OPEN, false, Optional.empty(), Optional.of(SHA), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());

    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final ContextInputReader inputs = mock(ContextInputReader.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final RepositoryContextService notebooks = new RepositoryContextService(new InMemoryAppSettingsStore(),
            new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule()), workspaceStore, mock(AgentReviewStore.class));
    private final RepositoryContextPreparation preparation = new RepositoryContextPreparation(notebooks, inputs, workspaces);

    @BeforeEach
    void setup()
    {
        when(workspaceStore.findById("workspace")).thenReturn(Optional.of(new Workspace("workspace", "Repository", 1, REPOSITORY,
                Optional.empty(), Optional.empty(), 1, REPOSITORY, Workspace.Topology.DIRECT, "main", "https://github.com/example/repository.git",
                Optional.empty(), Optional.empty(), "/tmp/context-test", Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH)));
        when(workspaces.requireReadableRepository("workspace")).thenReturn(new WorkspaceRepositoryContext("workspace", Path.of("/tmp/context-test"), 1,
                REPOSITORY, OptionalLong.empty(), REPOSITORY, "origin", "origin", "main", ImmutableList.of()));
        when(inputs.capture(any(), any(), any(), anyList())).thenAnswer(call -> {
            RepositoryNotebook notebook = call.getArgument(2);
            List<ContextScope> scopes = call.getArgument(3);
            return scopes.stream().map(scope -> ContextInputFingerprint.create(scope.id(), ImmutableMap.of("source-inventory",
                    notebook.sourceEvidence().toString() + scope.preparation().map(result -> result.checkedReads().toString()).orElse("")), ImmutableList.of()))
                    .collect(ImmutableList.toImmutableList());
        });
    }

    @Test
    void standaloneLearningOwnsKnowledgeWithoutAReviewEvenWhenUseInReviewsIsOff()
    {
        notebooks.setUseInReviews("workspace", 0, false);
        Execution review = execution("turn", false, SHA);
        Execution owner = new Execution(review.workspaceId(), review.repository(), review.repositoryId(), review.baselineSha(), "learning-task", "learning-turn",
                "learning-key", Optional.empty(), Optional.empty(), review.sourceReads(), review.evidence());
        var admitted = begin(owner);
        assertThat(admitted.attempt().orElseThrow().reviewId()).isEmpty();
        assertThat(admitted.attempt().orElseThrow().runId()).isEmpty();
        assertThatThrownBy(() -> preparation.prepareCompletion(review, admitted.attempt().orElseThrow().attemptId(), readyReport(READ_ID)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not owned");
        preparation.complete(owner, preparation.prepareCompletion(owner, admitted.attempt().orElseThrow().attemptId(), readyReport(READ_ID)));
        RepositoryNotebook saved = notebooks.getNotebook("workspace");
        assertThat(saved.useInReviews()).isFalse();
        assertThat(saved.entries()).singleElement().satisfies(entry -> assertThat(entry.body()).isEqualTo("Useful generated body"));
        assertThat(saved.activePreparation()).isEmpty();
    }

    @Test
    void preparationRetainsDiscoveryEvidenceForUnbuiltScopesAndSources()
    {
        ContextSourceEvidence proof = new ContextSourceEvidence("discovery:module", new ContextReference.RepositoryFile(1, REPOSITORY, "component/pom.xml", SHA),
                "Captured module metadata", "<project/>", ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(Instant.EPOCH), Optional.of(SHA), Optional.of(SHA), false);
        ContextScope component = new ContextScope("path:component/", "Component", new ContextMatch.Selected(ImmutableList.of("component/"), ImmutableList.of()),
                false, Optional.empty(), Optional.of(proof.id()));
        RegisteredContextSource source = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                "https://example.com/docs/", ContextEntryOrigin.GENERATED, Optional.of(proof.id()));
        notebooks.registerDiscovered("workspace", 0, ImmutableList.of(component), ImmutableList.of(source), ImmutableList.of(proof));
        Execution owner = execution("turn", false, SHA);

        var admitted = begin(owner);
        preparation.complete(owner, preparation.prepareCompletion(owner, admitted.attempt().orElseThrow().attemptId(), readyReport(READ_ID)));

        RepositoryNotebook saved = notebooks.getNotebook("workspace");
        assertThat(saved.scopes()).contains(component);
        assertThat(saved.sources()).containsExactly(source);
        assertThat(saved.sourceEvidence()).contains(proof);
        assertThat(saved.entries()).singleElement().satisfies(entry -> assertThat(entry.scopeId()).isEqualTo("project"));
    }

    @Test
    void freshDocumentContentInvalidatesReuseButNewReadIdsAndTimesDoNot()
    {
        notebooks.configure("workspace", 0, new EditContextConfiguration(Optional.empty(), Optional.of(ImmutableList.of(new EditContextConfiguration.Source(
                "docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE, "https://example.com/docs/page"))), Optional.empty()));
        Execution owner = documentExecution("original", "a".repeat(64));
        var admitted = begin(owner);
        preparation.complete(owner, preparation.prepareCompletion(owner, admitted.attempt().orElseThrow().attemptId(), readyReport("context-read:original")));
        Execution same = documentExecution("same-content", "a".repeat(64));
        assertThat(begin(same).status()).isEqualTo(REUSED);
        Execution changed = documentExecution("changed", "b".repeat(64));
        assertThat(begin(changed).status()).isEqualTo(OWNED);
    }

    private Execution documentExecution(String suffix, String hash)
    {
        RegisteredContextSource registration = notebooks.getNotebook("workspace").sources().getFirst();
        ContextDocumentRead read = new ContextDocumentRead("context-read:" + suffix, new ContextReference.Document("docs", registration.url()), registration,
                notebooks.getNotebook("workspace").revision(), UUID.randomUUID().toString(), hash, Instant.EPOCH, 1, 1, 6, false, false, false, Optional.of("E1"));
        return new Execution("workspace", REPOSITORY.fullName(), 1, SHA, "learning-task", suffix, "learn-key", Optional.empty(), Optional.empty(),
                ImmutableList.of(read), ImmutableMap.of("E1", new RepositoryContextPreparation.CapturedSource("Registered documentation", "source")));
    }

    @Test
    void matchingReviewJoinsOneOwnerAndDifferentInputsDefer()
    {
        Execution owner = execution("turn", false, SHA);
        var plan = preparation.plan(owner, ImmutableList.of("project"));
        var admitted = preparation.begin(owner, "request", DIGEST, 0, plan);
        assertThat(admitted.status()).isEqualTo(OWNED);
        assertThat(notebooks.getNotebook("workspace").revision()).isZero();
        assertThat(preparation.begin(owner, "request", DIGEST, 0, plan)).isEqualTo(admitted);
        assertThatThrownBy(() -> preparation.begin(owner, "request", "sha256:" + "c".repeat(64), 0, plan)).isInstanceOf(IllegalArgumentException.class);

        Execution other = execution("other-turn", false, SHA);
        assertThat(preparation.begin(other, "other", DIGEST, 0, preparation.plan(other, ImmutableList.of("project"))).status()).isEqualTo(JOINED);
        var different = new RepositoryContextPreparation.Plan(notebooks.getNotebook("workspace"), ImmutableList.of(
                ContextInputFingerprint.create("project", ImmutableMap.of("different", "inputs"), ImmutableList.of())));
        assertThat(preparation.begin(other, "different", DIGEST, 0, different).status()).isEqualTo(DEFERRED);
        assertThat(notebooks.getNotebook("workspace").activePreparation()).isEqualTo(admitted.attempt());
    }

    @Test
    void readyRetainsSourcesAndWarmReuseReturnsActualNoteText()
    {
        Execution owner = execution("turn", false, SHA);
        var admitted = begin(owner);
        var planned = preparation.prepareCompletion(owner, admitted.attempt().orElseThrow().attemptId(), readyReport(READ_ID));
        assertThat(notebooks.getNotebook("workspace").entries()).isEmpty();
        var receipt = preparation.complete(owner, planned);
        RepositoryNotebook saved = notebooks.getNotebook("workspace");
        assertThat(saved.activePreparation()).isEmpty();
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.sourceEvidence()).singleElement().satisfies(source -> {
            assertThat(source.id()).isEqualTo(READ_ID);
            assertThat(source.excerpt()).isEqualTo("source");
        });
        assertThat(receipt.packet().text()).contains("Useful generated body");
        Execution other = execution("other-turn", false, SHA);
        var reused = preparation.begin(other, "reuse", DIGEST, 1, preparation.plan(other, ImmutableList.of("project")));
        assertThat(reused.status()).isEqualTo(REUSED);
        assertThat(reused.packet().text()).contains("Useful generated body", READ_ID);
        assertThat(notebooks.getNotebook("workspace")).isEqualTo(saved);
    }

    @Test
    void oversizedNoteRemainsSavedAndReuseDisclosesPacketOmission()
    {
        Execution owner = execution("turn", false, SHA);
        var admitted = begin(owner);
        var entry = new GeneratedEntry("knowledge:project", "project", "Large note", "Summary", "b".repeat(8192),
                IntStream.range(0, 12).mapToObj(index -> new ContextClaim("c".repeat(2000), ContextClaim.Basis.DOCUMENTED,
                        ImmutableList.of(READ_ID))).collect(ImmutableList.toImmutableList()), ImmutableList.of(READ_ID));
        var report = new GeneratedReport(readyReport(READ_ID).scopeResults(), ImmutableList.of(entry));
        var receipt = preparation.complete(owner, preparation.prepareCompletion(owner, admitted.attempt().orElseThrow().attemptId(), report));
        assertThat(receipt.packet().utf8Bytes()).isLessThanOrEqualTo(ContextPacket.MAXIMUM_BYTES);
        assertThat(receipt.packet().text()).contains("Omitted 1 complete scope sections", "project");
        assertThat(notebooks.getNotebook("workspace").entries().getFirst().body()).isEqualTo(entry.body());
        var reused = preparation.begin(owner, "reuse", DIGEST, 1, preparation.plan(owner, ImmutableList.of("project")));
        assertThat(reused.status()).isEqualTo(REUSED);
        assertThat(reused.packet().text()).contains("Omitted 1 complete scope sections");
    }

    @Test
    void checkedBlankSourceIsReusableEmptyWithoutFabricatingEvidence()
    {
        Execution owner = execution("turn", true, SHA);
        var admitted = begin(owner);
        var report = new GeneratedReport(ImmutableList.of(new ScopeReport("project", EMPTY, ImmutableList.of(),
                ImmutableList.of(READ_ID), "The selected source is empty")), ImmutableList.of());
        preparation.complete(owner, preparation.prepareCompletion(owner, admitted.attempt().orElseThrow().attemptId(), report));
        RepositoryNotebook saved = notebooks.getNotebook("workspace");
        assertThat(saved.entries()).isEmpty();
        assertThat(saved.sourceEvidence()).isEmpty();
        assertThat(saved.revision()).isZero();
        assertThat(saved.scopes().getFirst().preparation().orElseThrow().checkedReads()).hasSize(1);
        assertThat(preparation.begin(owner, "reuse", DIGEST, 0, preparation.plan(owner, ImmutableList.of("project"))).status()).isEqualTo(REUSED);
    }

    @Test
    void userEditRejectsLateCompletionAndCleanupPreservesTheUserNote()
    {
        Execution owner = execution("turn", false, SHA);
        var admitted = begin(owner);
        var planned = preparation.prepareCompletion(owner, admitted.attempt().orElseThrow().attemptId(), readyReport(READ_ID));
        RepositoryNotebook user = notebooks.editEntry("workspace", 0, new EditContextEntry("knowledge:project", "project", "My note", "My summary", "My body",
                ImmutableList.of(new ContextClaim("My question?", ContextClaim.Basis.QUESTION, ImmutableList.of())), ImmutableList.of()));
        assertThatThrownBy(() -> preparation.complete(owner, planned)).isInstanceOf(ContextConflictException.class);
        assertThat(notebooks.getNotebook("workspace")).isEqualTo(user);
        preparation.fail("workspace", admitted.attempt().orElseThrow(), "The owning turn ended");
        RepositoryNotebook settled = notebooks.getNotebook("workspace");
        assertThat(settled.entries()).isEqualTo(user.entries());
        assertThat(settled.activePreparation()).isEmpty();
        assertThat(settled.scopes().getFirst().preparation().orElseThrow().outcome()).isEqualTo(FAILED);
        assertThat(preparation.begin(owner, "retry", DIGEST, 1, preparation.plan(owner, ImmutableList.of("project"))).status()).isEqualTo(DEFERRED);
    }

    @Test
    void missingOrOffBaselineSourceCannotBecomeKnowledge()
    {
        Execution owner = execution("turn", false, "b".repeat(40));
        var admitted = begin(owner);
        String attempt = admitted.attempt().orElseThrow().attemptId();
        assertThatThrownBy(() -> preparation.prepareCompletion(owner, attempt, readyReport("missing")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown captured source read");
        assertThatThrownBy(() -> preparation.prepareCompletion(owner, attempt, readyReport(READ_ID)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("monitored-baseline");
        assertThat(notebooks.getNotebook("workspace").entries()).isEmpty();
        assertThat(notebooks.getNotebook("workspace").activePreparation()).isEqualTo(admitted.attempt());
    }

    @Test
    void emptyWithoutCheckedSourcesIsRejectedAndFailedPreparationCanRetry()
    {
        Execution owner = execution("turn", false, SHA);
        var admitted = begin(owner);
        var report = new GeneratedReport(ImmutableList.of(new ScopeReport("project", EMPTY, ImmutableList.of(), ImmutableList.of(), "No data")), ImmutableList.of());
        assertThatThrownBy(() -> preparation.prepareCompletion(owner, admitted.attempt().orElseThrow().attemptId(), report))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("checked source reads");
        preparation.fail("workspace", admitted.attempt().orElseThrow(), "Read unavailable");
        var next = begin(owner);
        assertThat(next.status()).isEqualTo(OWNED);
        assertThat(next.attempt()).isNotEqualTo(admitted.attempt());
        preparation.fail("workspace", admitted.attempt().orElseThrow(), "Obsolete cleanup");
        assertThat(notebooks.getNotebook("workspace").activePreparation()).isEqualTo(next.attempt());
    }

    @Test
    void selectedHistoryIsRetainedReusedAndNewObservationInvalidatesKnowledge()
    {
        GitCommandRunner git = mock(GitCommandRunner.class);
        when(git.run(any(), any(Duration.class), anyList())).thenReturn(new GitCommandRunner.Result(0,
                "100644 blob " + SHA + "\tREADME.md\0", false));
        var historyPreparation = new RepositoryContextPreparation(notebooks, new ContextInputReader(git), workspaces);
        Execution owner = withHistory(execution("turn", false, SHA), "history-1", "Merged decision", 2);
        var plan = historyPreparation.plan(owner, ImmutableList.of("project"), ImmutableList.of("context-read:history-1"));
        var admitted = historyPreparation.begin(owner, "request", DIGEST, 0, plan);
        assertThat(admitted.status()).isEqualTo(OWNED);
        var completed = historyPreparation.prepareCompletion(owner, admitted.attempt().orElseThrow().attemptId(), historyReport("context-read:history-1", true));
        historyPreparation.complete(owner, completed);
        RepositoryNotebook saved = notebooks.getNotebook("workspace");
        assertThat(saved.sourceEvidence()).hasSize(2);
        assertThat(saved.scopes().getFirst().preparation().orElseThrow().checkedReads()).filteredOn(ContextHistoryRead.class::isInstance).hasSize(1);
        assertThat(saved.entries()).singleElement().satisfies(entry -> assertThat(entry.sourceIds()).contains("context-read:history-1"));

        Execution other = withHistory(execution("other-turn", false, SHA), "history-2", "Merged decision", 2);
        var equal = historyPreparation.plan(other, ImmutableList.of("project"), ImmutableList.of("context-read:history-2"));
        assertThat(historyPreparation.begin(other, "reuse", DIGEST, 1, equal).status()).isEqualTo(REUSED);
        var unchanged = historyPreparation.plan(other, ImmutableList.of("project"));
        assertThat(historyPreparation.begin(other, "reuse-retained", DIGEST, 1, unchanged).status()).isEqualTo(REUSED);

        Execution changed = withHistory(execution("changed-turn", false, SHA), "history-3", "Updated merged decision", 2);
        var different = historyPreparation.plan(changed, ImmutableList.of("project"), ImmutableList.of("context-read:history-3"));
        var refresh = historyPreparation.begin(changed, "changed", DIGEST, 1, different);
        assertThat(refresh.status()).isEqualTo(OWNED);
        var failed = new GeneratedReport(ImmutableList.of(new ScopeReport("project", FAILED, ImmutableList.of(),
                ImmutableList.of("context-read:history-3"), "Interpretation incomplete")), ImmutableList.of());
        historyPreparation.complete(changed, historyPreparation.prepareCompletion(changed, refresh.attempt().orElseThrow().attemptId(), failed));
        assertThat(notebooks.getNotebook("workspace").entries()).isEqualTo(saved.entries());
        var retry = historyPreparation.plan(changed, ImmutableList.of("project"), ImmutableList.of("context-read:history-3"));
        var retried = historyPreparation.begin(changed, "retry", DIGEST, 1, retry);
        assertThat(retried.status()).isEqualTo(OWNED);
        historyPreparation.complete(changed, historyPreparation.prepareCompletion(changed, retried.attempt().orElseThrow().attemptId(),
                historyReport("context-read:history-3", true)));
        assertThat(notebooks.getNotebook("workspace").sourceEvidence()).extracting(source -> source.id()).doesNotContain("context-read:history-1");
    }

    @Test
    void historyMustBeSelectedBeforeBeginAndSupportedByNonblankBaseline()
    {
        Execution owner = withHistory(execution("turn", false, SHA), "history", "Merged decision", 2);
        assertThatThrownBy(() -> preparation.plan(owner, ImmutableList.of("project"), ImmutableList.of(READ_ID)))
                .hasMessageContaining("Unknown captured history read");
        var admitted = begin(owner);
        assertThatThrownBy(() -> preparation.prepareCompletion(owner, admitted.attempt().orElseThrow().attemptId(), historyReport("context-read:history", true)))
                .hasMessageContaining("selected before preparation");
        preparation.fail("workspace", admitted.attempt().orElseThrow(), "Retry with selected history");
        var plan = preparation.plan(owner, ImmutableList.of("project"), ImmutableList.of("context-read:history"));
        var selected = preparation.begin(owner, "selected", DIGEST, 0, plan);
        assertThatThrownBy(() -> preparation.prepareCompletion(owner, selected.attempt().orElseThrow().attemptId(), historyReport("context-read:history", false)))
                .hasMessageContaining("nonblank monitored-baseline");
        var empty = new GeneratedReport(ImmutableList.of(new ScopeReport("project", EMPTY, ImmutableList.of(),
                ImmutableList.of("context-read:history"), "History was checked")), ImmutableList.of());
        assertThatThrownBy(() -> preparation.prepareCompletion(owner, selected.attempt().orElseThrow().attemptId(), empty))
                .isInstanceOf(IllegalArgumentException.class);
        Execution replacement = withHistory(execution("turn", false, SHA), "replacement", "Different observation", 2);
        assertThatThrownBy(() -> preparation.prepareCompletion(replacement, selected.attempt().orElseThrow().attemptId(),
                historyReport("context-read:replacement", true))).hasMessageContaining("selected before preparation");
        assertThat(notebooks.getNotebook("workspace").entries()).isEmpty();
    }

    @Test
    void duplicateHistorySourcesAreRejectedBeforeAdmission()
    {
        Execution owner = withHistory(withHistory(execution("turn", false, SHA), "first", "Old observation", 2), "second", "New observation", 2);
        assertThatThrownBy(() -> preparation.plan(owner, ImmutableList.of("project"), ImmutableList.of("context-read:first", "context-read:second")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicates");
        assertThat(notebooks.getNotebook("workspace").activePreparation()).isEmpty();
    }

    @Test
    void historyInputOverflowDefersWithoutStartingPreparation()
    {
        when(inputs.capture(any(), any(), any(), anyList())).thenReturn(ImmutableList.of(ContextInputFingerprint.create("project",
                IntStream.range(0, 128).boxed().collect(ImmutableMap.toImmutableMap(index -> "git:path-" + index, index -> SHA)), ImmutableList.of())));
        Execution owner = withHistory(execution("turn", false, SHA), "history", "Merged decision", 2);
        var plan = preparation.plan(owner, ImmutableList.of("project"), ImmutableList.of("context-read:history"));
        assertThat(plan.inputs()).singleElement().satisfies(input -> {
            assertThat(input.digest()).isEmpty();
            assertThat(input.gaps()).anyMatch(gap -> gap.contains("input budget"));
        });
        assertThat(preparation.begin(owner, "request", DIGEST, 0, plan).status()).isEqualTo(DEFERRED);
        assertThat(notebooks.getNotebook("workspace").activePreparation()).isEmpty();
    }

    private static GeneratedReport historyReport(String historyId, boolean baseline)
    {
        List<String> checked = baseline ? ImmutableList.of(READ_ID, historyId) : ImmutableList.of(historyId);
        return new GeneratedReport(ImmutableList.of(new ScopeReport("project", READY, ImmutableList.of("knowledge:project"), checked,
                "Recorded a historical decision alongside baseline evidence")), ImmutableList.of(new GeneratedEntry("knowledge:project", "project", "Project note",
                "Historical decision", "Verify this decision against current code", ImmutableList.of(new ContextClaim("Historical decision",
                ContextClaim.Basis.RECORDED_DECISION, ImmutableList.of(historyId))), checked)));
    }

    private static Execution withHistory(Execution execution, String suffix, String excerpt, int number)
    {
        String evidenceRef = "history-evidence:" + suffix;
        var read = new ContextHistoryRead("context-read:" + suffix, new ContextReference.PullRequest(1, REPOSITORY, number, Optional.empty()),
                ContextHistoryRead.State.MERGED, Optional.of(Instant.EPOCH), Optional.of(SHA), Hashing.sha256().hashString(excerpt, UTF_8).toString(),
                Instant.EPOCH, excerpt.getBytes(UTF_8).length, false, false, ImmutableList.of("Bounded historical observation"), Optional.of(evidenceRef));
        return new Execution(execution.workspaceId(), execution.repository(), execution.repositoryId(), execution.baselineSha(), execution.taskId(),
                execution.turnId(), execution.turnKey(), execution.reviewId(), execution.runId(),
                ImmutableList.<ContextSourceRead>builder().addAll(execution.sourceReads()).add(read).build(),
                ImmutableMap.<String, RepositoryContextPreparation.CapturedSource>builder().putAll(execution.evidence())
                        .put(evidenceRef, new RepositoryContextPreparation.CapturedSource("Retained source", excerpt)).buildOrThrow());
    }

    private RepositoryContextPreparation.Admission begin(Execution execution)
    {
        var plan = preparation.plan(execution, ImmutableList.of("project"));
        return preparation.begin(execution, "request", DIGEST, plan.notebook().revision(), plan);
    }

    private static GeneratedReport readyReport(String readId)
    {
        return new GeneratedReport(ImmutableList.of(new ScopeReport("project", READY, ImmutableList.of("knowledge:project"),
                ImmutableList.of(readId), "Extracted from the selected source")), ImmutableList.of(new GeneratedEntry("knowledge:project", "project", "Project note",
                "Useful summary", "Useful generated body", ImmutableList.of(new ContextClaim("Useful claim", ContextClaim.Basis.DOCUMENTED, ImmutableList.of(readId))),
                ImmutableList.of(readId))));
    }

    private static Execution execution(String turn, boolean blank, String sourceCommit)
    {
        ReviewContextSnapshot snapshot = new ReviewContextSnapshot("snapshot", true, 0, 1, Optional.of(SHA), Optional.of(ContextPacket.create("Initial context")),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        ReviewRun run = new ReviewRun("run", REVIEW.id(), 1, ReviewRun.Scope.FULL, "main", SHA, SHA, SHA, Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false, ImmutableMap.of(),
                ImmutableMap.of(), Optional.of(snapshot), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
        ContextRepositoryRead read = new ContextRepositoryRead(READ_ID, new ContextReference.RepositoryFile(1, REPOSITORY, "README.md", sourceCommit), SHA,
                Instant.EPOCH, 1, blank ? 0 : 1, blank ? 0 : 1, blank ? 0 : 6, false, blank ? Optional.empty() : Optional.of("E1"));
        return new Execution(REVIEW, run.withContextSourceRead(read, blank ? Optional.empty()
                : Optional.of(new ReviewEvidence("E1", "Baseline source", "Retained baseline README", "source", "Original source")), Instant.EPOCH), turn, "step-key");
    }
}
