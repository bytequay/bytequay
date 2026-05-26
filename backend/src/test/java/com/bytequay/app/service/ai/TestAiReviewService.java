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
package com.bytequay.app.service.ai;

import com.bytequay.app.domain.AiReviewDraft;
import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.PullRequestReview;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.domain.ReviewSkill;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.repository.AiReviewDraftStore;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.ReviewSkillStore;
import com.bytequay.app.service.pr.GitHubResponseCache;
import com.bytequay.app.service.pr.PullRequestDetailInvalidator;
import com.bytequay.app.service.skills.ReviewSkillService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class TestAiReviewService
{
    @Test
    void testPublishInvalidatesPullRequestDetailAfterGitHubReview()
    {
        List<String> events = new ArrayList<>();
        RecordingGitHub gitHub = new RecordingGitHub(events);
        RecordingDraftStore draftStore = new RecordingDraftStore(events, draft("DRAFT"), draft("PUBLISHED"));
        RecordingInvalidator detailInvalidator = new RecordingInvalidator(events);
        AiReviewService service = new AiReviewService(
                new UnsupportedPullRequestStore(),
                gitHub,
                new LlmReviewerRegistry(List.of(), new EmptyAppSettingsStore()),
                draftStore,
                new ReviewSkillService(new EmptyReviewSkillStore()),
                detailInvalidator);
        Function<String, String> patForRepo = repo -> "pat";

        AiReviewDraft published = service.publish(patForRepo, 5L, "APPROVE", "looks good");

        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(gitHub.pat).isEqualTo("pat");
        assertThat(gitHub.ref).isEqualTo(PullRequestRef.of("owner", "repo", 7));
        assertThat(gitHub.command).isNotNull();
        assertThat(detailInvalidator.repo).isEqualTo("owner/repo");
        assertThat(detailInvalidator.number).isEqualTo(7);
        assertThat(events).containsExactly("github", "invalidate", "publish");
    }

    private static AiReviewDraft draft(String status)
    {
        return new AiReviewDraft(
                5L,
                123L,
                "owner/repo",
                7,
                "summary",
                "provider",
                "model",
                "abc123",
                status,
                Instant.parse("2026-05-08T00:00:00Z"),
                Instant.parse("2026-05-08T00:00:00Z"),
                ImmutableList.of());
    }

    private static final class RecordingGitHub
            implements PullRequestRepository
    {
        private final List<String> events;
        private String pat;
        private PullRequestRef ref;
        private CreateReviewCommand command;

        private RecordingGitHub(List<String> events)
        {
            this.events = events;
        }

        @Override
        public PullRequestReview createReview(String pat, PullRequestRef pr, CreateReviewCommand command)
        {
            events.add("github");
            this.pat = pat;
            this.ref = pr;
            this.command = command;
            return null;
        }
    }

    private static final class RecordingDraftStore
            implements AiReviewDraftStore
    {
        private final List<String> events;
        private final Map<Long, AiReviewDraft> drafts = new HashMap<>();
        private final AiReviewDraft published;

        private RecordingDraftStore(List<String> events, AiReviewDraft draft, AiReviewDraft published)
        {
            this.events = events;
            this.published = published;
            drafts.put(draft.id(), draft);
        }

        @Override
        public Optional<AiReviewDraft> byId(long draftId)
        {
            return Optional.ofNullable(drafts.get(draftId));
        }

        @Override
        public AiReviewDraft markPublished(long draftId)
        {
            events.add("publish");
            drafts.put(draftId, published);
            return published;
        }

        @Override public AiReviewDraft save(long prId, String repo, int number, String headSha, ReviewOutput output)
        {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<AiReviewDraft> latestForPr(long prId) { throw new UnsupportedOperationException(); }
        @Override public List<AiReviewDraft> historyForPr(long prId) { throw new UnsupportedOperationException(); }
        @Override public AiReviewDraft updateCommentBody(long draftId, long commentId, String editedBody) { throw new UnsupportedOperationException(); }
        @Override public AiReviewDraft deleteComment(long draftId, long commentId) { throw new UnsupportedOperationException(); }
        @Override public AiReviewDraft setCommentDismissed(long draftId, long commentId, boolean dismissed) { throw new UnsupportedOperationException(); }
        @Override public void delete(long draftId) { throw new UnsupportedOperationException(); }
        @Override public AiReviewDraft findOrCreateActive(long prId, String repo, int number, String headSha) { throw new UnsupportedOperationException(); }
        @Override public AiReviewDraft stageHumanComment(long draftId, String filePath, int lineNumber, String side, Integer startLine, String startSide, String body) { throw new UnsupportedOperationException(); }
    }

    private static final class RecordingInvalidator
            extends PullRequestDetailInvalidator
    {
        private final List<String> events;
        private String repo;
        private int number;

        private RecordingInvalidator(List<String> events)
        {
            super(new UnsupportedPullRequestStore(), new UnsupportedPrDetailStore(), new GitHubResponseCache());
            this.events = events;
        }

        @Override
        public void invalidate(String repo, int number)
        {
            events.add("invalidate");
            this.repo = repo;
            this.number = number;
        }
    }

    private static final class EmptyAppSettingsStore
            implements AppSettingsStore
    {
        @Override public Optional<String> get(String key) { return Optional.empty(); }
        @Override public void set(String key, String value) { throw new UnsupportedOperationException(); }
    }

    private static final class EmptyReviewSkillStore
            implements ReviewSkillStore
    {
        @Override public List<ReviewSkill> list() { return List.of(); }
        @Override public Optional<ReviewSkill> byId(long id) { return Optional.empty(); }
        @Override public Optional<ReviewSkill> findByRepo(String repo) { return Optional.empty(); }
        @Override public ReviewSkill create(String skillName, String repo, String llmProvider, String description, String context) { throw new UnsupportedOperationException(); }
        @Override public ReviewSkill update(long id, String skillName, String repo, String llmProvider, String description, String context) { throw new UnsupportedOperationException(); }
        @Override public void delete(long id) { throw new UnsupportedOperationException(); }
        @Override public ReviewSkill setEnabled(long id, boolean enabled) { throw new UnsupportedOperationException(); }
    }

    private static final class UnsupportedPullRequestStore
            implements PullRequestStore
    {
        @Override public List<PullRequest> findAll() { throw new UnsupportedOperationException(); }
        @Override public void replaceAll(List<PullRequest> pullRequests) { throw new UnsupportedOperationException(); }
        @Override public Optional<Instant> lastSyncedAt() { throw new UnsupportedOperationException(); }
        @Override public Map<Long, Instant> findUpdatedAtMap() { throw new UnsupportedOperationException(); }
        @Override public Set<Long> findIdsMissingEnrichment() { throw new UnsupportedOperationException(); }
        @Override public Optional<Long> findIdByRepoAndNumber(String repo, int number) { throw new UnsupportedOperationException(); }
        @Override public Optional<PullRequest> findById(long prId) { throw new UnsupportedOperationException(); }
        @Override public void updateEnrichment(long prId, PullRequestDetail.CiStatus ciStatus, int additions, int deletions, int commentCount, AttentionReason attentionReason, Boolean mergeable, String mergeableState, Instant headPushedAt, Map<String, String> reviewerVerdicts, String headRef) { throw new UnsupportedOperationException(); }
        @Override public void updateCiStatus(long prId, PullRequestDetail.CiStatus ciStatus) { throw new UnsupportedOperationException(); }
    }

    private static final class UnsupportedPrDetailStore
            implements PrDetailStore
    {
        @Override public Optional<StoredPrDetail> find(long prId) { throw new UnsupportedOperationException(); }
        @Override public void save(long prId, StoredPrDetail detail) { throw new UnsupportedOperationException(); }
        @Override public void deleteByPrIds(Set<Long> prIds) { throw new UnsupportedOperationException(); }
    }
}
