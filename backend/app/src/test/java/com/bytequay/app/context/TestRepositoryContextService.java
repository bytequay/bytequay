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

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ActiveContextPreparation;
import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.context.ReviewContextSnapshot;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.bytequay.core.context.ContextEntryKind.MEMORY;
import static com.bytequay.core.context.ContextEntryOrigin.GENERATED;
import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestRepositoryContextService
{
    private static final String TASK_ID = "33333333-3333-4333-8333-333333333333";
    private static final String OTHER_TASK_ID = "44444444-4444-4444-8444-444444444444";
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
        ContextNotebookLookup lookup = new ContextNotebookLookup(service, new RepositoryContextMatcher(), new ContextNoteReader(service,
                new ContextSourceChecker(new GitCommandRunner(), mock(WorkspaceRepositoryService.class))), mapper);
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
    void taskNoteSavesDirectlyAndItsUpdateKeepsTheReplacedText()
    {
        ContextSourceEvidence first = discoveryEvidence("task-read:1", "README.md", "1  Evidence");
        RepositoryNotebook saved = service.saveTaskNote(WORKSPACE, 0, taskNote(TASK_ID, "b", MEMORY, Optional.empty(), "Keep the older layout", first));
        ContextEntry note = saved.entries().getFirst();
        assertThat(note.kind()).isEqualTo(MEMORY);
        assertThat(note.id()).startsWith("lesson:project:task-");
        assertThat(note.origin()).isEqualTo(GENERATED);
        assertThat(note.enabled()).isTrue();
        assertThat(saved.replaced()).isEmpty();
        assertThat(service.getTimes(WORKSPACE).entries().get(ContextTimes.key(note.id(), note.version())).taskId()).contains(TASK_ID);

        ContextSourceEvidence second = discoveryEvidence("task-read:2", "README.md", "2  Newer evidence");
        RepositoryNotebook updated = service.saveTaskNote(WORKSPACE, saved.revision(), taskNote(TASK_ID, "c", MEMORY, Optional.of(note), "Use the new layout", second));
        assertThat(updated.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.body()).isEqualTo("Use the new layout");
            assertThat(entry.version()).isEqualTo(updated.revision());
        });
        assertThat(updated.replaced()).containsExactly(note);
        assertThat(updated.sourceEvidence()).extracting(ContextSourceEvidence::id).containsExactlyInAnyOrder("task-read:1", "task-read:2");
        assertThatThrownBy(() -> service.saveTaskNote(WORKSPACE, updated.revision(), taskNote(TASK_ID, "d", MEMORY, Optional.of(note), "Stale update", second)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed since you read it");
    }

    @Test
    void taskNoteRejectsUncapturedOrForeignRepositoryEvidence()
    {
        ContextSourceEvidence source = discoveryEvidence("task-read:1", "README.md", "1  Evidence");
        ContextSourceEvidence reported = new ContextSourceEvidence(source.id(), source.reference(), source.provenance(),
                source.excerpt(), ContextSourceEvidence.Capture.AGENT_REPORTED, source.retrievedAt(), source.contentHash(), source.version(), false);
        assertThatThrownBy(() -> service.saveTaskNote(WORKSPACE, 0, taskNote(TASK_ID, "b", MEMORY, Optional.empty(), "Body", reported)))
                .hasMessageContaining("application-captured");
        ContextSourceEvidence foreign = new ContextSourceEvidence(source.id(),
                new ContextReference.RepositoryFile(2, RepoRef.of("example", "other"), "README.md", HEAD), source.provenance(),
                source.excerpt(), source.capture(), source.retrievedAt(), source.contentHash(), source.version(), false);
        assertThatThrownBy(() -> service.saveTaskNote(WORKSPACE, 0, taskNote(TASK_ID, "b", MEMORY, Optional.empty(), "Body", foreign)))
                .hasMessageContaining("outside its committed repository HEAD");
        assertThat(service.getNotebook(WORKSPACE).entries()).isEmpty();
    }

    @Test
    void taskNoteNeverReplacesUserOwnedOrDisabledNotes()
            throws Exception
    {
        seedGenerated();
        ContextSourceEvidence source = discoveryEvidence("task-read:1", "README.md", "1  Evidence");
        RepositoryNotebook disabled = service.setEntryEnabled(WORKSPACE, 1, "knowledge:project", false);
        assertThatThrownBy(() -> service.saveTaskNote(WORKSPACE, disabled.revision(),
                taskNote(TASK_ID, "b", KNOWLEDGE, Optional.of(disabled.entries().getFirst()), "Agent rewrite", source)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user-owned or disabled");
        RepositoryNotebook enabled = service.setEntryEnabled(WORKSPACE, disabled.revision(), "knowledge:project", true);
        RepositoryNotebook edited = service.editEntry(WORKSPACE, enabled.revision(), edit("User correction", "source"));
        assertThatThrownBy(() -> service.saveTaskNote(WORKSPACE, edited.revision(),
                taskNote(TASK_ID, "b", KNOWLEDGE, Optional.of(edited.entries().getFirst()), "Agent rewrite", source)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user-owned or disabled");
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(edited);
    }

    @Test
    void restoreBringsBackTheReplacedTextAndCanBeUndone()
            throws Exception
    {
        seedGenerated();
        assertThatThrownBy(() -> service.restoreEntry(WORKSPACE, 1, "knowledge:project")).isInstanceOf(ContextNotFoundException.class);
        RepositoryNotebook edited = service.editEntry(WORKSPACE, 1, edit("User correction", "source"));
        assertThat(edited.replaced()).singleElement().satisfies(old -> assertThat(old.body()).isEqualTo("Generated body"));

        RepositoryNotebook restored = service.restoreEntry(WORKSPACE, edited.revision(), "knowledge:project");
        assertThat(restored.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.body()).isEqualTo("Generated body");
            assertThat(entry.origin()).isEqualTo(GENERATED);
            assertThat(entry.version()).isEqualTo(restored.revision());
        });
        assertThat(restored.replaced()).singleElement().satisfies(old -> assertThat(old.body()).isEqualTo("User correction"));

        RepositoryNotebook undone = service.restoreEntry(WORKSPACE, restored.revision(), "knowledge:project");
        assertThat(undone.entries().getFirst().body()).isEqualTo("User correction");
        assertThat(undone.entries().getFirst().origin()).isEqualTo(USER);
        assertThat(undone.replaced()).singleElement().satisfies(old -> assertThat(old.body()).isEqualTo("Generated body"));
    }

    @Test
    void keepSavesTheSameTextAndOwnerAsANewVersion()
            throws Exception
    {
        seedGenerated();
        RepositoryNotebook kept = service.keepEntry(WORKSPACE, 1, "knowledge:project");
        assertThat(kept.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.body()).isEqualTo("Generated body");
            assertThat(entry.origin()).isEqualTo(GENERATED);
            assertThat(entry.version()).isEqualTo(kept.revision());
        });
        assertThat(kept.replaced()).isEmpty();
    }

    @Test
    void disablingARunsNotesSkipsNotesChangedSinceAndOtherTasksNotes()
    {
        ContextSourceEvidence source = discoveryEvidence("task-read:1", "README.md", "1  Evidence");
        RepositoryNotebook first = service.saveTaskNote(WORKSPACE, 0, taskNote(TASK_ID, "b", KNOWLEDGE, Optional.empty(), "Added and untouched", source));
        RepositoryNotebook second = service.saveTaskNote(WORKSPACE, first.revision(), taskNote(TASK_ID, "c", KNOWLEDGE, Optional.empty(), "Added then corrected", source));
        RepositoryNotebook third = service.saveTaskNote(WORKSPACE, second.revision(),
                taskNote(OTHER_TASK_ID, "d", KNOWLEDGE, Optional.of(second.entries().get(1)), "Corrected by another task", source));
        RepositoryNotebook fourth = service.saveTaskNote(WORKSPACE, third.revision(), taskNote(OTHER_TASK_ID, "e", KNOWLEDGE, Optional.empty(), "Another task's note", source));

        RepositoryNotebook disabled = service.disableRunNotes(WORKSPACE, fourth.revision(), TASK_ID);
        assertThat(disabled.entries()).extracting(ContextEntry::body, ContextEntry::enabled).containsExactly(
                tuple("Added and untouched", false), tuple("Corrected by another task", true), tuple("Another task's note", true));
        assertThat(service.disableRunNotes(WORKSPACE, disabled.revision(), TASK_ID)).isEqualTo(disabled);
    }

    @Test
    void onlyOneReplacedVersionIsKeptAndItsOtherwiseUnusedEvidenceIsPruned()
    {
        RepositoryNotebook saved = service.saveTaskNote(WORKSPACE, 0,
                taskNote(TASK_ID, "b", KNOWLEDGE, Optional.empty(), "First", discoveryEvidence("task-read:1", "README.md", "1  First")));
        RepositoryNotebook updated = service.saveTaskNote(WORKSPACE, saved.revision(),
                taskNote(TASK_ID, "c", KNOWLEDGE, Optional.of(saved.entries().getFirst()), "Second", discoveryEvidence("task-read:2", "README.md", "2  Second")));
        ContextEntry second = updated.entries().getFirst();
        RepositoryNotebook again = service.saveTaskNote(WORKSPACE, updated.revision(),
                taskNote(TASK_ID, "d", KNOWLEDGE, Optional.of(second), "Third", discoveryEvidence("task-read:3", "README.md", "3  Third")));
        assertThat(again.replaced()).containsExactly(second);
        assertThat(again.sourceEvidence()).extracting(ContextSourceEvidence::id).containsExactlyInAnyOrder("task-read:2", "task-read:3");
        assertThatThrownBy(() -> new RepositoryNotebook(1, again.revision(), true, true, again.sources(), again.scopes(), again.entries(),
                again.entries(), again.sourceEvidence(), Optional.empty())).hasMessageContaining("must precede");
    }

    @Test
    void notebookAndTimesSavedWithApprovalDraftsStillLoadTheirNotes()
            throws Exception
    {
        RepositoryNotebook saved = service.editEntry(WORKSPACE, 0, edit("Kept note"));
        ObjectNode old = (ObjectNode) mapper.valueToTree(saved);
        old.remove("replaced");
        old.putArray("pendingDrafts").addObject().put("id", "draft:old");
        settings.put(KEY, mapper.writeValueAsString(old));
        ObjectNode times = (ObjectNode) mapper.valueToTree(service.getTimes(WORKSPACE));
        times.putObject("drafts").putObject("draft:old").put("at", "2026-09-01T00:00:00Z");
        settings.put(RepositoryContextService.TIMES_PREFIX + WORKSPACE, mapper.writeValueAsString(times));

        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(saved);
        assertThat(service.getTimes(WORKSPACE).entries()).hasSize(1);
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
    void accumulatedNotebookSurvivesStorageAndEditingBeyondPerRunLimits()
    {
        List<ContextEntry> notes = IntStream.range(0, ContextLimits.MAX_STORED_ENTRIES)
                .mapToObj(index -> storedNote(index, "x".repeat(2048), IntStream.range(index * 8, (index + 1) * 8)
                        .mapToObj(source -> "evidence:" + source).collect(toImmutableList()))).collect(toImmutableList());
        List<ContextScope> scopes = ImmutableList.<ContextScope>builder().add(ContextScope.project()).addAll(IntStream.range(1, 256).mapToObj(index -> {
            String scopeId = "component:" + index;
            ContextInputFingerprint input = ContextInputFingerprint.create(scopeId,
                    ImmutableMap.of("git:README.md", "a".repeat(2000), "git:pom.xml", "b".repeat(2000)), ImmutableList.of());
            ContextPreparationResult preparation = new ContextPreparationResult("attempt:" + index, input, ContextPreparationResult.Outcome.FAILED,
                    ImmutableList.of(), ImmutableList.of(), "Sources need another read");
            return new ContextScope(scopeId, "Component " + index, new ContextMatch.Selected(ImmutableList.of("module-" + index + "/"), ImmutableList.of()),
                    true, Optional.of(preparation), Optional.empty());
        }).iterator()).build();
        List<RegisteredContextSource> sources = IntStream.range(0, 200).mapToObj(index -> new RegisteredContextSource("source:" + index,
                RegisteredContextSource.Kind.DOCUMENTATION_PAGE, "https://example.com/docs/page-" + index, USER, Optional.empty())).collect(toImmutableList());
        List<ContextSourceEvidence> evidence = IntStream.range(0, 4000).mapToObj(index -> new ContextSourceEvidence("evidence:" + index,
                new ContextReference.RepositoryFile(1, RepoRef.of("example", "repository"), "README.md", HEAD), "Captured source", "Evidence " + index,
                ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(Instant.EPOCH), Optional.empty(), Optional.of(HEAD), false)).collect(toImmutableList());
        List<ContextEntry> current = notes.stream().map(note -> new ContextEntry(note.id(), note.kind(), note.scopeId(), note.title(), note.summary(),
                note.body(), note.claims(), note.sourceIds(), note.origin(), note.enabled(), 2)).collect(toImmutableList());
        RepositoryNotebook accumulated = new RepositoryNotebook(1, 2, true, true, sources, scopes, current, notes.subList(0, 250), evidence, Optional.empty());

        RepositoryNotebook saved = service.savePreparation(WORKSPACE, service.getNotebook(WORKSPACE), accumulated);
        assertThat(ContextLimits.bytes(settings.get(KEY).orElseThrow())).isGreaterThan(ContextLimits.MAX_NOTEBOOK_BYTES);
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(saved);
        RepositoryNotebook edited = service.configure(WORKSPACE, saved.revision(), new EditContextConfiguration(Optional.of(false), Optional.empty(), Optional.empty()));
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(edited);
        assertThat(edited.entries()).hasSize(ContextLimits.MAX_STORED_ENTRIES);
        assertThat(edited.replaced()).hasSize(250);
        assertThat(edited.scopes()).hasSize(256);
        assertThat(edited.scopes().stream().flatMap(scope -> scope.preparation().stream()).mapToLong(result -> result.inputFingerprint().inputBytes()).sum())
                .isGreaterThan(ReviewContextSnapshot.MAXIMUM_INPUT_BYTES).isLessThanOrEqualTo(ContextLimits.MAX_STORED_INPUT_BYTES);
        assertThat(edited.sources()).hasSize(200);
        assertThat(edited.sourceEvidence()).hasSize(4000);
        List<ContextEntry> tooMany = ImmutableList.<ContextEntry>builder().addAll(accumulated.entries())
                .add(storedNote(ContextLimits.MAX_STORED_ENTRIES, "Another note", ImmutableList.of())).build();
        assertThatThrownBy(() -> new RepositoryNotebook(1, 2, true, true, sources, scopes, tooMany, ImmutableList.of(), evidence, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("entries exceeds");
    }

    @Test
    void serializedNotebookLimitStillRejectsOversizedWritesAndReads()
    {
        RepositoryNotebook saved = service.editEntry(WORKSPACE, 0, edit("Kept note"));
        List<ContextEntry> notes = IntStream.range(0, ContextLimits.MAX_STORED_ENTRIES)
                .mapToObj(index -> storedNote(index, "Escaped text " + String.valueOf((char) 1).repeat(6000), ImmutableList.of())).collect(toImmutableList());
        RepositoryNotebook oversized = new RepositoryNotebook(1, 2, true, true, saved.sources(), saved.scopes(), notes, saved.sourceEvidence(), Optional.empty());

        assertThatThrownBy(() -> service.savePreparation(WORKSPACE, saved, oversized))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Serialized notebook exceeds UTF-8 limit");
        assertThat(service.getNotebook(WORKSPACE)).isEqualTo(saved);
        settings.put(KEY, settings.get(KEY).orElseThrow() + " ".repeat(ContextLimits.MAX_STORED_NOTEBOOK_BYTES));
        assertThatThrownBy(() -> service.getNotebook(WORKSPACE)).isInstanceOf(ContextStorageException.class)
                .hasRootCauseMessage("Saved notebook exceeds UTF-8 limit");
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
    void contestsLastUntilTheUserDecidesAndKeepOrRestoreRecordTheChoice()
            throws Exception
    {
        seedGenerated();
        service.contest(WORKSPACE, "knowledge:project", 1, "Generated body");
        service.contest(WORKSPACE, "knowledge:project", 1, "Summary");
        assertThat(service.getDisputes(WORKSPACE).contest("knowledge:project")).contains(new ContextDisputes.Contest("Generated body", 1));

        RepositoryNotebook kept = service.keepEntry(WORKSPACE, 1, "knowledge:project");
        assertThat(service.getDisputes(WORKSPACE).contested()).isEmpty();
        assertThat(service.getDisputes(WORKSPACE).settlement("knowledge:project"))
                .contains(new ContextDisputes.Settlement(kept.revision(), ContextDisputes.By.USER, "Generated body", Optional.empty()));

        RepositoryNotebook edited = service.editEntry(WORKSPACE, kept.revision(), edit("User correction", "source"));
        service.contest(WORKSPACE, "knowledge:project", edited.revision(), "User correction");
        RepositoryNotebook restored = service.restoreEntry(WORKSPACE, edited.revision(), "knowledge:project");
        ContextDisputes.Settlement restoredChoice = new ContextDisputes.Settlement(restored.revision(), ContextDisputes.By.USER, "User correction", Optional.empty());
        assertThat(service.getDisputes(WORKSPACE)).isEqualTo(new ContextDisputes(ImmutableMap.of(), ImmutableMap.of("knowledge:project", restoredChoice)));

        service.contest(WORKSPACE, "knowledge:project", restored.revision(), "Generated body");
        RepositoryNotebook rewritten = service.editEntry(WORKSPACE, restored.revision(), edit("Second correction", "source"));
        assertThat(service.getDisputes(WORKSPACE)).isEqualTo(new ContextDisputes(ImmutableMap.of(), ImmutableMap.of("knowledge:project", restoredChoice)));

        service.contest(WORKSPACE, "knowledge:project", rewritten.revision(), "Second correction");
        RepositoryNotebook disabled = service.setEntryEnabled(WORKSPACE, rewritten.revision(), "knowledge:project", false);
        assertThat(service.getDisputes(WORKSPACE).contested()).isEmpty();
        service.contest(WORKSPACE, "knowledge:project", disabled.revision(), "Second correction");
        assertThat(service.getDisputes(WORKSPACE).contested()).isEmpty();

        service.deleteWorkspaceData(WORKSPACE);
        assertThat(settings.get(RepositoryContextService.DISPUTES_PREFIX + WORKSPACE)).isEmpty();
    }

    @Test
    void anInvestigationSettlesAContestedNoteWithNewTextOrEitherSideAndItsRootCause()
    {
        ContextSourceEvidence evidence = discoveryEvidence("task-read:1", "README.md", "1  Evidence");
        RepositoryNotebook saved = service.saveTaskNote(WORKSPACE, 0, taskNote(TASK_ID, "b", KNOWLEDGE, Optional.empty(), "Reads reuse the snapshot", evidence));
        RepositoryNotebook corrected = service.saveTaskNote(WORKSPACE, saved.revision(),
                taskNote(OTHER_TASK_ID, "c", KNOWLEDGE, Optional.of(saved.entries().getFirst()), "Reads refresh the snapshot", evidence));
        ContextEntry current = corrected.entries().getFirst();
        RepositoryContextService.Settle rewrite = new RepositoryContextService.Settle(current.id(), current.version(), RepositoryContextService.Settle.Outcome.REWRITE,
                Optional.of(new RepositoryContextService.Settle.Text("Finding", "Summary", "Reads reuse the snapshot until a REFRESH")),
                ImmutableList.of(discoveryEvidence("context-read:1", "Snapshot.java", "12  refresh();\n")), "Reads reuse the snapshot until a REFRESH",
                "One side read only the REFRESH path");
        assertThatThrownBy(() -> service.settleEntry(WORKSPACE, corrected.revision(), "investigation", rewrite)).hasMessageContaining("settled while");

        service.contest(WORKSPACE, current.id(), current.version(), "Reads refresh the snapshot");
        assertThatThrownBy(() -> service.settleEntry(WORKSPACE, corrected.revision(), "investigation", new RepositoryContextService.Settle(current.id(),
                current.version(), RepositoryContextService.Settle.Outcome.NARROW, rewrite.text(), rewrite.evidence(), "Not in the text", "Cause")))
                .hasMessageContaining("settled text");
        RepositoryNotebook settled = service.settleEntry(WORKSPACE, corrected.revision(), "investigation", rewrite);
        ContextEntry rewritten = settled.entries().getFirst();
        assertThat(rewritten.body()).isEqualTo("Reads reuse the snapshot until a REFRESH");
        assertThat(rewritten.origin()).isEqualTo(GENERATED);
        assertThat(rewritten.sourceIds()).containsExactly("context-read:1");
        assertThat(settled.sourceEvidence()).extracting(ContextSourceEvidence::id).contains("context-read:1");
        assertThat(settled.replaced()).singleElement().satisfies(old -> assertThat(old.body()).isEqualTo("Reads refresh the snapshot"));
        assertThat(service.getDisputes(WORKSPACE)).isEqualTo(new ContextDisputes(ImmutableMap.of(), ImmutableMap.of(current.id(), new ContextDisputes.Settlement(
                rewritten.version(), ContextDisputes.By.INVESTIGATION, "Reads reuse the snapshot until a REFRESH", Optional.of("One side read only the REFRESH path")))));
        assertThat(service.getTimes(WORKSPACE).entries().get(ContextTimes.key(rewritten.id(), rewritten.version())).taskId()).contains("investigation");
        assertThatThrownBy(() -> service.settleEntry(WORKSPACE, settled.revision(), "investigation", rewrite)).hasMessageContaining("changed since the investigation");

        service.contest(WORKSPACE, rewritten.id(), rewritten.version(), "Reads reuse the snapshot until a REFRESH");
        RepositoryNotebook restored = service.settleEntry(WORKSPACE, settled.revision(), "investigation", new RepositoryContextService.Settle(rewritten.id(),
                rewritten.version(), RepositoryContextService.Settle.Outcome.KEEP_PREVIOUS, Optional.empty(), ImmutableList.of(), "Reads refresh the snapshot",
                "Only the REFRESH path reads tables"));
        ContextEntry previous = restored.entries().getFirst();
        assertThat(previous.body()).isEqualTo("Reads refresh the snapshot");
        assertThat(service.getDisputes(WORKSPACE).settlement(previous.id()).orElseThrow().version()).isEqualTo(previous.version());

        service.contest(WORKSPACE, previous.id(), previous.version(), "Reads refresh the snapshot");
        assertThat(service.settleEntry(WORKSPACE, restored.revision(), "investigation", new RepositoryContextService.Settle(previous.id(), previous.version(),
                RepositoryContextService.Settle.Outcome.KEEP_CURRENT, Optional.empty(), ImmutableList.of(), "Reads refresh the snapshot", "Both sides read one path")))
                .isEqualTo(restored);
        assertThat(service.getDisputes(WORKSPACE).settlement(previous.id()).orElseThrow().rootCause()).contains("Both sides read one path");
        assertThat(service.getDisputes(WORKSPACE).contested()).isEmpty();
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

    private static RepositoryContextService.TaskNote taskNote(String taskId, String digit, ContextEntryKind kind, Optional<ContextEntry> target,
            String body, ContextSourceEvidence source)
    {
        return new RepositoryContextService.TaskNote("task:" + "a".repeat(32) + ":" + digit.repeat(32), taskId, kind, "project",
                target.map(ContextEntry::id), target.map(ContextEntry::version), "Finding", "Summary", body, HEAD, ImmutableList.of(source));
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

    private static ContextEntry storedNote(int index, String body, List<String> sourceIds)
    {
        return new ContextEntry("knowledge:project:" + index, KNOWLEDGE, "project", "Note " + index, "Summary", body,
                ImmutableList.of(), sourceIds, USER, true, 1);
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
