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

import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSelectedEntry;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.bytequay.core.context.ContextEntryKind.MEMORY;
import static com.bytequay.core.context.ContextEntryOrigin.GENERATED;
import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestReviewContextService
{
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final WorkspaceRepositoryContext WORKSPACE = new WorkspaceRepositoryContext("workspace", Path.of("/tmp/context-test"), 42,
            RepoRef.of("example", "repository"), OptionalLong.empty(), RepoRef.of("example", "repository"), "origin", "origin", "main", ImmutableList.of());
    private static final AgentReview REVIEW = new AgentReview("review", "workspace", "example/repository", 1, OptionalLong.empty(), "task", AgentReview.State.OPEN,
            false, Optional.empty(), Optional.of(HEAD), 0, NOW, NOW, Optional.empty());
    private static final ContextSourceEvidence SOURCE = new ContextSourceEvidence("source-doc", new ContextReference.Document("docs", "https://example.com/docs/page"),
            "Captured documentation provenance", "The retained source contract", ContextSourceEvidence.Capture.BYTEQUAY_READ,
            Optional.of(NOW), Optional.of("sha256:source"), Optional.of("release-1"), false);

    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final RepositoryContextReader reader = mock(RepositoryContextReader.class);
    private final ContextInputReader inputs = mock(ContextInputReader.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final ReviewContextService service = new ReviewContextService(notebooks, reader, inputs, reviews, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void disabledContextPersistsExplicitSnapshotWithoutReadingSources()
    {
        RepositoryNotebook notebook = notebook(false, ImmutableList.of(), ImmutableList.of());
        when(notebooks.getNotebook("workspace")).thenReturn(notebook);
        captureWrites();
        ReviewRun run = run();

        ReviewRun captured = service.prepareRun(REVIEW, run, WORKSPACE, Optional.empty());

        ReviewContextSnapshot context = captured.context().orElseThrow();
        assertThat(context.enabled()).isFalse();
        assertThat(context.notebookRevision()).isEqualTo(notebook.revision());
        assertThat(context.repositoryId()).isEqualTo(42);
        assertThat(context.bootstrap()).isEmpty();
        assertThat(context.selectedEntries()).isEmpty();
        assertThat(context.relatedItems()).isEmpty();
        assertThat(context.inputFingerprints()).isEmpty();
        assertThat(captured.evidence()).isEqualTo(run.evidence());
        assertThat(captured.version()).isEqualTo(1);
        verifyNoInteractions(reader, inputs);
        verify(reviews).updateRun(captured.withVersion(0));
    }

    @Test
    void replayReturnsCapturedRunWithoutAnyReadsOrWrites()
    {
        ReviewContextSnapshot disabled = new ReviewContextSnapshot("context-off", false, 3, 42, Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        ReviewRun captured = run().withContext(disabled, ImmutableMap.of(), NOW).withVersion(1);

        assertThat(service.prepareRun(REVIEW, captured, WORKSPACE, Optional.empty())).isSameAs(captured);
        verifyNoInteractions(notebooks, reader, inputs, reviews);
    }

    @Test
    void freezesRenderedClaimsAndCopiedEvidenceWithoutPromotingTheirApplicability()
    {
        ContextEntry note = new ContextEntry("knowledge:project", KNOWLEDGE, "project", "Project architecture", "Connector summary", "Connector body",
                ImmutableList.of(new ContextClaim("The connector owns the transaction", ContextClaim.Basis.DOCUMENTED, ImmutableList.of(SOURCE.id()))),
                ImmutableList.of(SOURCE.id()), GENERATED, true, 4);
        RepositoryNotebook notebook = notebook(true, ImmutableList.of(note), ImmutableList.of(SOURCE));
        ReviewRun original = run();
        select(notebook, original, ImmutableList.of());
        captureWrites();

        ReviewRun captured = service.prepareRun(REVIEW, original, WORKSPACE, Optional.empty());
        ReviewContextSnapshot context = captured.context().orElseThrow();
        ContextSelectedEntry selected = context.selectedEntries().getFirst();
        String evidenceRef = selected.sourceEvidenceRefs().get(SOURCE.id());
        ReviewEvidence evidence = captured.evidence().get(evidenceRef);

        assertThat(context.notebookRevision()).isEqualTo(notebook.revision());
        assertThat(context.baselineSha()).contains(BASE);
        assertThat(selected.entryVersion()).isEqualTo(4);
        assertThat(selected.applicability()).isEqualTo(ContextSelectedEntry.Applicability.UNVERIFIED);
        assertThat(selected.text().orElseThrow()).contains(note.title(), note.summary(), note.body(), note.claims().getFirst().text(), "DOCUMENTED", evidenceRef);
        assertThat(evidence.excerpt()).isEqualTo(SOURCE.excerpt());
        assertThat(evidence.source()).contains("https://example.com/docs/page");
        assertThat(evidence.provenance()).contains(SOURCE.provenance(), "capture=BYTEQUAY_READ", "version=release-1", "contentHash=sha256:source", "truncated=false");
        assertThat(context.bootstrap().orElseThrow().text()).contains(selected.text().orElseThrow());
        assertThat(captured.evidence()).containsEntry("existing", original.evidence().get("existing"));
        assertThat(original.context()).isEmpty();

        when(notebooks.getNotebook("workspace")).thenReturn(RepositoryNotebook.empty());
        clearInvocations(notebooks, reader, inputs, reviews);
        assertThat(service.prepareRun(REVIEW, captured, WORKSPACE, Optional.empty())).isSameAs(captured);
        assertThat(captured.evidence().get(evidenceRef)).isEqualTo(evidence);
        verifyNoInteractions(notebooks, reader, inputs, reviews);
    }

    @Test
    void measuresRenderedNotesInUtf8AndDefersWholeEntries()
    {
        ContextEntry first = note("project", "你".repeat(2500));
        ContextEntry second = note("extra", "é".repeat(1000));
        RepositoryNotebook notebook = notebook(true, ImmutableList.of(first, second), ImmutableList.of());
        ReviewRun original = run();
        select(notebook, original, ImmutableList.of());
        captureWrites();

        ReviewContextSnapshot context = service.prepareRun(REVIEW, original, WORKSPACE, Optional.empty()).context().orElseThrow();
        ContextSelectedEntry included = context.selectedEntries().get(0);
        ContextSelectedEntry deferred = context.selectedEntries().get(1);

        assertThat(included.inclusion()).isEqualTo(ContextSelectedEntry.Inclusion.INCLUDED);
        assertThat(included.text().orElseThrow()).contains(first.body());
        assertThat(ContextLimits.bytes(included.text().orElseThrow())).isLessThanOrEqualTo(ReviewContextSnapshot.MAXIMUM_SELECTED_TEXT_BYTES);
        assertThat(deferred.inclusion()).isEqualTo(ContextSelectedEntry.Inclusion.DEFERRED);
        assertThat(deferred.text()).isEmpty();
        assertThat(deferred.sourceEvidenceRefs()).isEmpty();
        assertThat(context.gaps()).isNotEmpty();
        ContextPacket packet = context.bootstrap().orElseThrow();
        assertThat(packet.text()).doesNotContain(second.body());
        assertThat(packet.utf8Bytes()).isEqualTo(ContextLimits.bytes(packet.text())).isLessThanOrEqualTo(ContextPacket.MAXIMUM_BYTES);
    }

    @Test
    void givesTopicMatchedLessonBudgetBeforeScopeOnlyLessonAndFreezesTheReasons()
    {
        ContextScope iceberg = new ContextScope("iceberg", "Iceberg",
                new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of()), true, Optional.empty());
        ContextEntry configuration = new ContextEntry("lesson:configuration", MEMORY, iceberg.id(), "Configuration validation", "Catalog configuration",
                "Configuration guidance. ".repeat(250), ImmutableList.of(), ImmutableList.of(SOURCE.id()), USER, true, 1);
        ContextEntry snapshot = new ContextEntry("lesson:snapshot", MEMORY, iceberg.id(), "Snapshot isolation", "Validate the snapshot isolation contract",
                "Snapshot guidance. ".repeat(300), ImmutableList.of(), ImmutableList.of(SOURCE.id()), USER, true, 1);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 4, true, ImmutableList.of(), ImmutableList.of(ContextScope.project(), iceberg),
                ImmutableList.of(configuration, snapshot), ImmutableList.of(), ImmutableList.of(SOURCE), Optional.empty());
        ReviewRun original = run();
        when(notebooks.getNotebook("workspace")).thenReturn(notebook);
        RepositoryContextMatcher.Selection matches = new RepositoryContextMatcher().select(notebook, ImmutableList.of("plugin/iceberg/Metadata.java"),
                "Fix snapshot isolation", ImmutableList.of());
        when(reader.select(REVIEW, original, WORKSPACE, Optional.empty(), notebook)).thenReturn(new RepositoryContextReader.Selection(Optional.of(BASE), matches,
                ImmutableList.of(), ImmutableList.of()));
        captureWrites();

        ReviewRun captured = service.prepareRun(REVIEW, original, WORKSPACE, Optional.empty());
        ReviewContextSnapshot context = captured.context().orElseThrow();

        assertThat(context.selectedEntries()).extracting(ContextSelectedEntry::entryId).containsExactly(snapshot.id(), configuration.id());
        ContextSelectedEntry included = context.selectedEntries().getFirst();
        ContextSelectedEntry deferred = context.selectedEntries().getLast();
        assertThat(included.inclusion()).isEqualTo(ContextSelectedEntry.Inclusion.INCLUDED);
        assertThat(included.matchReasons()).contains("Lesson title/summary words also occur in PR text or changed filenames: snapshot, isolation");
        assertThat(deferred.inclusion()).isEqualTo(ContextSelectedEntry.Inclusion.DEFERRED);
        assertThat(deferred.text()).isEmpty();
        assertThat(deferred.matchReasons()).anySatisfy(reason -> assertThat(reason).contains("no lesson-specific words"));
        assertThat(context.selectedEntries()).allSatisfy(entry -> assertThat(entry.applicability()).isEqualTo(ContextSelectedEntry.Applicability.UNVERIFIED));
        assertThat(context.bootstrap().orElseThrow().text()).contains(snapshot.body()).doesNotContain(configuration.body());

        clearInvocations(notebooks, reader, inputs, reviews);
        assertThat(service.prepareRun(REVIEW, captured, WORKSPACE, Optional.empty())).isSameAs(captured);
        verifyNoInteractions(notebooks, reader, inputs, reviews);
    }

    @Test
    void freezesBaselineInputIdentitiesWithoutPromotingClaimsOrRereadingOnReplay()
    {
        ContextEntry note = note("project", "A conditional project claim");
        RepositoryNotebook notebook = notebook(true, ImmutableList.of(note), ImmutableList.of());
        ReviewRun original = run();
        select(notebook, original, ImmutableList.of());
        ContextInputFingerprint fingerprint = ContextInputFingerprint.create("project", ImmutableMap.of("git:pom.xml", "100644 blob " + BASE), ImmutableList.of());
        when(inputs.capture(WORKSPACE, Optional.of(BASE), notebook, ImmutableList.of(ContextScope.project()))).thenReturn(ImmutableList.of(fingerprint));
        captureWrites();

        ReviewRun captured = service.prepareRun(REVIEW, original, WORKSPACE, Optional.empty());
        ReviewContextSnapshot context = captured.context().orElseThrow();

        assertThat(context.inputFingerprints()).containsExactly(fingerprint);
        assertThat(context.bootstrap().orElseThrow().text()).contains("Local input inventories: 1 complete of 1", "remote document freshness are unverified");
        assertThat(context.selectedEntries().getFirst().applicability()).isEqualTo(ContextSelectedEntry.Applicability.UNVERIFIED);
        verify(inputs).capture(WORKSPACE, Optional.of(BASE), notebook, ImmutableList.of(ContextScope.project()));
        clearInvocations(notebooks, reader, inputs, reviews);

        assertThat(service.prepareRun(REVIEW, captured, WORKSPACE, Optional.empty())).isSameAs(captured);
        assertThat(captured.context().orElseThrow().inputFingerprints()).containsExactly(fingerprint);
        verifyNoInteractions(notebooks, reader, inputs, reviews);
    }

    @Test
    void retainsIncompleteInventoryAndItsReasonWithoutBlockingReview()
    {
        RepositoryNotebook notebook = notebook(true, ImmutableList.of(note("project", "Project")), ImmutableList.of());
        ReviewRun original = run();
        select(notebook, original, ImmutableList.of());
        ContextInputFingerprint incomplete = ContextInputFingerprint.create("project", ImmutableMap.of(), ImmutableList.of("Git inventory was truncated."));
        when(inputs.capture(WORKSPACE, Optional.of(BASE), notebook, ImmutableList.of(ContextScope.project()))).thenReturn(ImmutableList.of(incomplete));
        captureWrites();

        ReviewContextSnapshot context = service.prepareRun(REVIEW, original, WORKSPACE, Optional.empty()).context().orElseThrow();

        assertThat(context.inputFingerprints()).containsExactly(incomplete);
        assertThat(context.inputFingerprints().getFirst().digest()).isEmpty();
        assertThat(context.gaps()).contains("Scope project: Git inventory was truncated.");
        assertThat(context.bootstrap().orElseThrow().text()).contains("0 complete of 1", "Git inventory was truncated.");
    }

    @Test
    void boundsTotalInventoryStorageWithoutCreatingAReusablePartialDigest()
    {
        RepositoryNotebook notebook = notebook(true, ImmutableList.of(), ImmutableList.of());
        ReviewRun original = run();
        select(notebook, original, ImmutableList.of());
        ImmutableMap.Builder<String, String> identities = ImmutableMap.builder();
        IntStream.range(0, 16).forEach(index -> identities.put("git:" + index, "界".repeat(660)));
        List<ContextInputFingerprint> captured = IntStream.range(0, 10)
                .mapToObj(index -> ContextInputFingerprint.create("scope-" + index, identities.buildOrThrow(), ImmutableList.of())).collect(toImmutableList());
        when(inputs.capture(WORKSPACE, Optional.of(BASE), notebook, ImmutableList.of())).thenReturn(captured);
        captureWrites();

        ReviewContextSnapshot context = service.prepareRun(REVIEW, original, WORKSPACE, Optional.empty()).context().orElseThrow();

        assertThat(context.inputFingerprints()).hasSize(10);
        assertThat(context.inputFingerprints().stream().mapToLong(ContextInputFingerprint::inputBytes).sum()).isLessThanOrEqualTo(ReviewContextSnapshot.MAXIMUM_INPUT_BYTES);
        assertThat(context.inputFingerprints().getFirst()).isEqualTo(captured.getFirst());
        ContextInputFingerprint omitted = context.inputFingerprints().getLast();
        assertThat(omitted.digest()).isEmpty();
        assertThat(omitted.inputs()).isEmpty();
        assertThat(omitted.gaps()).anyMatch(gap -> gap.contains("256 KiB inventory budget"));
        assertThat(context.gaps()).anyMatch(gap -> gap.contains("scope-9") && gap.contains("256 KiB inventory budget"));
    }

    @Test
    void defersValidBodyWhenWholeRenderedNoteExceedsBudget()
    {
        ContextEntry large = new ContextEntry("knowledge:project", KNOWLEDGE, "project", "Project", "Summary", "你".repeat(2730), ImmutableList.of(),
                ImmutableList.of(SOURCE.id()), USER, true, 4);
        RepositoryNotebook notebook = notebook(true, ImmutableList.of(large), ImmutableList.of(SOURCE));
        ReviewRun original = run();
        select(notebook, original, ImmutableList.of());
        captureWrites();

        ReviewRun captured = service.prepareRun(REVIEW, original, WORKSPACE, Optional.empty());
        ReviewContextSnapshot context = captured.context().orElseThrow();

        assertThat(ContextLimits.bytes(large.body())).isLessThanOrEqualTo(ContextLimits.MAX_BODY_BYTES);
        assertThat(context.selectedEntries().getFirst().inclusion()).isEqualTo(ContextSelectedEntry.Inclusion.DEFERRED);
        assertThat(context.selectedEntries().getFirst().text()).isEmpty();
        assertThat(context.selectedEntries().getFirst().sourceEvidenceRefs()).isEmpty();
        assertThat(captured.evidence()).isEqualTo(original.evidence());
        assertThat(context.gaps()).isNotEmpty();
    }

    @Test
    void boundsHistoryAndMetadataWithoutPartiallyIncludingSources()
    {
        RepositoryNotebook notebook = notebook(true, ImmutableList.of(note("project", "你".repeat(2300))), ImmutableList.of());
        List<RepositoryContextReader.Related> history = ImmutableList.of(
                related("pr:42:2", "😀".repeat(1500)), related("issue:42:3", "你".repeat(2000)), related("review:previous", "é".repeat(3000)));
        List<String> gaps = ImmutableList.of("First " + "界".repeat(600), "Second " + "界".repeat(600), "Third " + "界".repeat(600),
                "Fourth " + "界".repeat(600), "Fifth " + "界".repeat(600), "Sixth " + "界".repeat(600));
        ReviewRun original = run();
        select(notebook, original, history, gaps);
        captureWrites();

        ReviewRun captured = service.prepareRun(REVIEW, original, WORKSPACE, Optional.empty());
        ReviewContextSnapshot context = captured.context().orElseThrow();
        ContextPacket packet = context.bootstrap().orElseThrow();

        assertThat(context.relatedItems()).extracting(item -> item.sourceId()).containsExactly("pr:42:2", "issue:42:3");
        assertThat(captured.evidence()).containsKeys("pr:42:2", "issue:42:3").doesNotContainKey("review:previous");
        assertThat(captured.evidence().get("pr:42:2").excerpt()).isEqualTo(history.getFirst().excerpt());
        assertThat(packet.text()).contains(history.get(0).excerpt(), history.get(1).excerpt()).doesNotContain(history.get(2).excerpt());
        assertThat(context.gaps()).containsAll(gaps).anyMatch(gap -> gap.contains("16 KiB history budget"));
        assertThat(packet.text()).contains("Additional gap details are retained in the snapshot");
        assertThat(packet.utf8Bytes()).isEqualTo(ContextLimits.bytes(packet.text())).isLessThanOrEqualTo(ContextPacket.MAXIMUM_BYTES);
    }

    @Test
    void retainsUnavailableBaselineAndMissingKnowledgeAsExplicitGaps()
    {
        RepositoryNotebook notebook = notebook(true, ImmutableList.of(), ImmutableList.of());
        ReviewRun original = run();
        when(notebooks.getNotebook("workspace")).thenReturn(notebook);
        RepositoryContextMatcher.ScopeMatch missing = new RepositoryContextMatcher.ScopeMatch(ContextScope.project(), ImmutableList.of("Repository-wide scope"),
                RepositoryContextMatcher.Rank.REPOSITORY, true);
        RepositoryContextReader.Selection selection = new RepositoryContextReader.Selection(Optional.empty(),
                new RepositoryContextMatcher.Selection(ImmutableList.of(), ImmutableList.of(missing)), ImmutableList.of(), ImmutableList.of("Baseline unavailable locally"));
        when(reader.select(REVIEW, original, WORKSPACE, Optional.empty(), notebook)).thenReturn(selection);
        captureWrites();

        ReviewContextSnapshot context = service.prepareRun(REVIEW, original, WORKSPACE, Optional.empty()).context().orElseThrow();

        assertThat(context.baselineSha()).isEmpty();
        assertThat(context.selectedEntries()).isEmpty();
        assertThat(context.gaps()).contains("Baseline unavailable locally").anyMatch(gap -> gap.contains("No completed preparation is recorded"));
    }

    @Test
    void boundsLargeAndNumerousGapMessagesBeforeCapturingSnapshot()
    {
        RepositoryNotebook notebook = notebook(true, ImmutableList.of(), ImmutableList.of());
        String oversized = "Gap 0: " + "😀".repeat(600);
        List<String> gaps = ImmutableList.<String>builder().add(oversized)
                .addAll(IntStream.range(1, 105).mapToObj(index -> "Gap " + index).collect(toImmutableList()))
                .add(oversized, "Gap 1").build();
        ReviewRun original = run();
        select(notebook, original, ImmutableList.of(), gaps);
        captureWrites();

        ReviewContextSnapshot context = service.prepareRun(REVIEW, original, WORKSPACE, Optional.empty()).context().orElseThrow();

        assertThat(context.gaps()).hasSize(100).doesNotHaveDuplicates();
        assertThat(context.gaps().getFirst()).startsWith("Gap 0: ").endsWith("…").isNotEqualTo(oversized);
        assertThat(context.gaps()).allSatisfy(gap -> assertThat(ContextLimits.bytes(gap)).isLessThanOrEqualTo(2000));
        assertThat(context.gaps().get(98)).isEqualTo("Gap 98");
        assertThat(context.gaps().getLast()).startsWith("6 additional context gaps were omitted");
        assertThat(context.bootstrap().orElseThrow().utf8Bytes()).isLessThanOrEqualTo(ContextPacket.MAXIMUM_BYTES);
    }

    private void captureWrites()
    {
        when(reviews.updateRun(any())).thenAnswer(invocation -> {
            ReviewRun updated = invocation.getArgument(0);
            return updated.withVersion(updated.version() + 1);
        });
    }

    private void select(RepositoryNotebook notebook, ReviewRun run, List<RepositoryContextReader.Related> related)
    {
        select(notebook, run, related, ImmutableList.of());
    }

    private void select(RepositoryNotebook notebook, ReviewRun run, List<RepositoryContextReader.Related> related, List<String> gaps)
    {
        when(notebooks.getNotebook("workspace")).thenReturn(notebook);
        List<RepositoryContextMatcher.Match> entries = notebook.entries().stream()
                .map(entry -> new RepositoryContextMatcher.Match(entry, ImmutableList.of("Repository-wide scope"), RepositoryContextMatcher.Rank.REPOSITORY))
                .collect(toImmutableList());
        RepositoryContextReader.Selection selection = new RepositoryContextReader.Selection(Optional.of(BASE),
                new RepositoryContextMatcher.Selection(entries, notebook.scopes().stream()
                        .filter(scope -> entries.stream().anyMatch(entry -> entry.entry().scopeId().equals(scope.id())))
                        .map(scope -> new RepositoryContextMatcher.ScopeMatch(scope, ImmutableList.of("Fixture scope"), RepositoryContextMatcher.Rank.REPOSITORY, false))
                        .collect(toImmutableList())), related, gaps);
        when(reader.select(REVIEW, run, WORKSPACE, Optional.empty(), notebook)).thenReturn(selection);
    }

    private static RepositoryContextReader.Related related(String id, String excerpt)
    {
        return new RepositoryContextReader.Related(id, "Related local source", excerpt, "Cached local provenance", ImmutableList.of("Matching path"), Optional.empty());
    }

    private static ContextEntry note(String scopeId, String body)
    {
        return new ContextEntry(ContextEntry.knowledgeId(scopeId), KNOWLEDGE, scopeId, "Title " + scopeId, "Summary", body,
                ImmutableList.of(), ImmutableList.of(), USER, true, 4);
    }

    private static RepositoryNotebook notebook(boolean enabled, List<ContextEntry> entries, List<ContextSourceEvidence> sources)
    {
        ContextScope extra = new ContextScope("extra", "Extra", new ContextMatch.Selected(ImmutableList.of(), ImmutableList.of("extra")), true, Optional.empty());
        return new RepositoryNotebook(1, 4, enabled, ImmutableList.of(), ImmutableList.of(ContextScope.project(), extra), entries, ImmutableList.of(), sources, Optional.empty());
    }

    private static ReviewRun run()
    {
        ReviewEvidence existing = new ReviewEvidence("existing", "Existing source", "Already retained", "Existing excerpt", "Existing explanation");
        return new ReviewRun("run", REVIEW.id(), 1, ReviewRun.Scope.FULL, "main", BASE, BASE, HEAD, Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false, ImmutableMap.of(),
                ImmutableMap.of(existing.ref(), existing), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }
}
