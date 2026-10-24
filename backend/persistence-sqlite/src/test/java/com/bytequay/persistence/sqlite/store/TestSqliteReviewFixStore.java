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

import com.bytequay.core.discussion.ReviewFixReply;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteReviewFixStore
        extends AbstractTestTransactionalSqliteStore
{
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

    @Autowired
    private SqliteReviewFixStore store;

    @Test
    void keepsTheFirstRequestOfACommentAndEveryStepOfItsReply()
    {
        ReviewFixReply requested = ReviewFixReply.requested("task-1", "owner/repo", 12, 10, 11, "review-fix:task-1:11", NOW);
        store.insertIfAbsent(requested);
        store.insertIfAbsent(ReviewFixReply.requested("task-1", "owner/repo", 12, 10, 11, "another-key", NOW.plusSeconds(1)));
        store.insertIfAbsent(ReviewFixReply.requested("task-1", "owner/repo", 12, 20, 21, "review-fix:task-1:11", NOW));
        store.insertIfAbsent(ReviewFixReply.requested("task-2", "owner/repo", 12, 10, 11, "review-fix:task-2:11", NOW));

        assertThat(store.listByTask("task-1")).extracting(ReviewFixReply::commentId).containsExactly(11L, 21L);
        assertThat(store.listByTask("task-1").getFirst()).isEqualTo(requested);

        ReviewFixReply resolved = requested.answered("Renamed it.", true).postStarted(NOW).posted(NOW.plusSeconds(2)).resolved(NOW.plusSeconds(3));
        store.update(resolved);
        assertThat(store.listByTask("task-1").getFirst()).isEqualTo(resolved);

        ReviewFixReply failed = resolved.failed("Replied, but the thread could not be resolved");
        store.update(failed);
        assertThat(store.listByTask("task-1").getFirst()).isEqualTo(failed);
        assertThat(store.listByTask("task-2")).hasSize(1);
    }
}
