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
package com.bytequay.app.service.hosting;

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubRateLimitException;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@ExtendWith(MockitoExtension.class)
class TestGitHubPullRequestContentReader
{
    private static final PullRequestRef REF = PullRequestRef.of("owner", "repo", 7);

    @Mock private GitHubPullRequestReadClient gitHub;
    @Mock private GitHubActionsClient actions;
    @Mock private GitHubAccountClient accounts;

    private GitHubPullRequestContentReader fetcher;

    @BeforeEach
    void setUp()
    {
        fetcher = new GitHubPullRequestContentReader(gitHub, actions, accounts, Runnable::run);
        lenient().when(gitHub.fetchPrCommits("pat", REF)).thenReturn(ImmutableList.of());
        lenient().when(gitHub.fetchPrReviewThreads("pat", REF)).thenReturn(ImmutableList.of());
    }

    @Test
    void normalizesOnlyExecutableJobsAndPreservesTheirConditionalCache()
    {
        HostingInstance instance = new HostingInstance(1, GITHUB_SERVER.provider(), GITHUB_SERVER.webBaseUrl(), GITHUB_SERVER.apiBaseUrl());
        HostingAccess access = new HostingAccess(instance, "pat");
        HostingRepository repository = new HostingRepository(2, 1, "99", "owner/repo", URI.create("https://github.com/owner/repo"),
                URI.create("https://github.com/owner/repo.git"), Optional.empty(), Optional.of("main"));
        PrCheckRunState cachedJob = new PrCheckRunState(42L, "test", "completed", "failure",
                "https://github.com/owner/repo/actions/runs/5/job/42", "job", null, null);
        PrCheckRunState nativeCheck = new PrCheckRunState(42L, "test", "completed", "failure", cachedJob.detailsUrl(), "check_run", null, null);
        PrCheckRunState external = new PrCheckRunState(44L, "external", "completed", "success", "https://checks.example/job/44", "check_run", null, null);
        when(actions.refreshPrCheckRuns("pat", "owner", "repo", "head", ImmutableList.of(nativeCheck), Optional.of("checks"), Optional.empty()))
                .thenReturn(new GitHubActionsClient.PrCheckRunsFetch(ImmutableList.of(nativeCheck, external), Optional.of("checks"), Optional.empty()));

        var result = fetcher.checks(access, repository, 7, "head", ImmutableList.of(cachedJob), Optional.of("checks"), Optional.empty());

        assertThat(result.checkRuns()).containsExactly(cachedJob, external);
        assertThat(result.checkRunsEtag()).contains("checks");
    }

    @Test
    void assemblesDiscussionAndPreservesCachedChecks()
    {
        PrReviewState review = new PrReviewState(null, "alice", null, false, "APPROVED", null, null, null, null);
        PrCheckRunState check = new PrCheckRunState(1L, "build", "completed", "success", null, "check_run", null, null);
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(ImmutableList.of(review));
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of());
        StoredPrDetail existing = new StoredPrDetail(raw("head"), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(check), ImmutableList.of(), true);

        StoredPrDetail stored = GitHubPullRequestContentReader.await(fetcher.fetchDiscussion("pat", REF, existing));

        assertThat(stored.raw().body()).isEqualTo("body");
        assertThat(stored.reviews()).containsExactly(review);
        assertThat(stored.timeline()).isEmpty();
        assertThat(stored.checkRuns()).containsExactly(check);
        assertThat(stored.viewerCanWrite()).isTrue();
    }

    @Test
    void treatsNullListsAsEmpty()
    {
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(null);
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(null);
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(null);

        StoredPrDetail stored = GitHubPullRequestContentReader.await(fetcher.fetchDiscussion("pat", REF, existing()));

        assertThat(stored.reviews()).isEmpty();
        assertThat(stored.timeline()).isEmpty();
        assertThat(stored.checkRuns()).isEmpty();
    }

    @Test
    void preservesMarkdownLinksAndCodeInDiscussionBodies()
    {
        String body = "Upstream PR: [owner/project#31610](https://github.com/owner/project/pull/31610)\n"
                + "Keep `#31610` and `https://github.com/owner/project/pull/31610` literal.";
        Instant commentedAt = Instant.parse("2026-08-15T10:00:00Z");
        PrTimelineEvent issueComment = comment(1L, "alice", commentedAt, body);
        PrTimelineComment reviewComment = new PrTimelineComment(2L, "bob", null, false, commentedAt, body, null, null, null, ImmutableList.of());
        PrTimelineEvent thread = new PrTimelineEvent(2L, "review_thread", "bob", commentedAt, body,
                PrTimelinePayload.builder().comments(ImmutableList.of(reviewComment)).build(), Optional.empty());
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of(issueComment));
        when(gitHub.fetchPrReviewThreads("pat", REF)).thenReturn(ImmutableList.of(thread));

        StoredPrDetail stored = GitHubPullRequestContentReader.await(fetcher.fetchDiscussion("pat", REF, existing()));

        assertThat(stored.timeline()).containsExactly(issueComment, thread);
    }

    @Test
    void preservesUnavailableChecksWithoutHeadSha()
    {
        StoredPrDetail existing = new StoredPrDetail(raw(null), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(StoredPrDetail.CHECKS), false);

        StoredPrDetail stored = GitHubPullRequestContentReader.await(fetcher.fetchDiscussion("pat", REF, existing));

        assertThat(stored.checkRuns()).isEmpty();
        assertThat(stored.unavailableSections()).containsExactly(StoredPrDetail.CHECKS);
    }

    @Test
    void reportsAnOptionalSourceFailureWithoutHidingThePullRequest()
    {
        ResponseStatusException notFound = new ResponseStatusException(NOT_FOUND, "gone");
        when(gitHub.fetchPrReviews("pat", REF)).thenThrow(notFound);
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of());

        StoredPrDetail stored = GitHubPullRequestContentReader.await(fetcher.fetchDiscussion("pat", REF, existing()));

        assertThat(stored.reviews()).isEmpty();
        assertThat(stored.unavailableSections()).containsExactly("reviews");
    }

    @Test
    void aRateLimitOnSeveralSourcesKeepsItsStatus()
    {
        when(gitHub.fetchPrReviews("pat", REF)).thenThrow(new GitHubRateLimitException("secondary", 60));
        when(gitHub.fetchPrTimeline("pat", REF)).thenThrow(new GitHubRateLimitException("secondary", 60));

        assertThatThrownBy(() -> GitHubPullRequestContentReader.await(fetcher.fetchDiscussion("pat", REF, existing()))).isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    void interruptingTheRefreshCancelsItsSourceReads()
            throws Exception
    {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        Answer<?> blocked = invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            }
            catch (InterruptedException failure) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return ImmutableList.of();
        };
        when(gitHub.fetchPrReviews("pat", REF)).thenAnswer(blocked);
        when(gitHub.fetchPrTimeline("pat", REF)).thenAnswer(blocked);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var discussion = new GitHubPullRequestContentReader(gitHub, actions, accounts, executor).fetchDiscussion("pat", REF, existing());
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread caller = Thread.startVirtualThread(() -> {
            try {
                GitHubPullRequestContentReader.await(discussion);
            }
            catch (RuntimeException caught) {
                failure.set(caught);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertThat(started.await(5, SECONDS)).isTrue();
            caller.interrupt();
            assertThat(caller.join(Duration.ofSeconds(5))).isTrue();
            assertThat(failure.get()).isInstanceOfSatisfying(ResponseStatusException.class,
                    caught -> assertThat(caught.getStatusCode()).isEqualTo(SERVICE_UNAVAILABLE));
            assertThat(interruptRestored).isTrue();
            assertThat(discussion.isCancelled()).isTrue();
            assertThat(interrupted.await(5, SECONDS)).isTrue();
        }
        finally {
            discussion.cancel(true);
            caller.interrupt();
            executor.shutdownNow();
            executor.close();
        }
    }

    @Test
    void prefersIssueCommentCopyOverTimelineDuplicate()
    {
        Instant commentedAt = Instant.parse("2026-08-15T10:00:00Z");
        PrTimelineEvent timelineCopy = comment(1L, "alice", commentedAt, "plain copy");
        PrTimelineEvent labeled = new PrTimelineEvent(2L, "labeled", "bob", commentedAt, null, Optional.empty());
        PrTimelineEvent issueCopy = comment(3L, "alice", commentedAt, "copy with reactions");
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(ImmutableList.of(timelineCopy, labeled));
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of(issueCopy));

        StoredPrDetail stored = GitHubPullRequestContentReader.await(fetcher.fetchDiscussion("pat", REF, existing()));

        assertThat(stored.timeline()).containsExactly(labeled, issueCopy);
    }

    @Test
    void refetchesTheFullMutableTimeline()
    {
        when(gitHub.fetchPrReviews("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrTimeline("pat", REF)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", REF)).thenReturn(ImmutableList.of());

        GitHubPullRequestContentReader.await(fetcher.fetchDiscussion("pat", REF, existing()));

        verify(gitHub).fetchPrTimeline("pat", REF);
        verify(gitHub).fetchPrIssueComments("pat", REF);
    }

    @Test
    void discussionRefreshReusesMetadataAndChecksWithoutFetchingThemAgain()
    {
        PrRawDetail raw = raw("head");
        StoredPrDetail existing = new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false);

        StoredPrDetail refreshed = GitHubPullRequestContentReader.await(fetcher.fetchDiscussion("pat", REF, existing));

        assertThat(refreshed.raw()).isSameAs(raw);
        verify(gitHub).fetchPrIssueComments("pat", REF);
        verify(gitHub).fetchPrReviews("pat", REF);
        verify(gitHub, never()).fetchPrCommits("pat", REF);
    }

    private static StoredPrDetail existing()
    {
        return new StoredPrDetail(raw("head"), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(StoredPrDetail.COMMITS), false);
    }

    private static PrRawDetail raw(String headSha)
    {
        return new PrRawDetail(
                null, null, null, null, null, null, null, null, "body", ImmutableList.of(), null, ImmutableList.of(), null, false, true, "clean", 4, 2, 1, 0, ImmutableList.of(),
                headSha, "feature", null);
    }

    private static PrTimelineEvent comment(Long githubId, String actor, Instant timestamp, String body)
    {
        return new PrTimelineEvent(githubId, "commented", actor, timestamp, body, Optional.empty());
    }
}
