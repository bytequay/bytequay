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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewContextSnapshot
{
    private static final String SHA = "1111111111111111111111111111111111111111";

    @Test
    void computesAndChecksPacketBytesAndDigest()
    {
        ContextPacket packet = ContextPacket.create("é");

        assertThat(packet.utf8Bytes()).isEqualTo(2);
        assertThat(packet.digest()).isEqualTo("sha256:4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c");
        assertThatThrownBy(() -> new ContextPacket(packet.text(), packet.digest(), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextPacket(packet.text(), "sha256:altered", 2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ContextPacket.create("é".repeat(ContextPacket.MAXIMUM_BYTES / 2)).utf8Bytes()).isEqualTo(ContextPacket.MAXIMUM_BYTES);
        assertThatThrownBy(() -> ContextPacket.create("é".repeat(ContextPacket.MAXIMUM_BYTES / 2 + 1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void distinguishesDisabledAndEnabledWithUnknownBaseline()
    {
        ReviewContextSnapshot disabled = new ReviewContextSnapshot("off", false, 0, 42, Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        ReviewContextSnapshot unknown = new ReviewContextSnapshot("unknown", true, 3, 42, Optional.empty(), Optional.of(ContextPacket.create("Local notes")),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of("Base revision could not be resolved"));

        assertThat(disabled.bootstrap()).isEmpty();
        assertThat(unknown.baselineSha()).isEmpty();
        assertThatThrownBy(() -> new ReviewContextSnapshot("unknown", true, 3, 42, Optional.empty(), Optional.of(ContextPacket.create("Local notes")),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewContextSnapshot("off", false, 0, 42, Optional.empty(), Optional.of(ContextPacket.create("Local notes")),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsClaimSourceMappingAndBoundsSelectedTextAcrossEntries()
    {
        ContextSelectedEntry first = entry("knowledge:repository", "é".repeat(2048));
        ContextSelectedEntry second = entry("lesson:ownership", "a".repeat(4096));
        ReviewContextSnapshot snapshot = snapshot(first, second);

        assertThat(snapshot.evidenceRefs()).containsExactlyInAnyOrder("ev-source", "ev-related");
        assertThat(snapshot.selectedEntries().getFirst().sourceEvidenceRefs()).containsEntry("source-doc", "ev-source");
        assertThatThrownBy(() -> snapshot(first, entry("lesson:ownership", "a".repeat(4097)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(first, first)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContextSelectedEntry("lesson:deferred", 1, "Deferred", Optional.of("Text"), ImmutableMap.of(),
                ImmutableList.of("repository"), ContextSelectedEntry.Applicability.UNVERIFIED, ContextSelectedEntry.Inclusion.DEFERRED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsCompleteReasonsForMaximumValidScope()
    {
        String prefix = "a".repeat(999) + "/";
        new ContextMatch.Selected(ImmutableList.of(prefix), ImmutableList.of());
        List<String> reasons = IntStream.range(0, 41).mapToObj(index -> "Changed path matches prefix: " + prefix + index).collect(toImmutableList());
        ContextSelectedEntry selected = new ContextSelectedEntry("knowledge:project", 1, "Project", Optional.of("Note"), ImmutableMap.of(), reasons,
                ContextSelectedEntry.Applicability.UNVERIFIED, ContextSelectedEntry.Inclusion.INCLUDED);
        ContextRelatedItem related = new ContextRelatedItem("ev-related", reasons, Optional.empty());

        assertThat(selected.matchReasons()).hasSize(41);
        assertThat(related.matchReasons()).hasSize(41);
        assertThatThrownBy(() -> new ContextRelatedItem("ev-related", ImmutableList.of("é".repeat(1001)), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> tooMany = ImmutableList.<String>builder().addAll(reasons).add("One too many").build();
        assertThatThrownBy(() -> new ContextSelectedEntry("knowledge:project", 1, "Project", Optional.of("Note"), ImmutableMap.of(), tooMany,
                ContextSelectedEntry.Applicability.UNVERIFIED, ContextSelectedEntry.Inclusion.INCLUDED)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsFingerprintBytesAcrossAllScopes()
    {
        Map<String, String> inputs = new LinkedHashMap<>();
        IntStream.range(0, 16).forEach(index -> inputs.put(String.valueOf(1000 + index), "é".repeat(1000)));
        inputs.put("rest", "a".repeat(700));
        List<ContextInputFingerprint> fingerprints = IntStream.range(0, 8)
                .mapToObj(index -> ContextInputFingerprint.create("scope-" + index, inputs, ImmutableList.of())).collect(toImmutableList());
        ReviewContextSnapshot snapshot = fingerprintsSnapshot(fingerprints);

        assertThat(snapshot.inputFingerprints().stream().mapToInt(ContextInputFingerprint::inputBytes).sum()).isEqualTo(ReviewContextSnapshot.MAXIMUM_INPUT_BYTES);
        List<ContextInputFingerprint> tooLarge = ImmutableList.<ContextInputFingerprint>builder().addAll(fingerprints)
                .add(ContextInputFingerprint.create("scope-8", inputs, ImmutableList.of())).build();
        assertThatThrownBy(() -> fingerprintsSnapshot(tooLarge)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UTF-8 budget");
    }

    @Test
    void requiresUniqueBoundedFingerprintScopesAndNoFingerprintsWhenDisabled()
    {
        List<ContextInputFingerprint> fingerprints = IntStream.range(0, 100)
                .mapToObj(index -> ContextInputFingerprint.create("scope-" + index, ImmutableMap.of(), ImmutableList.of())).collect(toImmutableList());
        assertThat(fingerprintsSnapshot(fingerprints).inputFingerprints()).hasSize(100);
        assertThatThrownBy(() -> fingerprintsSnapshot(ImmutableList.of(fingerprints.getFirst(), fingerprints.getFirst())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicates");
        List<ContextInputFingerprint> tooMany = ImmutableList.<ContextInputFingerprint>builder().addAll(fingerprints)
                .add(ContextInputFingerprint.create("scope-100", ImmutableMap.of(), ImmutableList.of())).build();
        assertThatThrownBy(() -> fingerprintsSnapshot(tooMany)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100 items");
        assertThatThrownBy(() -> new ReviewContextSnapshot("off", false, 0, 42, Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(fingerprints.getFirst()), ImmutableList.of(), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Disabled context");
    }

    @Test
    void retainsAtMost128DistinctSourceReceiptsAndRejectsReplacement()
    {
        ReviewContextSnapshot original = fingerprintsSnapshot(ImmutableList.of());
        ReviewContextSnapshot captured = original;
        for (int index = 0; index < ReviewContextSnapshot.MAXIMUM_SOURCE_READS; index++) {
            captured = captured.withSourceRead(repositoryRead("context-read:" + index, 42));
        }
        ReviewContextSnapshot full = captured;

        assertThat(full.sourceReads()).hasSize(128);
        assertThat(full.withSourceRead(full.sourceReads().getFirst())).isSameAs(full);
        assertThat(full.bootstrap()).isEqualTo(original.bootstrap());
        assertThat(full.evidenceRefs()).contains("source-receipt");
        assertThat(original.sourceReads()).isEmpty();
        assertThatThrownBy(() -> full.withSourceRead(repositoryRead("context-read:overflow", 42)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("128 items");
        assertThatThrownBy(() -> original.withSourceRead(repositoryRead("context-read:other-repo", 43)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another repository");
        ContextRepositoryRead replaced = new ContextRepositoryRead(full.sourceReads().getFirst().id(),
                new ContextReference.RepositoryFile(42, RepoRef.of("example", "repo"), "other.md", SHA), SHA, Instant.EPOCH, 1, 1, 1, 4, false, Optional.of("source-receipt"));
        assertThatThrownBy(() -> full.withSourceRead(replaced)).isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be replaced");
    }

    private static ContextRepositoryRead repositoryRead(String id, long repositoryId)
    {
        return new ContextRepositoryRead(id, new ContextReference.RepositoryFile(repositoryId, RepoRef.of("example", "repo"), "README.md", SHA),
                SHA, Instant.EPOCH, 1, 1, 1, 4, false, Optional.of("source-receipt"));
    }

    private static ReviewContextSnapshot fingerprintsSnapshot(List<ContextInputFingerprint> fingerprints)
    {
        return new ReviewContextSnapshot("context-1", true, 3, 42, Optional.of(SHA), Optional.of(ContextPacket.create("Local notes")),
                ImmutableList.of(), ImmutableList.of(), fingerprints, ImmutableList.of(), ImmutableList.of());
    }

    private static ContextSelectedEntry entry(String id, String text)
    {
        return new ContextSelectedEntry(id, 1, "Ownership", Optional.of(text), ImmutableMap.of("source-doc", "ev-source"),
                ImmutableList.of("path:backend/core"), ContextSelectedEntry.Applicability.MATCHED, ContextSelectedEntry.Inclusion.INCLUDED);
    }

    private static ReviewContextSnapshot snapshot(ContextSelectedEntry... entries)
    {
        return new ReviewContextSnapshot("context-1", true, 3, 42, Optional.of(SHA), Optional.of(ContextPacket.create("Local notes")),
                ImmutableList.copyOf(entries), ImmutableList.of(new ContextRelatedItem("ev-related", ImmutableList.of("path:backend/core"), Optional.empty())),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of());
    }
}
