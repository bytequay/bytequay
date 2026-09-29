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
import com.bytequay.core.context.ContextKnowledgeDraft;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.bytequay.core.context.ContextEntryKind.MEMORY;
import static com.bytequay.core.context.ContextEntryOrigin.GENERATED;
import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestRepositoryContextService
{
    private static final String TASK_ID = "33333333-3333-4333-8333-333333333333";
    private static final String WORKSPACE = "workspace";
    private static final String KEY = RepositoryContextService.SETTINGS_PREFIX + WORKSPACE;
    private static final String HEAD = "a".repeat(40);

    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final RepositoryContextService service = new RepositoryContextService(settings, mapper, workspaces, reviews, Clock.systemUTC());

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
        assertThat(service.getNotebook(WORKSPACE).useInDevelopment()).isTrue();
        assertThat(settings.get(KEY)).isEmpty();
        RepositoryNotebook saved = service.configure(WORKSPACE, 0, new EditContextConfiguration(Optional.of(false), Optional.empty(), Optional.empty()));
        String json = settings.get(KEY).orElseThrow();
        assertThat(new RepositoryContextService(settings, mapper, workspaces, reviews, Clock.systemUTC()).getNotebook(WORKSPACE)).isEqualTo(saved);
        assertThat(saved.useInReviews()).isFalse();
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(settings.get(KEY)).contains(json);
        verifyNoInteractions(reviews);
    }

    @Test
    void developmentChoiceIsIndependentAndOldNotebookDefaultsToEnabled()
            throws Exception
    {
        ObjectNode old = (ObjectNode) mapper.valueToTree(RepositoryNotebook.empty());
        old.remove("useInDevelopment");
        settings.put(KEY, mapper.writeValueAsString(old));
        assertThat(service.getNotebook(WORKSPACE).useInDevelopment()).isTrue();

        RepositoryNotebook disabled = service.configure(WORKSPACE, 0, new EditContextConfiguration(Optional.empty(), Optional.of(false), Optional.empty()));
        assertThat(disabled.useInDevelopment()).isFalse();
        assertThat(disabled.useInReviews()).isTrue();
        assertThat(service.configure(WORKSPACE, 1, new EditContextConfiguration(Optional.of(false), Optional.empty(), Optional.empty())).useInDevelopment()).isFalse();

        old.putNull("useInDevelopment");
        settings.put(KEY, mapper.writeValueAsString(old));
        assertThatThrownBy(() -> service.getNotebook(WORKSPACE)).isInstanceOf(ContextStorageException.class);
    }

    @Test
    void notebookSavedWithARemovalListStillLoadsItsNotes()
            throws Exception
    {
        RepositoryNotebook saved = service.editEntry(WORKSPACE, 0, edit("Kept note"));
        ObjectNode old = (ObjectNode) mapper.valueToTree(saved);
        old.putArray("suppressedKnowledgeIds").add("knowledge:project:removed");
        settings.put(KEY, mapper.writeValueAsString(old));

        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(saved);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}", "{\"schemaVersion\":99}"})
    void corruptContentNeverBecomesEmptyOrGetsOverwritten(String corrupt)
    {
        settings.put(KEY, corrupt);
        assertThatThrownBy(() -> service.getNotebook(WORKSPACE)).isInstanceOf(ContextStorageException.class);
        assertThatThrownBy(() -> service.configure(WORKSPACE, 0, new EditContextConfiguration(Optional.of(false), Optional.empty(), Optional.empty())))
                .isInstanceOf(ContextStorageException.class);
        assertThat(settings.get(KEY)).contains(corrupt);
    }

    @Test
    void configurationIsAtomicAndPreservesUnspecifiedFields()
    {
        EditContextConfiguration.Source source = new EditContextConfiguration.Source("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/");
        RepositoryNotebook first = service.configure(WORKSPACE, 0, new EditContextConfiguration(
                Optional.of(false), Optional.empty(), Optional.of(ImmutableList.of(source))));
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.sources().getFirst().origin()).isEqualTo(USER);
        assertThat(first.useInReviews()).isFalse();
        assertThat(first.useInDevelopment()).isTrue();
        RepositoryNotebook second = service.configure(WORKSPACE, 1, new EditContextConfiguration(Optional.of(true), Optional.empty(), Optional.empty()));
        assertThat(second.sources()).isEqualTo(first.sources());
        assertThat(second.scopes()).isEqualTo(first.scopes());
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(second);
    }

    @Test
    void discoveredConfigurationRetainsEvidenceBeforeKnowledgeAndPreservesUserContent()
    {
        ContextScope project = ContextScope.project();
        service.configure(WORKSPACE, 0, new EditContextConfiguration(Optional.of(false), Optional.empty(), Optional.empty()));
        RepositoryNotebook before = service.editEntry(WORKSPACE, 1, edit("User correction"));
        ContextSourceEvidence module = discoveryEvidence("module", "component/pom.xml", "<project/>");
        ContextSourceEvidence readme = discoveryEvidence("readme", "README.md", "Documentation: https://example.com/docs/");
        ContextScope scope = discoveredScope(module.id());
        RegisteredContextSource source = discoveredSource(readme.id());

        RepositoryNotebook saved = service.registerDiscovered(WORKSPACE, before.revision(), ImmutableList.of(scope), ImmutableList.of(source), ImmutableList.of(module, readme));

        assertThat(saved.revision()).isEqualTo(3);
        assertThat(saved.useInReviews()).isFalse();
        assertThat(saved.entries()).isEqualTo(before.entries());
        assertThat(saved.scopes()).containsExactly(project, scope);
        assertThat(saved.sources()).containsExactly(source);
        assertThat(saved.sourceEvidence()).containsExactly(module, readme);
        assertThat(new RepositoryContextService(settings, mapper, workspaces, reviews, Clock.systemUTC()).getNotebook(WORKSPACE)).isEqualTo(saved);
        assertThatThrownBy(() -> service.registerDiscovered(WORKSPACE, before.revision(), ImmutableList.of(scope), ImmutableList.of(), ImmutableList.of()))
                .isInstanceOf(ContextConflictException.class);
        assertThatThrownBy(() -> service.registerDiscovered(WORKSPACE, saved.revision(), ImmutableList.of(scope), ImmutableList.of(), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scope identity already exists");
        assertThatThrownBy(() -> service.registerDiscovered(WORKSPACE, saved.revision(), ImmutableList.of(), ImmutableList.of(source), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source identity already exists");
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(saved);
        verifyNoInteractions(reviews);
    }

    @Test
    void discoveredSharedScopeKeepsExistingNotesAndMatchesAnotherConnector()
    {
        ContextSourceEvidence icebergSource = discoveryEvidence("iceberg", "plugin/trino-iceberg/pom.xml", "Iceberg connector");
        ContextScope iceberg = new ContextScope("path:plugin/trino-iceberg/", "Iceberg", new ContextMatch.Selected(
                ImmutableList.of("plugin/trino-iceberg/"), ImmutableList.of()), true, Optional.empty(), Optional.of(icebergSource.id()));
        RepositoryNotebook initial = service.registerDiscovered(WORKSPACE, 0, ImmutableList.of(iceberg), ImmutableList.of(), ImmutableList.of(icebergSource));
        RepositoryNotebook before = service.editEntry(WORKSPACE, initial.revision(), new EditContextEntry(
                ContextEntry.knowledgeId(iceberg.id()), iceberg.id(), "Iceberg snapshots", "Iceberg-only rule", "Existing approved knowledge",
                ImmutableList.of(), ImmutableList.of(icebergSource.id())));
        ContextSourceEvidence sharedSource = discoveryEvidence("shared", "pom.xml", "Shared connector modules");
        ContextScope shared = new ContextScope("discovery:lakehouse", "Lakehouse connectors", new ContextMatch.Selected(
                ImmutableList.of("plugin/trino-iceberg/", "plugin/trino-delta-lake/", "lib/trino-filesystem/"), ImmutableList.of()),
                true, Optional.empty(), Optional.of(sharedSource.id()));

        RepositoryNotebook registered = service.registerDiscovered(WORKSPACE, before.revision(),
                ImmutableList.of(shared), ImmutableList.of(), ImmutableList.of(sharedSource));

        assertThat(registered.scopes()).containsExactly(ContextScope.project(), iceberg, shared);
        assertThat(registered.entries()).isEqualTo(before.entries());
        RepositoryNotebook saved = service.editEntry(WORKSPACE, registered.revision(), new EditContextEntry(
                ContextEntry.knowledgeId(shared.id()), shared.id(), "Shared storage behavior", "Connector storage contracts", "Approved shared knowledge",
                ImmutableList.of(), ImmutableList.of(sharedSource.id())));
        assertThat(saved.entries()).containsAll(before.entries());
        ContextNotebookLookup lookup = new ContextNotebookLookup(service, new RepositoryContextMatcher(), new ContextNoteReader(service), mapper);
        ContextNotebookLookup.SearchResult found = lookup.search(WORKSPACE, new ContextNotebookLookup.SearchQuery("",
                ImmutableList.of("plugin/trino-delta-lake/src/main/java/DeltaLakeMetadata.java")));
        assertThat(found.hits()).extracting(ContextNotebookLookup.Hit::entryId).containsExactly(ContextEntry.knowledgeId(shared.id()));
        assertThat(found.hits().getFirst().scopeId()).isEqualTo(shared.id());
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(saved);
    }

    @Test
    void sourceEvidenceStaysOnlyUntilTheSourceChanges()
    {
        ContextSourceEvidence module = discoveryEvidence("module", "component/pom.xml", "<project/>");
        ContextSourceEvidence readme = discoveryEvidence("readme", "README.md", "Documentation: https://example.com/docs/");
        RepositoryNotebook initial = service.registerDiscovered(WORKSPACE, 0, ImmutableList.of(discoveredScope(module.id())),
                ImmutableList.of(discoveredSource(readme.id())), ImmutableList.of(module, readme));
        EditContextConfiguration.Source unchanged = new EditContextConfiguration.Source("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/");

        RepositoryNotebook edited = service.configure(WORKSPACE, initial.revision(), new EditContextConfiguration(Optional.empty(), Optional.empty(),
                Optional.of(ImmutableList.of(unchanged))));

        assertThat(edited.scopes()).isEqualTo(initial.scopes());
        assertThat(edited.sources()).isEqualTo(initial.sources());
        assertThat(edited.sourceEvidence()).containsExactly(module, readme);

        EditContextConfiguration.Source moved = new EditContextConfiguration.Source("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://example.com/new-docs/");
        RepositoryNotebook cleared = service.configure(WORKSPACE, edited.revision(), new EditContextConfiguration(Optional.empty(), Optional.empty(),
                Optional.of(ImmutableList.of(moved))));

        assertThat(cleared.scopes().get(1).discoveryEvidenceRef()).contains(module.id());
        assertThat(cleared.sources().getFirst().discoveryEvidenceRef()).isEmpty();
        assertThat(cleared.sources().getFirst().origin()).isEqualTo(USER);
        assertThat(cleared.sourceEvidence()).containsExactly(module);
    }

    @Test
    void discoveryRejectsMissingEvidenceAndActivePreparationWithoutChangingNotebook()
            throws Exception
    {
        assertThatThrownBy(() -> service.registerDiscovered(WORKSPACE, 0, ImmutableList.of(discoveredScope("missing")), ImmutableList.of(), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Discovery evidence is missing");
        assertThat(settings.get(KEY)).isEmpty();
        RepositoryNotebook active = seedPreparation(ContextPreparationResult.Outcome.EMPTY);
        ContextSourceEvidence module = discoveryEvidence("module", "component/pom.xml", "<project/>");

        assertThatThrownBy(() -> service.registerDiscovered(WORKSPACE, active.revision(), ImmutableList.of(discoveredScope(module.id())),
                ImmutableList.of(), ImmutableList.of(module))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("active preparation");

        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(active);
    }

    @Test
    void discoveryChecksCurrentMonitoredRepositoryBeforeSaving()
    {
        for (ContextReference.RepositoryFile reference : ImmutableList.of(
                new ContextReference.RepositoryFile(2, RepoRef.of("example", "repository"), "component/pom.xml", HEAD),
                new ContextReference.RepositoryFile(1, RepoRef.of("other", "repository"), "component/pom.xml", HEAD))) {
            ContextSourceEvidence wrong = new ContextSourceEvidence("module", reference, "Captured module", "<project/>",
                    ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(Instant.EPOCH), Optional.of(HEAD), Optional.of(HEAD), false);
            assertThatThrownBy(() -> service.registerDiscovered(WORKSPACE, 0, ImmutableList.of(discoveredScope(wrong.id())), ImmutableList.of(), ImmutableList.of(wrong)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another monitored repository");
            assertThat(settings.get(KEY)).isEmpty();
        }
    }

    @Test
    void staleConcurrentEditorsCannotOverwriteEachOther()
            throws Exception
    {
        RepositoryContextService other = new RepositoryContextService(settings, mapper, workspaces, reviews, Clock.systemUTC());
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
    void editingThenDisablingGeneratedKnowledgeKeepsUserOwnership()
            throws Exception
    {
        seedGenerated();
        RepositoryNotebook edited = service.editEntry(WORKSPACE, 1, edit("Corrected lesson", "source"));
        assertThat(edited.entries().getFirst().origin()).isEqualTo(USER);
        RepositoryNotebook disabled = service.setEntryEnabled(WORKSPACE, 2, "knowledge:project", false);
        assertThat(disabled.entries().getFirst().origin()).isEqualTo(USER);
        assertThat(disabled.entries().getFirst().enabled()).isFalse();
        assertThat(disabled.entries().getFirst().version()).isGreaterThan(edited.entries().getFirst().version());
    }

    @Test
    void taskMemoryProposalStaysPendingUntilApprovalAndRetainsKindAfterEditing()
    {
        String id = "draft:task:" + "a".repeat(32) + ":" + "b".repeat(32);
        ContextSourceEvidence source = discoveryEvidence("task-read:1", "README.md", "1  Evidence");
        RepositoryContextService.TaskDraftProposal proposal = new RepositoryContextService.TaskDraftProposal(id, TASK_ID, MEMORY,
                "project", Optional.empty(), Optional.empty(), "Finding", "Checkpoint invariant", "Keep the older layout",
                HEAD, ImmutableList.of(source));
        RepositoryNotebook pending = service.proposeTaskDraft(WORKSPACE, 0, proposal);
        assertThat(pending.entries()).isEmpty();
        assertThat(pending.pendingDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.entry().kind()).isEqualTo(MEMORY);
            assertThat(draft.entry().id()).startsWith("lesson:project:task-");
            assertThat(draft.entry().origin()).isEqualTo(GENERATED);
        });
        assertThat(service.proposeTaskDraft(WORKSPACE, pending.revision(), proposal)).isEqualTo(pending);
        RepositoryNotebook edited = service.editDraft(WORKSPACE, pending.revision(), id, "Edited", "Edited summary", "Edited body",
                ImmutableList.of());
        assertThat(edited.pendingDrafts().getFirst().entry().kind()).isEqualTo(MEMORY);
        ContextTimes.Stamp proposed = service.getTimes(WORKSPACE).drafts().get(id);
        assertThat(proposed.taskId()).contains(TASK_ID);
        RepositoryNotebook approved = service.approveDraft(WORKSPACE, edited.revision(), id);
        assertThat(approved.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.kind()).isEqualTo(MEMORY);
            assertThat(entry.origin()).isEqualTo(USER);
        });
        ContextTimes times = service.getTimes(WORKSPACE);
        assertThat(times.drafts()).isEmpty();
        ContextEntry active = approved.entries().getFirst();
        assertThat(times.entries().get(ContextTimes.key(active.id(), active.version())).taskId()).contains(TASK_ID);
    }

    @Test
    void taskProposalRejectsUncapturedOrForeignRepositoryEvidence()
    {
        String id = "draft:task:" + "a".repeat(32) + ":" + "b".repeat(32);
        ContextSourceEvidence source = discoveryEvidence("task-read:1", "README.md", "1  Evidence");
        ContextSourceEvidence reported = new ContextSourceEvidence(source.id(), source.reference(), source.provenance(),
                source.excerpt(), ContextSourceEvidence.Capture.AGENT_REPORTED, source.retrievedAt(), source.contentHash(), source.version(), false);
        assertThatThrownBy(() -> service.proposeTaskDraft(WORKSPACE, 0, new RepositoryContextService.TaskDraftProposal(id, TASK_ID, MEMORY,
                "project", Optional.empty(), Optional.empty(), "Finding", "Summary", "Body", HEAD, ImmutableList.of(reported))))
                .hasMessageContaining("application-captured");
        ContextSourceEvidence foreign = new ContextSourceEvidence(source.id(),
                new ContextReference.RepositoryFile(2, RepoRef.of("example", "other"), "README.md", HEAD), source.provenance(),
                source.excerpt(), source.capture(), source.retrievedAt(), source.contentHash(), source.version(), false);
        assertThatThrownBy(() -> service.proposeTaskDraft(WORKSPACE, 0, new RepositoryContextService.TaskDraftProposal(id, TASK_ID, MEMORY,
                "project", Optional.empty(), Optional.empty(), "Finding", "Summary", "Body", HEAD, ImmutableList.of(foreign))))
                .hasMessageContaining("outside its committed repository HEAD");
        assertThat(service.getNotebook(WORKSPACE).pendingDrafts()).isEmpty();
    }

    @Test
    void draftEditRemainsPendingAndApprovalReplacesOnlyItsExactActiveVersion()
            throws Exception
    {
        seedGenerated();
        RepositoryNotebook initial = service.getNotebook(WORKSPACE);
        ContextKnowledgeDraft draft = replacementDraft(initial, "draft:replacement");
        RepositoryNotebook pending = service.savePreparation(WORKSPACE, initial, new RepositoryNotebook(1, 2, true, true,
                initial.sources(), initial.scopes(), initial.entries(), ImmutableList.of(draft),
                initial.sourceEvidence(), Optional.empty()));
        assertThat(pending.entries()).isEqualTo(initial.entries());
        assertThat(pending.pendingDrafts()).singleElement().satisfies(value -> assertThat(value.entry().origin()).isEqualTo(GENERATED));

        RepositoryNotebook edited = service.editDraft(WORKSPACE, pending.revision(), draft.id(), "Edited title", "Edited summary", "Edited body",
                draft.entry().claims());
        assertThat(edited.entries()).isEqualTo(initial.entries());
        assertThat(edited.pendingDrafts()).singleElement().satisfies(value -> {
            assertThat(value.entry().body()).isEqualTo("Edited body");
            assertThat(value.entry().origin()).isEqualTo(USER);
            assertThat(value.entry().sourceIds()).isEqualTo(draft.entry().sourceIds());
        });
        RepositoryNotebook approved = service.approveDraft(WORKSPACE, edited.revision(), draft.id());
        assertThat(approved.pendingDrafts()).isEmpty();
        assertThat(approved.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.body()).isEqualTo("Edited body");
            assertThat(entry.origin()).isEqualTo(USER);
            assertThat(entry.version()).isEqualTo(approved.revision());
        });
    }

    @Test
    void disabledActiveNoteCannotBeReplacedByItsPendingDraft()
            throws Exception
    {
        seedGenerated();
        RepositoryNotebook initial = service.getNotebook(WORKSPACE);
        ContextKnowledgeDraft draft = replacementDraft(initial, "draft:replacement");
        service.savePreparation(WORKSPACE, initial, new RepositoryNotebook(1, 2, true, true, initial.sources(), initial.scopes(),
                initial.entries(), ImmutableList.of(draft), initial.sourceEvidence(), Optional.empty()));
        RepositoryNotebook disabled = service.setEntryEnabled(WORKSPACE, 2, "knowledge:project", false);
        assertThatThrownBy(() -> service.approveDraft(WORKSPACE, disabled.revision(), draft.id()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed since this proposal");
    }

    @Test
    void dismissingAnUnapprovedNewTopicPrunesItsOtherwiseUnusedEvidence()
    {
        ContextSourceEvidence source = new ContextSourceEvidence("draft-source", new ContextReference.Document("docs", "https://example.com/docs/page"),
                "Captured documentation", "Topic evidence", ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.EPOCH), Optional.empty(), Optional.empty(), false);
        ContextEntry entry = new ContextEntry("knowledge:project:new-topic", KNOWLEDGE, "project", "New topic", "Summary", "Body",
                ImmutableList.of(), ImmutableList.of(source.id()), GENERATED, true, 1);
        ContextKnowledgeDraft draft = new ContextKnowledgeDraft("draft:new-topic", entry, Optional.empty(), HEAD, "Proposed from documentation");
        RepositoryNotebook empty = service.getNotebook(WORKSPACE);
        RepositoryNotebook pending = service.savePreparation(WORKSPACE, empty, new RepositoryNotebook(1, 1, true, true,
                empty.sources(), empty.scopes(), empty.entries(), ImmutableList.of(draft),
                ImmutableList.of(source), Optional.empty()));
        assertThat(pending.sourceEvidence()).containsExactly(source);
        RepositoryNotebook dismissed = service.dismissDraft(WORKSPACE, pending.revision(), draft.id());
        assertThat(dismissed.pendingDrafts()).isEmpty();
        assertThat(dismissed.entries()).isEmpty();
        assertThat(dismissed.sourceEvidence()).isEmpty();
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
        assertThat(new RepositoryContextService(settings, mapper, workspaces, reviews, Clock.systemUTC()).getNotebook(WORKSPACE)).isEqualTo(saved);
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
        service.configure(WORKSPACE, 0, new EditContextConfiguration(Optional.of(false), Optional.empty(), Optional.empty()));
        settings.put(ContextBuildStore.PREFIX + WORKSPACE, "retained learning request");
        service.deleteWorkspaceData(WORKSPACE);
        service.deleteWorkspaceData(WORKSPACE);
        when(workspaces.findById(WORKSPACE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.editEntry(WORKSPACE, 0, edit("Orphan"))).isInstanceOf(ContextNotFoundException.class);
        assertThat(settings.get(KEY)).isEmpty();
        assertThat(settings.get(ContextBuildStore.PREFIX + WORKSPACE)).isEmpty();
    }

    @Test
    void configurationRejectsUnsafeDocumentationAndInvalidPathRules()
    {
        EditContextConfiguration.Source unsafe = new EditContextConfiguration.Source("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://localhost/docs/");
        assertThatThrownBy(() -> service.configure(WORKSPACE, 0, new EditContextConfiguration(Optional.empty(), Optional.empty(), Optional.of(ImmutableList.of(unsafe)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextMatch.Selected(ImmutableList.of("plugin/../"), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextMatch.Selected(ImmutableList.of(), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.get(KEY)).isEmpty();
    }

    private static AgentReview review(String workspaceId)
    {
        return new AgentReview("review", workspaceId, "example/repository", OptionalInt.of(1), OptionalLong.empty(), "task", Optional.empty(), AgentReview.State.OPEN,
                false, Optional.empty(), Optional.of(HEAD), Optional.of(HEAD), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty());
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
                ImmutableList.of(new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/", USER, Optional.empty())),
                ImmutableList.of(ContextScope.project()), ImmutableList.of(generated), ImmutableList.of(source), Optional.empty());
        settings.put(KEY, mapper.writeValueAsString(notebook));
    }

    private static ContextKnowledgeDraft replacementDraft(RepositoryNotebook initial, String draftId)
    {
        ContextEntry prior = initial.entries().getFirst();
        ContextEntry proposed = new ContextEntry(prior.id(), KNOWLEDGE, prior.scopeId(), "New title", "New summary", "New generated body",
                prior.claims(), prior.sourceIds(), GENERATED, true, 2);
        return new ContextKnowledgeDraft(draftId, proposed, Optional.of(prior.version()), HEAD, "Refreshed from the retained source");
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
        ContextScope scope = new ContextScope("project", "Project", new ContextMatch.Repository(), true, Optional.of(result), Optional.empty());
        ContextEntry generated = new ContextEntry("knowledge:project", KNOWLEDGE, "project", "Project", "Summary", "Generated body",
                ImmutableList.of(), ImmutableList.of(id), GENERATED, true, 1);
        ActiveContextPreparation active = new ActiveContextPreparation("active", "request", "sha256:" + "a".repeat(64),
                "task", "turn", "turn-key", Optional.of("review"), Optional.of("run"), 1, ImmutableList.of(input));
        RepositoryNotebook notebook = new RepositoryNotebook(1, 1, true, ImmutableList.of(), ImmutableList.of(scope),
                outcome == ContextPreparationResult.Outcome.READY ? ImmutableList.of(generated) : ImmutableList.of(), ImmutableList.of(source), Optional.of(active));
        settings.put(KEY, mapper.writeValueAsString(notebook));
        return notebook;
    }

    private static EditContextEntry edit(String body, String... sources)
    {
        return new EditContextEntry("knowledge:project", "project", "Project", "Summary", body, ImmutableList.of(), ImmutableList.copyOf(sources));
    }

    private static ContextScope discoveredScope(String evidenceRef)
    {
        return new ContextScope("path:component/", "Component", new ContextMatch.Selected(ImmutableList.of("component/"), ImmutableList.of()),
                true, Optional.empty(), Optional.of(evidenceRef));
    }

    private static RegisteredContextSource discoveredSource(String evidenceRef)
    {
        return new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/", GENERATED, Optional.of(evidenceRef));
    }

    private static ContextSourceEvidence discoveryEvidence(String id, String path, String excerpt)
    {
        return new ContextSourceEvidence(id, new ContextReference.RepositoryFile(1, RepoRef.of("example", "repository"), path, HEAD),
                "Captured repository configuration evidence", excerpt, ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.EPOCH), Optional.of(HEAD), Optional.of(HEAD), false);
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
