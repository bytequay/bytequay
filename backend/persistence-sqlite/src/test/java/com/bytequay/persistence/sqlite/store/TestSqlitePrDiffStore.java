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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.StoredPrDiff;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqlitePrDiffStore
        extends AbstractTestTransactionalSqliteStore
{
    @Autowired
    private SqlitePrDiffStore store;

    @Autowired
    private EntityManager entities;

    @Test
    void bindsDefaultFileBodiesToTheirFetchedCommits()
    {
        long prId = 88;
        String base = "a".repeat(40);
        String head = "b".repeat(40);
        Instant validated = Instant.parse("2026-09-20T00:00:00Z");
        StoredPrDiff diff = new StoredPrDiff(ImmutableList.of(), ImmutableList.of(), ImmutableMap.of(), Optional.of(base), Optional.of(head), Optional.of(validated));
        store.saveDefault(prId, diff, PrDiffEtags.none());
        DiffFileContent content = new DiffFileContent("before", "after");

        assertThat(store.find(prId, null, null)).contains(diff);
        assertThat(store.defaultDiffValidated(prId, base, head)).isTrue();
        // A same head with a different base is another diff.
        assertThat(store.defaultDiffValidated(prId, "d".repeat(40), head)).isFalse();
        assertThat(store.saveDefaultFileContent(prId, base, "c".repeat(40), "file.txt", content)).isFalse();
        assertThat(store.find(prId, null, null).orElseThrow().fileContents()).isEmpty();
        assertThat(store.saveDefaultFileContent(prId, base, head, "file.txt", content)).isTrue();
        assertThat(store.find(prId, null, null).orElseThrow().fileContents()).containsEntry("file.txt", content);
    }

    @Test
    void keepsDefaultAndCommitRangeSnapshotsIndependent()
    {
        long prId = 42;
        DiffFile allFile = new DiffFile("all.txt", "modified", 1, 0, "+all");
        DiffCommit commit = new DiffCommit("b".repeat(40), "a".repeat(40), "change", "alice", Instant.parse("2026-08-24T00:00:00Z"));
        DiffFile rangeFile = new DiffFile("range.txt", "added", 2, 0, "+range");
        store.save(prId, null, null, new StoredPrDiff(ImmutableList.of(allFile), ImmutableList.of(commit)));
        store.save(prId, "a".repeat(40), "b".repeat(40), new StoredPrDiff(ImmutableList.of(rangeFile), ImmutableList.of()));

        DiffFileContent content = new DiffFileContent("old", "new");
        store.saveFileContent(prId, null, null, "all.txt", content);

        assertThat(store.find(prId, null, null).orElseThrow().files()).containsExactly(allFile);
        assertThat(store.find(prId, null, null).orElseThrow().commits()).containsExactly(commit);
        assertThat(store.find(prId, null, null).orElseThrow().fileContents())
                .containsEntry("all.txt", content);
        assertThat(store.find(prId, "a".repeat(40), "b".repeat(40)).orElseThrow().files())
                .containsExactly(rangeFile);

        store.deleteByPrIds(ImmutableSet.of(prId));
        assertThat(store.find(prId, null, null)).isEmpty();
        assertThat(store.find(prId, "a".repeat(40), "b".repeat(40))).isEmpty();
    }

    @Test
    void refreshingDefaultSnapshotKeepsItsIdAndDoesNotChangeOtherSnapshots()
    {
        StoredPrDiff original = new StoredPrDiff(ImmutableList.of(new DiffFile("old.txt", "added", 1, 0, "+old")), ImmutableList.of());
        StoredPrDiff updated = new StoredPrDiff(ImmutableList.of(new DiffFile("new.txt", "added", 1, 0, "+new")), ImmutableList.of());
        store.save(42, null, null, original);
        store.save(42, "base", "head", original);
        store.save(43, null, null, original);
        entities.flush();
        entities.clear();

        store.save(42, null, null, updated);
        entities.flush();
        entities.clear();
        assertThat(store.find(42, null, null)).contains(updated);
        assertThat(store.find(42, "base", "head").orElseThrow().files()).isEqualTo(original.files());
        assertThat(store.find(43, null, null)).contains(original);
        assertThat(store.defaultDiffValidated(42, "base", "head")).isFalse();
        assertThat(entities.createQuery("SELECT count(d) FROM PrDiffEntity d", Long.class).getSingleResult()).isEqualTo(3);

        store.deleteByPrIds(ImmutableSet.of(42L));
        entities.flush();
        entities.clear();
        assertThat(store.find(42, null, null)).isEmpty();
        assertThat(store.find(42, "base", "head")).isEmpty();
        assertThat(store.find(43, null, null)).contains(original);
    }
}
