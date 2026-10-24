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

import com.bytequay.core.domain.AttentionMark;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteAttentionMarkStore
        extends AbstractTestTransactionalSqliteStore
{
    @Autowired
    private SqliteAttentionMarkStore store;

    @Autowired
    private EntityManager entities;

    @Test
    void savesReplacesAndRemovesMarksByKey()
    {
        AttentionMark acknowledged = new AttentionMark("owner/repository#31402", AttentionMark.Kind.ACK, Optional.empty(), "2026-10-17T01:02:00Z",
                Optional.of("[\"2026-10-17T01:02:00Z\",\"open\"]"), "acknowledged", Optional.empty());
        AttentionMark snoozed = new AttentionMark("task:7d0e", AttentionMark.Kind.SNOOZE, Optional.of(Instant.parse("2026-10-18T01:00:00Z")), "2026-10-17T01:02:00Z",
                Optional.empty(), "tomorrow", Optional.of("{\"key\":\"task:7d0e\",\"title\":\"Expose snapshot ids\"}"));
        store.change(ImmutableList.of(acknowledged, snoozed), ImmutableList.of());
        entities.flush();
        entities.clear();
        assertThat(store.all()).containsExactlyInAnyOrder(acknowledged, snoozed);

        // A second mark on the same item replaces the first, and removing an unknown key is not an error.
        AttentionMark again = new AttentionMark("owner/repository#31402", AttentionMark.Kind.SNOOZE, Optional.empty(), "2026-10-17T03:00:00Z", Optional.empty(),
                "until activity", Optional.empty());
        store.change(ImmutableList.of(again), ImmutableList.of("task:7d0e", "jira:gone:1"));
        entities.flush();
        entities.clear();
        assertThat(store.all()).containsExactly(again);
    }
}
