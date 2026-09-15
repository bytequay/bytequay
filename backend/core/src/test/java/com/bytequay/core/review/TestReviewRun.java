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
import com.bytequay.core.context.ContextRelatedItem;
import com.bytequay.core.context.ReviewContextSnapshot;
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

    private static ReviewContextSnapshot snapshot(String id)
    {
        return new ReviewContextSnapshot(id, true, 1, 42, Optional.of(SHA), Optional.of(ContextPacket.create("Previous review reference")),
                ImmutableList.of(), ImmutableList.of(new ContextRelatedItem(EVIDENCE.ref(), ImmutableList.of("path:backend/core"), Optional.empty())),
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
