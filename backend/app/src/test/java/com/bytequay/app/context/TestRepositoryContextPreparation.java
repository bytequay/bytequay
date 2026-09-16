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
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextScope;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
