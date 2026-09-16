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
package com.bytequay.core.review;

import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRelatedItem;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewRun
{
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String SHA = "1111111111111111111111111111111111111111";
    private static final ReviewEvidence EVIDENCE = new ReviewEvidence("ev-context", "Previous review", "ByteQuay stored review", "Result", "Related path");

    @Test
    void attachesContextAndEvidenceOnceWithoutReplacingExistingReferences()
    {
        ReviewRun original = run();
        ReviewContextSnapshot snapshot = snapshot("context-1");
        ReviewRun captured = original.withContext(snapshot, ImmutableMap.of(EVIDENCE.ref(), EVIDENCE), NOW.plusSeconds(1));

        assertThat(original.context()).isEmpty();
        assertThat(original.evidence()).isEmpty();
        assertThat(captured.context()).contains(snapshot);
        assertThat(captured.evidence()).containsEntry(EVIDENCE.ref(), EVIDENCE);
        assertThat(captured.withContext(snapshot, ImmutableMap.of(EVIDENCE.ref(), EVIDENCE), NOW.plusSeconds(2))).isSameAs(captured);
        assertThatThrownBy(() -> original.withContext(snapshot, ImmutableMap.of(), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> captured.withContext(snapshot("context-2"), ImmutableMap.of(), NOW)).isInstanceOf(IllegalStateException.class);
        ReviewEvidence replacement = new ReviewEvidence(EVIDENCE.ref(), "Altered source", "Altered", "Replacement", "Changed");
        assertThatThrownBy(() -> captured.withContext(snapshot, ImmutableMap.of(EVIDENCE.ref(), replacement), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> original.withContext(snapshot, ImmutableMap.of("wrong-ref", EVIDENCE), NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesFrozenContextThroughProgressUpdates()
    {
        ReviewContextSnapshot snapshot = snapshot("context-1");
        ReviewRun captured = run().withContext(snapshot, ImmutableMap.of(EVIDENCE.ref(), EVIDENCE), NOW);
        ReviewRun updated = captured.withVersion(1)
                .withTurn("turn-1", NOW.plusSeconds(1))
                .withStep(ReviewStep.initial(), NOW.plusSeconds(2))
                .withCoverage(new ReviewCoverage(false, ImmutableList.of("fixture unavailable"), NOW), NOW.plusSeconds(3))
                .withOperation("operation", new ReviewOperation("digest", "result"), NOW.plusSeconds(4))
                .withCompletionRepairUsed(NOW.plusSeconds(5))
                .ended(ReviewRun.State.STOPPED, Optional.of("User stopped"), NOW.plusSeconds(6));

        assertThat(updated.context()).contains(snapshot);
        assertThat(updated.evidence()).isEqualTo(captured.evidence());
    }

    @Test
    void retainsSourceReceiptAndExactEvidenceTogetherWithoutChangingBootstrap()
    {
        ReviewContextSnapshot snapshot = snapshot("context-1");
        ReviewRun original = run().withContext(snapshot, ImmutableMap.of(EVIDENCE.ref(), EVIDENCE), NOW);
        ReviewEvidence source = new ReviewEvidence("source-read", "README.md", "Frozen repository blob", "1  é\n", "");
        ContextRepositoryRead read = repositoryRead("context-read:first", 6, Optional.of(source.ref()));
        ReviewRun captured = original.withContextSourceRead(read, Optional.of(source), NOW.plusSeconds(1));

        assertThat(captured.context().orElseThrow().sourceReads()).containsExactly(read);
        assertThat(captured.context().orElseThrow().bootstrap()).isEqualTo(snapshot.bootstrap());
        assertThat(captured.context().orElseThrow().selectedEntries()).isEqualTo(snapshot.selectedEntries());
        assertThat(captured.evidence()).containsEntry(source.ref(), source).containsEntry(EVIDENCE.ref(), EVIDENCE);
        assertThat(captured.withContextSourceRead(read, Optional.of(source), NOW.plusSeconds(2))).isSameAs(captured);
        assertThat(original.context()).contains(snapshot);
        assertThatThrownBy(() -> original.withContextSourceRead(read, Optional.empty(), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> original.withContextSourceRead(repositoryRead("context-read:bytes", 5, Optional.of(source.ref())), Optional.of(source), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("byte count");
        ReviewEvidence replaced = new ReviewEvidence(source.ref(), "Another source", source.provenance(), source.excerpt(), "");
        assertThatThrownBy(() -> captured.withContextSourceRead(read, Optional.of(replaced), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> captured.withContextSourceRead(repositoryRead(read.id(), 7, Optional.of(source.ref())),
                Optional.of(new ReviewEvidence(source.ref(), source.source(), source.provenance(), "1  é!\n", "")), NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Source read");
        assertThatThrownBy(() -> original.withContext(snapshot.withSourceRead(read), ImmutableMap.of(), NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run().withContext(snapshot.withSourceRead(read), ImmutableMap.of(EVIDENCE.ref(), EVIDENCE), NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing run evidence");
    }

    @Test
    void retainsSuccessfulEmptyFileWithoutInventingAnEvidenceExcerpt()
    {
        ReviewRun original = run().withContext(snapshot("context-1"), ImmutableMap.of(EVIDENCE.ref(), EVIDENCE), NOW);
        ContextRepositoryRead empty = new ContextRepositoryRead("context-read:empty", repositoryReference(), SHA, NOW, 1, 0, 0, 0, false, Optional.empty());
        ReviewRun captured = original.withContextSourceRead(empty, Optional.empty(), NOW.plusSeconds(1));

        assertThat(captured.context().orElseThrow().sourceReads()).containsExactly(empty);
        assertThat(captured.evidence()).isEqualTo(original.evidence());
        assertThatThrownBy(() -> run().withContextSourceRead(empty, Optional.empty(), NOW)).isInstanceOf(IllegalStateException.class);
        ReviewContextSnapshot disabled = new ReviewContextSnapshot("off", false, 1, 42, Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        assertThatThrownBy(() -> run().withContext(disabled, ImmutableMap.of(), NOW).withContextSourceRead(empty, Optional.empty(), NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
    }

    private static ContextRepositoryRead repositoryRead(String id, int bytes, Optional<String> ref)
    {
        return new ContextRepositoryRead(id, repositoryReference(), SHA, NOW, 1, 1, 1, bytes, false, ref);
    }

    private static ContextReference.RepositoryFile repositoryReference()
    {
        return new ContextReference.RepositoryFile(42, RepoRef.of("example", "repo"), "README.md", SHA);
    }

    private static ReviewContextSnapshot snapshot(String id)
    {
        return new ReviewContextSnapshot(id, true, 1, 42, Optional.of(SHA), Optional.of(ContextPacket.create("Previous review reference")),
                ImmutableList.of(), ImmutableList.of(new ContextRelatedItem(EVIDENCE.ref(), ImmutableList.of("path:backend/core"), Optional.empty())),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of());
    }

    private static ReviewRun run()
    {
        return new ReviewRun("run-1", "review-1", 1, ReviewRun.Scope.FULL, "main", SHA, SHA, SHA, Optional.empty(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }
}
