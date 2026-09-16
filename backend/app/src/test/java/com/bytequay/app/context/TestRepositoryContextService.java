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

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.context.ActiveContextPreparation;
import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.bytequay.core.context.ContextEntryOrigin.GENERATED;
import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestRepositoryContextService
{
    private static final String WORKSPACE = "workspace";
    private static final String KEY = RepositoryContextService.SETTINGS_PREFIX + WORKSPACE;
    private static final String HEAD = "a".repeat(40);

    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final RepositoryContextService service = new RepositoryContextService(settings, mapper, workspaces, reviews);

    @BeforeEach
    void workspaceExists()
    {
        RepoRef repository = RepoRef.of("example", "repository");
        Workspace workspace = new Workspace(
                WORKSPACE, "Repository", 1, repository, Optional.empty(), Optional.empty(), 1, repository, Workspace.Topology.DIRECT,
                "main", "https://github.com/example/repository.git", Optional.empty(), Optional.empty(), "/tmp/context-test",
                Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH);
        when(workspaces.findById(WORKSPACE)).thenReturn(Optional.of(workspace));
    }

    @Test
    void absentNotebookIsEnabledAndReadsNeverWrite()
    {
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(RepositoryNotebook.empty());
        assertThat(settings.get(KEY)).isEmpty();
        RepositoryNotebook saved = service.setUseInReviews(WORKSPACE, 0, false);
        String json = settings.get(KEY).orElseThrow();
        assertThat(new RepositoryContextService(settings, mapper, workspaces, reviews).getNotebook(WORKSPACE)).isEqualTo(saved);
        assertThat(saved.useInReviews()).isFalse();
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(settings.get(KEY)).contains(json);
        verifyNoInteractions(reviews);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}", "{\"schemaVersion\":99}"})
    void corruptContentNeverBecomesEmptyOrGetsOverwritten(String corrupt)
    {
        settings.put(KEY, corrupt);
        assertThatThrownBy(() -> service.getNotebook(WORKSPACE)).isInstanceOf(ContextStorageException.class);
        assertThatThrownBy(() -> service.setUseInReviews(WORKSPACE, 0, false)).isInstanceOf(ContextStorageException.class);
        assertThat(settings.get(KEY)).contains(corrupt);
    }

    @Test
    void configurationIsAtomicAndPreservesUnspecifiedFields()
    {
        ContextScope iceberg = new ContextScope("path:plugin/iceberg/", "Iceberg",
                new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of("iceberg")), true, Optional.empty());
        EditContextConfiguration.Source source = new EditContextConfiguration.Source("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/");
        RepositoryNotebook first = service.configure(WORKSPACE, 0, new EditContextConfiguration(
                Optional.of(false), Optional.of(ImmutableList.of(source)), Optional.of(ImmutableList.of(ContextScope.project(), iceberg))));
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.sources().getFirst().origin()).isEqualTo(USER);
        assertThat(first.useInReviews()).isFalse();
        RepositoryNotebook second = service.setUseInReviews(WORKSPACE, 1, true);
        assertThat(second.sources()).isEqualTo(first.sources());
        assertThat(second.scopes()).isEqualTo(first.scopes());
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(second);
        assertThatThrownBy(() -> service.configure(WORKSPACE, 2, new EditContextConfiguration(
                Optional.empty(), Optional.empty(), Optional.of(ImmutableList.of(ContextScope.project())))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Disable existing scopes");
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(second);
    }

    @Test
    void scopeConfigurationPreservesServerPreparationAndOwnershipInsteadOfAcceptingCallerMetadata()
            throws Exception
    {
        RepositoryNotebook initial = seedPreparation(ContextPreparationResult.Outcome.EMPTY);
        ContextPreparationResult forged = new ContextPreparationResult("forged", initial.scopes().getFirst().preparation().orElseThrow().inputFingerprint(),
                ContextPreparationResult.Outcome.FAILED, ImmutableList.of(), ImmutableList.of(), "Caller-provided preparation");
        ContextScope edited = new ContextScope("project", "Edited project title", new ContextMatch.Repository(), false, Optional.of(forged));
        RepositoryNotebook updated = service.configure(WORKSPACE, 1,
                new EditContextConfiguration(Optional.empty(), Optional.empty(), Optional.of(ImmutableList.of(edited))));

        assertThat(updated.revision()).isEqualTo(2);
        assertThat(updated.scopes().getFirst().title()).isEqualTo("Edited project title");
        assertThat(updated.scopes().getFirst().enabled()).isFalse();
        assertThat(updated.scopes().getFirst().preparation()).isEqualTo(initial.scopes().getFirst().preparation());
        assertThat(updated.activePreparation()).isEqualTo(initial.activePreparation());
        assertThat(updated.sourceEvidence()).isEqualTo(initial.sourceEvidence());
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(updated);
    }

    @ParameterizedTest
    @EnumSource(value = ContextPreparationResult.Outcome.class, names = {"READY", "EMPTY"})
    void resetClearsCompletedMissingKnowledgeAndUnusedEvidenceWithoutRecreatingContent(ContextPreparationResult.Outcome outcome)
            throws Exception
    {
        RepositoryNotebook initial = seedPreparation(outcome);
        RepositoryNotebook missing = initial;
        if (outcome == ContextPreparationResult.Outcome.READY) {
            RepositoryNotebook edited = service.editEntry(WORKSPACE, 1, edit("User correction", "context-read:source"));
            RepositoryNotebook retained = service.resetSuppression(WORKSPACE, edited.revision(), ImmutableList.of("project"));
            assertThat(retained).isEqualTo(edited);
            assertThat(retained.entries().getFirst().origin()).isEqualTo(USER);
            missing = service.removeEntry(WORKSPACE, retained.revision(), "knowledge:project");
            assertThat(missing.suppressedKnowledgeIds()).containsExactly("knowledge:project");
            assertThat(missing.scopes().getFirst().preparation()).isEqualTo(initial.scopes().getFirst().preparation());
            assertThat(missing.sourceEvidence()).isEqualTo(initial.sourceEvidence());
        }
        RepositoryNotebook reset = service.resetSuppression(WORKSPACE, missing.revision(), ImmutableList.of("project"));
        assertThat(reset.revision()).isEqualTo(missing.revision() + 1);
        assertThat(reset.scopes().getFirst().preparation()).isEmpty();
        assertThat(reset.entries()).isEmpty();
        assertThat(reset.suppressedKnowledgeIds()).isEmpty();
        assertThat(reset.sourceEvidence()).isEmpty();
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(reset);
    }

    @Test
    void staleConcurrentEditorsCannotOverwriteEachOther()
            throws Exception
    {
        RepositoryContextService other = new RepositoryContextService(settings, mapper, workspaces, reviews);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return attemptEdit(service, "first");
            });
            var second = executor.submit(() -> {
                start.await();
                return attemptEdit(other, "second");
            });
            start.countDown();
            assertThat(ImmutableList.of(first.get(), second.get())).containsExactlyInAnyOrder("saved", "conflict");
        }
        assertThat(service.getNotebook(WORKSPACE).revision()).isEqualTo(1);
        assertThat(service.getNotebook(WORKSPACE).entries()).hasSize(1);
    }

    @Test
    void editingThenRemovingGeneratedKnowledgePreservesSuppression()
            throws Exception
    {
        seedGenerated();
        RepositoryNotebook edited = service.editEntry(WORKSPACE, 1, edit("Corrected lesson", "source"));
        assertThat(edited.entries().getFirst().origin()).isEqualTo(USER);
        RepositoryNotebook disabled = service.setEntryEnabled(WORKSPACE, 2, "knowledge:project", false);
        assertThat(disabled.entries().getFirst().origin()).isEqualTo(USER);
        assertThat(disabled.entries().getFirst().enabled()).isFalse();
        RepositoryNotebook removed = service.removeEntry(WORKSPACE, 3, "knowledge:project");
        assertThat(removed.entries()).isEmpty();
        assertThat(removed.suppressedKnowledgeIds()).containsExactly("knowledge:project");
        assertThat(removed.sourceEvidence()).isEmpty();
        assertThatThrownBy(() -> service.editEntry(WORKSPACE, 4, edit("Resurrected")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Reset");
        service.resetSuppression(WORKSPACE, 4, ImmutableList.of("project"));
        RepositoryNotebook recreated = service.editEntry(WORKSPACE, 5, edit("Explicitly recreated"));
        assertThat(recreated.entries().getFirst().version()).isGreaterThan(edited.entries().getFirst().version());
        assertThat(recreated.sourceEvidence()).isEmpty();
    }

    @Test
    void missingSourcesAndMultibyteOverflowLeaveSavedContentIntact()
    {
        RepositoryNotebook current = service.editEntry(WORKSPACE, 0, edit("Valid"));
        assertThatThrownBy(() -> service.editEntry(WORKSPACE, 1, edit("Missing evidence", "missing")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("evidence");
        assertThatThrownBy(() -> service.editEntry(WORKSPACE, 1, edit("你".repeat(2731))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UTF-8");
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(current);
        assertThat(service.editEntry(WORKSPACE, 1, edit("你".repeat(2730))).entries().getFirst().body()).hasSize(2730);
    }

    @Test
    void savedLessonCopiesOnlySameWorkspaceRunEvidenceAndRetainsItAfterSourceDisappears()
    {
        when(reviews.findReview("review")).thenReturn(Optional.of(review(WORKSPACE)));
        ReviewRun run = run(ImmutableMap.of("e1",
                new ReviewEvidence("e1", "base source " + "b".repeat(40), "Frozen source read", "Actual retained contract", "Explanation")));
        when(reviews.findRun("run")).thenReturn(Optional.of(run));
        RepositoryNotebook saved = service.saveLesson(WORKSPACE, 0, lesson("review", "run", "e1"));
        assertThat(saved.entries().getFirst().origin()).isEqualTo(USER);
        assertThat(saved.sourceEvidence().getFirst().excerpt()).isEqualTo("Actual retained contract");
        assertThat(saved.sourceEvidence().getFirst().provenance())
                .contains("Copied stored review evidence", "b".repeat(40), "Frozen source read", "Explanation", "Reviewed head", HEAD);
        assertThat(saved.sourceEvidence().getFirst().version()).isEmpty();
        assertThat(saved.sourceEvidence().getFirst().reference()).isEqualTo(new ContextReference.Review("review", "run", Optional.of("e1"), Optional.empty()));
        when(reviews.findRun("run")).thenReturn(Optional.empty());
        assertThat(new RepositoryContextService(settings, mapper, workspaces, reviews).getNotebook(WORKSPACE)).isEqualTo(saved);
        when(reviews.findReview("review")).thenReturn(Optional.of(review("another-workspace")));
        assertThatThrownBy(() -> service.saveLesson(WORKSPACE, 1, lesson("review", "run", "e1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another workspace");
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(saved);
    }

    @Test
    void discussionSourceMustBelongToSelectedRun()
    {
        when(reviews.findReview("review")).thenReturn(Optional.of(review(WORKSPACE)));
        when(reviews.findRun("run")).thenReturn(Optional.of(run(ImmutableMap.of())));
        ReviewMessage message = new ReviewMessage("message", "review", Optional.empty(), Optional.empty(), ReviewMessage.Author.USER,
                "Discussion", 1, "different-run", Optional.empty(), Optional.empty(), 0, Instant.EPOCH);
        when(reviews.listMessages("review")).thenReturn(ImmutableList.of(message));
        SaveContextLesson lesson = new SaveContextLesson("project", "Lesson", "Summary", "Body", "review", "run", ImmutableList.of(), Optional.of("message"));
        assertThatThrownBy(() -> service.saveLesson(WORKSPACE, 0, lesson)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another run");
        assertThat(settings.get(KEY)).isEmpty();
    }

    @Test
    void removedWorkspaceCannotRecreateNotebookAndDeletionIsIdempotent()
    {
        service.setUseInReviews(WORKSPACE, 0, false);
        service.deleteWorkspaceData(WORKSPACE);
        service.deleteWorkspaceData(WORKSPACE);
        when(workspaces.findById(WORKSPACE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.editEntry(WORKSPACE, 0, edit("Orphan"))).isInstanceOf(ContextNotFoundException.class);
        assertThat(settings.get(KEY)).isEmpty();
    }

    @Test
    void configurationRejectsUnsafeDocumentationAndInvalidPathRules()
    {
        EditContextConfiguration.Source unsafe = new EditContextConfiguration.Source("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://localhost/docs/");
        assertThatThrownBy(() -> service.configure(WORKSPACE, 0, new EditContextConfiguration(Optional.empty(), Optional.of(ImmutableList.of(unsafe)), Optional.empty())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextMatch.Selected(ImmutableList.of("plugin/../"), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextMatch.Selected(ImmutableList.of(), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.get(KEY)).isEmpty();
    }

    private static AgentReview review(String workspaceId)
    {
        return new AgentReview("review", workspaceId, "example/repository", 1, OptionalLong.empty(), "task", AgentReview.State.OPEN,
                false, Optional.empty(), Optional.of(HEAD), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
    }

    private static ReviewRun run(Map<String, ReviewEvidence> evidence)
    {
        return new ReviewRun("run", "review", 1, ReviewRun.Scope.FULL, "main", "b".repeat(40), "b".repeat(40), HEAD, Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(), false, ImmutableMap.of(), evidence, Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
    }

    private void seedGenerated()
            throws Exception
    {
        ContextSourceEvidence source = new ContextSourceEvidence("source", new ContextReference.Document("docs", "https://example.com/docs/page"),
                "Captured documentation", "Original contract", ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(Instant.EPOCH), Optional.empty(), Optional.empty(), false);
        ContextEntry generated = new ContextEntry("knowledge:project", KNOWLEDGE, "project", "Project", "Summary", "Generated body",
                ImmutableList.of(new ContextClaim("Contract", ContextClaim.Basis.DOCUMENTED, ImmutableList.of("source"))), ImmutableList.of("source"), GENERATED, true, 1);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 1, true,
                ImmutableList.of(new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/", USER)),
                ImmutableList.of(ContextScope.project()), ImmutableList.of(generated), ImmutableList.of(), ImmutableList.of(source), Optional.empty());
        settings.put(KEY, mapper.writeValueAsString(notebook));
    }

    private RepositoryNotebook seedPreparation(ContextPreparationResult.Outcome outcome)
            throws Exception
    {
        String id = "context-read:source";
        ContextReference.RepositoryFile reference = new ContextReference.RepositoryFile(1, RepoRef.of("example", "repository"), "README.md", HEAD);
        ContextRepositoryRead read = new ContextRepositoryRead(id, reference, HEAD, Instant.EPOCH, 1, 1, 1, 7, false, Optional.of(id));
        ContextSourceEvidence source = new ContextSourceEvidence(id, reference, "Captured baseline", "Excerpt", ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.EPOCH), Optional.of(HEAD), Optional.empty(), false);
        ContextInputFingerprint input = ContextInputFingerprint.create("project", ImmutableMap.of("git:README.md", "100644 blob " + HEAD), ImmutableList.of());
        ContextPreparationResult result = new ContextPreparationResult("completed", input, outcome,
                outcome == ContextPreparationResult.Outcome.READY ? ImmutableList.of("knowledge:project") : ImmutableList.of(), ImmutableList.of(read), "Checked baseline source");
        ContextScope scope = new ContextScope("project", "Project", new ContextMatch.Repository(), true, Optional.of(result));
        ContextEntry generated = new ContextEntry("knowledge:project", KNOWLEDGE, "project", "Project", "Summary", "Generated body",
                ImmutableList.of(), ImmutableList.of(id), GENERATED, true, 1);
        ActiveContextPreparation active = new ActiveContextPreparation("active", "request", "sha256:" + "a".repeat(64),
                "task", "turn", "turn-key", "review", "run", 1, ImmutableList.of(input));
        RepositoryNotebook notebook = new RepositoryNotebook(1, 1, true, ImmutableList.of(), ImmutableList.of(scope),
                outcome == ContextPreparationResult.Outcome.READY ? ImmutableList.of(generated) : ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(source), Optional.of(active));
        settings.put(KEY, mapper.writeValueAsString(notebook));
        return notebook;
    }

    private static EditContextEntry edit(String body, String... sources)
    {
        return new EditContextEntry("knowledge:project", "project", "Project", "Summary", body, ImmutableList.of(), ImmutableList.copyOf(sources));
    }

    private static SaveContextLesson lesson(String review, String run, String evidence)
    {
        return new SaveContextLesson("project", "Lesson", "Summary", "Conditional lesson", review, run, ImmutableList.of(evidence), Optional.empty());
    }

    private static String attemptEdit(RepositoryContextService service, String text)
    {
        try {
            service.editEntry(WORKSPACE, 0, edit(text));
            return "saved";
        }
        catch (ContextConflictException ignored) {
            return "conflict";
        }
    }
}
