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

import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.persistence.sqlite.entity.PrTimelineEntity;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PrCheckRunJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrReviewJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrTimelineJpaRepository;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TestSqlitePrDetailCredentialScope
        extends AbstractTestTransactionalSqliteStore
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Autowired
    private SqlitePrDetailStore store;

    @Autowired
    private PullRequestJpaRepository requests;

    @Autowired
    private PrTimelineJpaRepository timeline;

    @Autowired
    private PrReviewJpaRepository reviews;

    @Autowired
    private PrCheckRunJpaRepository checks;

    @Autowired
    private SqlitePrViewStateStore views;

    @Autowired
    private SqliteReviewDraftStore drafts;

    @Autowired
    private EntityManager entities;

    @Test
    void absentScopeIsUnknownAndEverySameOwnerSavePreservesTheStamp()
    {
        long prId = request();
        assertThat(store.findCredentialScope(prId)).isEmpty();
        store.saveMetadata(prId, detail());
        assertThat(store.findCredentialScope(prId)).isEmpty();
        store.setCredentialScope(prId, "github-global:revision-a");

        store.saveMetadata(prId, detail());
        store.saveDiscussion(prId, detail(), NOW);
        store.saveStatus(prId, detail());
        store.saveCached(prId, detail());
        store.save(prId, detail());
        entities.flush();
        entities.clear();

        assertThat(store.findCredentialScope(prId)).contains("github-global:revision-a");
        assertThat(store.find(prId).orElseThrow().raw().body()).isEqualTo("remote description");
    }

    @Test
    void clearingRemoteDataKeepsLocalTimelineDraftAndViewState()
    {
        long prId = request();
        store.save(prId, detail());
        store.setCredentialScope(prId, "github-global:old-revision");
        PrTimelineEvent local = new PrTimelineEvent(null, "commented", "alice", NOW, "local action", Optional.of("local-action"));
        timeline.saveAndFlush(new PrTimelineEntity().updateFrom(prId, local));
        views.markViewed(prId);
        ReviewDraft draft = drafts.save(ReviewDraft.empty("owner/project", 7, NOW));

        store.clearRemote(prId);
        entities.flush();
        entities.clear();

        assertThat(store.find(prId)).isEmpty();
        assertThat(store.findCredentialScope(prId)).isEmpty();
        assertThat(reviews.findByPrId(prId)).isEmpty();
        assertThat(checks.findByPrId(prId)).isEmpty();
        assertThat(timeline.findByPrIdOrderByTimestampAscIdAsc(prId)).singleElement().satisfies(event -> assertThat(event.toDomain()).isEqualTo(local));
        assertThat(requests.findByIdAndHostingRepositoryIsNull(prId)).isPresent();
        assertThat(views.findByPrIds(ImmutableSet.of(prId))).containsKey(prId);
        assertThat(drafts.find("owner/project", 7)).contains(draft);

        store.saveMetadata(prId, detail());
        store.setCredentialScope(prId, "github-global:new-revision");
        assertThat(store.findCredentialScope(prId)).contains("github-global:new-revision");
    }

    @Test
    void scopeCannotCreateAnEmptyDetailOrAcceptInvalidIdentity()
    {
        long prId = request();
        assertThatIllegalArgumentException().isThrownBy(() -> store.setCredentialScope(prId, "github-global:revision"));
        assertThatIllegalArgumentException().isThrownBy(() -> store.findCredentialScope(0));
        assertThatIllegalArgumentException().isThrownBy(() -> store.clearRemote(0));
        store.saveMetadata(prId, detail());
        assertThatIllegalArgumentException().isThrownBy(() -> store.setCredentialScope(prId, " "));
        assertThat(store.findCredentialScope(prId)).isEmpty();
    }

    private long request()
    {
        PullRequestEntity row = new PullRequestEntity();
        row.setRepo("owner/project");
        row.setNumber(7);
        row.setTitle("Title");
        row.setOrigin("AUTHORED");
        row.setLabels(ImmutableList.of());
        row.setSyncedAt(NOW);
        return requests.saveAndFlush(row).getId();
    }

    private static StoredPrDetail detail()
    {
        PrRawDetail raw = new PrRawDetail("Title", "https://github.com/owner/project/pull/7", "open", "alice", null, NOW, NOW, null, "remote description",
                ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, null, null,
                0, 0, 0, 0, ImmutableList.of(), "a".repeat(40), "feature", "main", "b".repeat(40));
        return new StoredPrDetail(raw, ImmutableList.of(new PrReviewState(1L, "alice", null, false, "APPROVED", NOW, "remote review", null, null)),
                ImmutableList.of(new PrTimelineEvent(2L, "commented", "alice", NOW, "remote comment", Optional.empty())),
                ImmutableList.of(new PrCheckRunState(3L, "build", "completed", "success", null, "check_run", NOW, NOW)), ImmutableList.of(), true);
    }
}
