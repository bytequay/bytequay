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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestContextNoteReader
{
    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final ContextSourceChecker checker = mock(ContextSourceChecker.class);
    private final ContextNoteReader reader = new ContextNoteReader(notebooks, checker);

    @Test
    void deferredReadPreservesClaimBasisAndDistinctSourceCaptureProvenance()
    {
        ContextSourceEvidence observed = source("observed", ContextSourceEvidence.Capture.BYTEQUAY_READ);
        ContextSourceEvidence reported = source("reported", ContextSourceEvidence.Capture.AGENT_REPORTED);
        ContextEntry entry = new ContextEntry("knowledge:project", ContextEntryKind.KNOWLEDGE, "project", "Conditional note", "Summary", "Body",
                ImmutableList.of(new ContextClaim("Conditional claim", ContextClaim.Basis.INFERENCE, ImmutableList.of(observed.id(), reported.id()))),
                ImmutableList.of(observed.id(), reported.id()), ContextEntryOrigin.USER, true, 7);
        RepositoryNotebook notebook = new RepositoryNotebook(1, 9, false, ImmutableList.of(), ImmutableList.of(ContextScope.project()), ImmutableList.of(entry),
                ImmutableList.of(observed, reported), Optional.empty());
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
                        new ContextKnowledgeDraft("new", additional, Optional.empty(), "a".repeat(40), "Proposed topic")), ImmutableList.of(evidence), Optional.empty());
        when(notebooks.getNotebook("workspace")).thenReturn(notebook);
        when(checker.check(eq("workspace"), eq(Optional.empty()), any())).thenReturn(new ContextSourceCheck(Optional.empty(), ImmutableList.of(), ImmutableList.of()));

        assertThat(reader.readEnabled("workspace", active.id(), 7, Optional.empty()).orElseThrow().read().text()).contains("Approved contract").doesNotContain("Unapproved");
        assertThat(reader.readEnabled("workspace", replacement.id(), 8, Optional.empty())).isEmpty();
        assertThat(reader.readEnabled("workspace", additional.id(), 1, Optional.empty())).isEmpty();
    }

    @Test
    void enabledReadMarksClaimsThatCiteChangedLines()
    {
        String head = "c".repeat(40);
        ContextSourceEvidence workflow = source("workflow", ContextSourceEvidence.Capture.BYTEQUAY_READ);
        ContextSourceEvidence guide = source("guide", ContextSourceEvidence.Capture.BYTEQUAY_READ);
        ContextEntry entry = new ContextEntry("knowledge:project", ContextEntryKind.KNOWLEDGE, "project", "Build commands", "Summary", "Body",
                ImmutableList.of(new ContextClaim("Run validate", ContextClaim.Basis.DOCUMENTED, ImmutableList.of(workflow.id())),
                        new ContextClaim("Use airstyle", ContextClaim.Basis.DOCUMENTED, ImmutableList.of(guide.id()))),
                ImmutableList.of(workflow.id(), guide.id()), ContextEntryOrigin.GENERATED, true, 3);
        when(notebooks.getNotebook("workspace")).thenReturn(new RepositoryNotebook(1, 9, true, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(entry), ImmutableList.of(), ImmutableList.of(workflow, guide), Optional.empty()));
        when(checker.check(eq("workspace"), eq(Optional.of(head)), any())).thenAnswer(invocation -> {
            List<String> refs = ImmutableList.copyOf(invocation.<Map<String, ContextSourceEvidence>>getArgument(2).keySet());
            return new ContextSourceCheck(Optional.of(head), ImmutableList.of(
                    new ContextSourceCheck.Source(refs.get(0), "REPOSITORY_FILE", Optional.of("ci.yml"), Optional.of("3-3"), Optional.empty(), Optional.of("a".repeat(40)),
                            ContextSourceCheck.Status.CHANGED),
                    new ContextSourceCheck.Source(refs.get(1), "REPOSITORY_FILE", Optional.of("DEVELOPMENT.md"), Optional.of("1-2"), Optional.of("4-5"),
                            Optional.of("a".repeat(40)), ContextSourceCheck.Status.UNCHANGED)), ImmutableList.of());
        });

        ContextNoteReader.EnabledRead read = reader.readEnabled("workspace", entry.id(), 3, Optional.of(head)).orElseThrow();

        String workflowRef = read.read().sourceEvidenceRefs().get(workflow.id());
        String guideRef = read.read().sourceEvidenceRefs().get(guide.id());
        assertThat(read.sourceCheck().applicability()).isEqualTo(ContextSelectedEntry.Applicability.VERSION_MISMATCH);
        assertThat(read.read().text()).contains("Applicability: VERSION_MISMATCH",
                "Claim [DOCUMENTED]: Run validate; evidence: [" + workflowRef + "]; cites a changed source\n",
                "Claim [DOCUMENTED]: Use airstyle; evidence: [" + guideRef + "]\n",
                "Cited lines compared with " + head + ": " + workflowRef + " ci.yml lines 3-3: CHANGED; " + guideRef + " DEVELOPMENT.md lines 1-2: UNCHANGED (now lines 4-5)");
    }

    private static ContextSourceEvidence source(String id, ContextSourceEvidence.Capture capture)
    {
        return new ContextSourceEvidence(id, new ContextReference.Document("docs", "https://example.com/" + id), "Source provenance " + id, "Source text " + id,
                capture, Optional.of(Instant.parse("2026-01-01T00:00:00Z")), Optional.of("hash-" + id), Optional.of("release-1"), false);
    }
}
