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

import com.bytequay.core.domain.TeamActivityEvent;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteTeamActivityStore
        extends AbstractTestTransactionalSqliteStore
{
    @Autowired
    private SqliteTeamActivityStore store;

    @Test
    void sourceDeduplicationAccountIsolationAndRetentionSurviveDatabaseRoundTrip()
    {
        Instant time = Instant.now().minusSeconds(10);
        TeamActivityEvent publicEvent = new TeamActivityEvent("review:10", "PR_REVIEWED", 2, "alice", "owner/repo", 7, "Title",
                "https://github.com/owner/repo/pull/7", time, Optional.empty(), Optional.empty(), "PUBLIC_EVENTS", ImmutableList.of());
        TeamActivityEvent repositoryEvent = new TeamActivityEvent("review:10", "PR_REVIEWED", 2, "alice", "owner/repo", 7, "Title",
                "https://github.com/owner/repo/pull/7", time, Optional.of(1L), Optional.of("viewer"), "REPOSITORY", ImmutableList.of(2L));
        store.save(1, ImmutableList.of(publicEvent, repositoryEvent));
        store.save(2, ImmutableList.of(publicEvent));
        assertThat(store.findSince(1, time.minusSeconds(1))).singleElement().satisfies(event -> {
            assertThat(event.subjectAuthorId()).contains(1L);
            assertThat(event.viewerInteractionMemberIds()).containsExactly(2L);
        });
        assertThat(store.findSince(2, time.minusSeconds(1))).containsExactly(publicEvent);
        assertThat(store.findSince(3, time.minusSeconds(1))).isEmpty();
        store.deleteBefore(time.plusSeconds(1));
        assertThat(store.findSince(1, time.minusSeconds(1))).isEmpty();
    }
}
