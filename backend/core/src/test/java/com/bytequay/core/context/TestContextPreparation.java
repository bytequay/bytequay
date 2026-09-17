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
package com.bytequay.core.context;

import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.bytequay.core.context.ContextPreparationResult.Outcome.EMPTY;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.FAILED;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.READY;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestContextPreparation
{
    private static final String SHA = "a".repeat(40);
    private static final ContextReference.RepositoryFile REFERENCE = new ContextReference.RepositoryFile(1, RepoRef.of("example", "repo"), "README.md", SHA);

    @Test
    void distinguishesNeverBuiltFromEmptyAndFailedWithoutInventingSourceText()
    {
        ContextRepositoryRead blank = new ContextRepositoryRead("context-read:blank", REFERENCE, SHA, Instant.EPOCH, 1, 0, 0, 0, false, Optional.empty());
        ContextPreparationResult empty = result(EMPTY, ImmutableList.of(), ImmutableList.of(blank));
        ContextPreparationResult failed = new ContextPreparationResult("attempt", ContextInputFingerprint.create("project", ImmutableMap.of(),
                ImmutableList.of("README.md could not be read")), FAILED, ImmutableList.of(), ImmutableList.of(), "Source could not be read");

        assertThat(ContextScope.project().preparation()).isEmpty();
        assertThat(notebook(empty, ImmutableList.of()).scopes().getFirst().preparation()).contains(empty);
        assertThat(notebook(empty, ImmutableList.of()).sourceEvidence()).isEmpty();
        assertThat(notebook(failed, ImmutableList.of()).scopes().getFirst().preparation()).contains(failed);
        assertThatThrownBy(() -> result(EMPTY, ImmutableList.of(), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextPreparationResult("attempt", failed.inputFingerprint(), EMPTY, ImmutableList.of(), ImmutableList.of(blank), "No note"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyCannotClaimSuccessAfterTruncationOrFailedDocumentRefresh()
    {
        ContextRepositoryRead truncated = new ContextRepositoryRead("context-read:truncated", REFERENCE, SHA, Instant.EPOCH, 1, 1, 1, 6, true,
                Optional.of("context-read:truncated"));
        RegisteredContextSource source = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                "https://example.com/docs/page", ContextEntryOrigin.USER, Optional.empty());
        ContextDocumentRead refreshFailed = new ContextDocumentRead("context-read:document", new ContextReference.Document("docs", source.url()), source,
                0, UUID.randomUUID().toString(), "b".repeat(64), Instant.EPOCH, 1, 1, 0, true, false, true, Optional.empty());

        assertThatThrownBy(() -> result(EMPTY, ImmutableList.of(), ImmutableList.of(truncated))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(EMPTY, ImmutableList.of(), ImmutableList.of(refreshFailed))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(FAILED, ImmutableList.of("knowledge:project"), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readyRequiresCanonicalKnowledgeAndItsCapturedEvidence()
    {
        ContextRepositoryRead read = read("source");
        ContextPreparationResult ready = result(READY, ImmutableList.of("knowledge:project"), ImmutableList.of(read));
        ContextSourceEvidence evidence = evidence(read, "source", REFERENCE);

        assertThat(notebook(ready, ImmutableList.of(evidence)).sourceEvidence()).containsExactly(evidence);
        assertThat(notebook(ready, ImmutableList.of(evidence)).entries()).isEmpty();
        assertThatThrownBy(() -> result(READY, ImmutableList.of("knowledge:other"), ImmutableList.of(read))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(READY, ImmutableList.of("knowledge:project"), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notebook(ready, ImmutableList.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("evidence is missing");
        assertThatThrownBy(() -> notebook(ready, ImmutableList.of(evidence(read, "different bytes", REFERENCE))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("byte count differs");
        ContextReference.RepositoryFile other = new ContextReference.RepositoryFile(1, REFERENCE.repository(), "OTHER.md", SHA);
        assertThatThrownBy(() -> notebook(ready, ImmutableList.of(evidence(read, "source", other))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity differs");
        ContextRepositoryRead localRef = new ContextRepositoryRead(read.id(), REFERENCE, SHA, Instant.EPOCH, 1, 1, 1, 6, false, Optional.of("E1"));
        assertThatThrownBy(() -> notebook(result(READY, ready.noteIds(), ImmutableList.of(localRef)), ImmutableList.of(evidence)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("globally unique");
    }

    @Test
    void completedResultsAndActiveAttemptsCannotClaimAnotherScope()
    {
        ContextPreparationResult failed = result(FAILED, ImmutableList.of(), ImmutableList.of());
        assertThatThrownBy(() -> new ContextScope("component", "Component", new ContextMatch.Selected(ImmutableList.of("plugin/"), ImmutableList.of()),
                true, Optional.of(failed), Optional.empty())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another scope");
        ActiveContextPreparation active = active(ImmutableList.of(input("missing")), 0);
        assertThatThrownBy(() -> new RepositoryNotebook(1, 0, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.of(active)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scope is missing");
        assertThatThrownBy(() -> new RepositoryNotebook(1, 0, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.of(active(ImmutableList.of(input("project")), 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("newer than notebook");
    }

    @Test
    void discoveryProvenanceRequiresApplicationCapturedRepositoryEvidence()
    {
        ContextScope scope = new ContextScope("path:component/", "Component", new ContextMatch.Selected(ImmutableList.of("component/"), ImmutableList.of()),
                true, Optional.empty(), Optional.of("proof"));
        RegisteredContextSource source = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                "https://example.com/docs/", ContextEntryOrigin.GENERATED, Optional.of("proof"));
        ContextSourceEvidence captured = new ContextSourceEvidence("proof", REFERENCE, "Captured README", "Documentation link",
                ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(Instant.EPOCH), Optional.of(SHA), Optional.of(SHA), false);
        assertThat(new RepositoryNotebook(1, 0, true, ImmutableList.of(source), ImmutableList.of(ContextScope.project(), scope),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(captured), Optional.empty()).sourceEvidence()).containsExactly(captured);
        assertThatThrownBy(() -> new RepositoryNotebook(1, 0, true, ImmutableList.of(source), ImmutableList.of(ContextScope.project(), scope),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Discovery evidence is missing");
        ContextSourceEvidence reported = new ContextSourceEvidence("proof", REFERENCE, "Model assertion", "Documentation link",
                ContextSourceEvidence.Capture.AGENT_REPORTED, Optional.empty(), Optional.empty(), Optional.empty(), false);
        ContextSourceEvidence document = new ContextSourceEvidence("proof", new ContextReference.Document("docs", source.url()), "Captured page", "Documentation link",
                ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(Instant.EPOCH), Optional.of(SHA), Optional.empty(), false);
        for (ContextSourceEvidence invalid : ImmutableList.of(reported, document)) {
            assertThatThrownBy(() -> new RepositoryNotebook(1, 0, true, ImmutableList.of(source), ImmutableList.of(ContextScope.project(), scope),
                    ImmutableList.of(), ImmutableList.of(), ImmutableList.of(invalid), Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("captured repository file");
        }
    }

    @Test
    void ownsOnlyBoundedImmutableCompleteInputs()
    {
        List<ContextInputFingerprint> inputs = new ArrayList<>(ImmutableList.of(input("project")));
        ActiveContextPreparation active = active(inputs, 0);
        inputs.clear();
        assertThat(active.inputs()).hasSize(1);
        assertThatThrownBy(() -> active(ImmutableList.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> active(ImmutableList.of(input("project"), input("project")), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> active(ImmutableList.of(ContextInputFingerprint.create("project", ImmutableMap.of(), ImmutableList.of("Unavailable"))), 0))
                .isInstanceOf(IllegalArgumentException.class);
        List<ContextSourceRead> reads = new ArrayList<>(ImmutableList.of(read("source")));
        ContextPreparationResult ready = result(READY, ImmutableList.of("knowledge:project"), reads);
        reads.clear();
        assertThat(ready.checkedReads()).hasSize(1);
        List<ContextSourceRead> excessive = IntStream.range(0, 9).mapToObj(index -> (ContextSourceRead) read("source" + index)).collect(toImmutableList());
        assertThatThrownBy(() -> result(READY, ready.noteIds(), excessive)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void standaloneOwnerRequiresNoReviewAndRejectsPartialReviewIdentity()
    {
        ActiveContextPreparation standalone = new ActiveContextPreparation("attempt", "request", "sha256:" + "a".repeat(64), "task", "turn", "turn-key",
                Optional.empty(), Optional.empty(), 0, ImmutableList.of(input("project")));
        assertThat(standalone.reviewId()).isEmpty();
        assertThatThrownBy(() -> new ActiveContextPreparation("attempt", "request", "sha256:" + "a".repeat(64), "task", "turn", "turn-key",
                Optional.of("review"), Optional.empty(), 0, ImmutableList.of(input("project"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paired");
    }

    private static ContextInputFingerprint input(String scope)
    {
        return ContextInputFingerprint.create(scope, ImmutableMap.of("git:README.md", "100644 blob " + SHA), ImmutableList.of());
    }

    private static ContextPreparationResult result(ContextPreparationResult.Outcome outcome, List<String> noteIds, List<ContextSourceRead> reads)
    {
        return new ContextPreparationResult("attempt", input("project"), outcome, noteIds, reads, "Only the checked source material was considered");
    }

    private static ActiveContextPreparation active(List<ContextInputFingerprint> inputs, long revision)
    {
        return new ActiveContextPreparation("attempt", "request", "sha256:" + "a".repeat(64), "task", "turn", "turn-key",
                Optional.of("review"), Optional.of("run"), revision, inputs);
    }

    private static ContextRepositoryRead read(String suffix)
    {
        String id = "context-read:" + suffix;
        return new ContextRepositoryRead(id, REFERENCE, SHA, Instant.EPOCH, 1, 1, 1, 6, false, Optional.of(id));
    }

    private static ContextSourceEvidence evidence(ContextSourceRead read, String text, ContextReference reference)
    {
        return new ContextSourceEvidence(read.id(), reference, "Captured source", text, ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.EPOCH), Optional.of(SHA), Optional.empty(), false);
    }

    private static RepositoryNotebook notebook(ContextPreparationResult result, List<ContextSourceEvidence> sources)
    {
        ContextScope project = new ContextScope("project", "Project", new ContextMatch.Repository(), true, Optional.of(result), Optional.empty());
        return new RepositoryNotebook(1, 0, true, ImmutableList.of(), ImmutableList.of(project), ImmutableList.of(), ImmutableList.of(), sources, Optional.empty());
    }
}
