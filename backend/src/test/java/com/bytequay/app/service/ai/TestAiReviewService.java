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
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.repository.AiReviewDraftStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.service.pr.PullRequestDetailInvalidator;
import com.bytequay.app.service.skills.ReviewSkillService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAiReviewService
{
    @Test
    void testPublishInvalidatesPullRequestDetailAfterGitHubReview()
    {
        PullRequestStore pullRequestStore = mock(PullRequestStore.class);
        PullRequestRepository gitHub = mock(PullRequestRepository.class);
        LlmReviewerRegistry registry = mock(LlmReviewerRegistry.class);
        AiReviewDraftStore draftStore = mock(AiReviewDraftStore.class);
        ReviewSkillService skillService = mock(ReviewSkillService.class);
        PullRequestDetailInvalidator detailInvalidator = mock(PullRequestDetailInvalidator.class);
        AiReviewService service = new AiReviewService(
                pullRequestStore,
                gitHub,
                registry,
                draftStore,
                skillService,
                detailInvalidator);
        AiReviewDraft draft = draft("DRAFT");
        AiReviewDraft published = draft("PUBLISHED");
        when(draftStore.byId(5L)).thenReturn(Optional.of(draft));
        when(draftStore.markPublished(5L)).thenReturn(published);
        Function<String, String> patForRepo = repo -> "pat";

        service.publish(patForRepo, 5L, "APPROVE", "looks good");

        verify(gitHub).createReview(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any(CreateReviewCommand.class));
        verify(detailInvalidator).invalidate("owner/repo", 7);
        InOrder order = inOrder(gitHub, detailInvalidator, draftStore);
        order.verify(gitHub).createReview(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any(CreateReviewCommand.class));
        order.verify(detailInvalidator).invalidate("owner/repo", 7);
        order.verify(draftStore).markPublished(5L);
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
}
