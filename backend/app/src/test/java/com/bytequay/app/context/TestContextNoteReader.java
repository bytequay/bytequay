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

import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextKnowledgeDraft;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSelectedEntry;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.review.ReviewEvidence;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestContextNoteReader
{
    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final ContextNoteReader reader = new ContextNoteReader(notebooks);

    @Test
    void deferredReadPreservesClaimBasisAndDistinctSourceCaptureProvenance()
    {
        ContextSourceEvidence observed = source("observed", ContextSourceEvidence.Capture.BYTEQUAY_READ);
        ContextSourceEvidence reported = source("reported", ContextSourceEvidence.Capture.AGENT_REPORTED);
        ContextEntry entry = new ContextEntry("knowledge:project", ContextEntryKind.KNOWLEDGE, "project", "Conditional note", "Summary", "Body",
                ImmutableList.of(new ContextClaim("Conditional claim", ContextClaim.Basis.INFERENCE, ImmutableList.of(observed.id(), reported.id()))),
                ImmutableList.of(observed.id(), reported.id()), ContextEntryOrigin.USER, true, 7);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 9, false, ImmutableList.of(), ImmutableList.of(ContextScope.project()), ImmutableList.of(entry),
                ImmutableList.of(), ImmutableList.of(observed, reported), Optional.empty());
        when(notebooks.getNotebook("workspace")).thenReturn(notebook);
        ContextSelectedEntry selected = new ContextSelectedEntry(entry.id(), 7, entry.title(), Optional.empty(), ImmutableMap.of(), ImmutableList.of("Frozen match"),
                ContextSelectedEntry.Applicability.MATCHED, ContextSelectedEntry.Inclusion.DEFERRED);

        ContextNoteReader.Read read = reader.read("workspace", selected).orElseThrow();

        assertThat(read.text()).contains("Applicability: MATCHED (source/input identity only", "claims and effective dependencies remain UNVERIFIED", "Claim [INFERENCE]");
        assertThat(read.sourceEvidenceRefs()).hasSize(2);
        ReviewEvidence captured = read.evidence().get(read.sourceEvidenceRefs().get(observed.id()));
        ReviewEvidence asserted = read.evidence().get(read.sourceEvidenceRefs().get(reported.id()));
        assertThat(captured.excerpt()).isEqualTo("Source text observed");
        assertThat(captured.provenance()).contains("capture=BYTEQUAY_READ", "contentHash=hash-observed", "version=release-1");
        assertThat(captured.explanation()).contains("identity comparison does not verify");
        assertThat(asserted.provenance()).contains("capture=AGENT_REPORTED");
        assertThat(asserted.explanation()).contains("has not independently captured");
    }

    @Test
    void includedReadUsesCapturedReferencesWithoutRequiringCurrentSourceRegistration()
    {
        ContextSelectedEntry selected = new ContextSelectedEntry("knowledge:project", 7, "Captured note", Optional.of("Exact prior rendering"),
                ImmutableMap.of("old-source", "context-source:retained"), ImmutableList.of("Frozen match"), ContextSelectedEntry.Applicability.UNVERIFIED,
                ContextSelectedEntry.Inclusion.INCLUDED);
        ContextNoteReader.Read read = reader.read("workspace", selected).orElseThrow();
        assertThat(read.text()).isEqualTo("Exact prior rendering");
        assertThat(read.sourceEvidenceRefs()).containsExactlyEntriesOf(selected.sourceEvidenceRefs());
        assertThat(read.evidence()).isEmpty();
        verifyNoInteractions(notebooks);
    }

    @Test
    void pendingKnowledgeNeverReplacesOrAddsAnAgentReadableVersion()
    {
        ContextSourceEvidence evidence = source("observed", ContextSourceEvidence.Capture.BYTEQUAY_READ);
        ContextEntry active = new ContextEntry("knowledge:project", ContextEntryKind.KNOWLEDGE, "project", "Active overview", "Approved summary", "Approved contract",
                ImmutableList.of(), ImmutableList.of(evidence.id()), ContextEntryOrigin.GENERATED, true, 7);
        ContextEntry replacement = new ContextEntry(active.id(), active.kind(), active.scopeId(), "Pending overview", "Unapproved summary", "Unapproved replacement",
                ImmutableList.of(), active.sourceIds(), ContextEntryOrigin.GENERATED, true, 8);
        ContextEntry additional = new ContextEntry("knowledge:project:checkpoint", active.kind(), active.scopeId(), "Pending checkpoint", "Unapproved summary", "Unapproved topic",
                ImmutableList.of(), active.sourceIds(), ContextEntryOrigin.GENERATED, true, 1);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 9, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()), ImmutableList.of(active),
                ImmutableList.of(new ContextKnowledgeDraft("update", replacement, Optional.of(7L), "a".repeat(40), "Proposed update"),
                        new ContextKnowledgeDraft("new", additional, Optional.empty(), "a".repeat(40), "Proposed topic")),
                ImmutableList.of(), ImmutableList.of(evidence), Optional.empty());
        when(notebooks.getNotebook("workspace")).thenReturn(notebook);

        assertThat(reader.readEnabled("workspace", active.id(), 7).orElseThrow().read().text()).contains("Approved contract").doesNotContain("Unapproved");
        assertThat(reader.readEnabled("workspace", replacement.id(), 8)).isEmpty();
        assertThat(reader.readEnabled("workspace", additional.id(), 1)).isEmpty();
    }

    private static ContextSourceEvidence source(String id, ContextSourceEvidence.Capture capture)
    {
        return new ContextSourceEvidence(id, new ContextReference.Document("docs", "https://example.com/" + id), "Source provenance " + id, "Source text " + id,
                capture, Optional.of(Instant.parse("2026-01-01T00:00:00Z")), Optional.of("hash-" + id), Optional.of("release-1"), false);
    }
}
