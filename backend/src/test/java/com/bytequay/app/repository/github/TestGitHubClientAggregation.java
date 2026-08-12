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
package com.bytequay.app.repository.github;

import com.bytequay.app.repository.github.GitHubPullRequestReadClient.GitHubNotification;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.GitHubNotification.NotificationRepo;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.GitHubNotification.Subject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.bytequay.app.domain.PullRequest.Origin.AUTHORED;
import static org.assertj.core.api.Assertions.assertThat;

class TestGitHubClientAggregation
{
    // ── attentionPrRef: notifications → review/mention PR refs ──────────────────

    private static GitHubNotification notification(String reason, String type, String repo, String url)
    {
        return new GitHubNotification(reason, new Subject("title", url, type), new NotificationRepo(repo));
    }

    @Test
    void testAttentionPrRefKeepsReviewRequest()
    {
        assertThat(GitHubPullRequestReadClient.attentionPrRef(notification(
                "review_requested", "PullRequest", "acme/widget",
                "https://api.github.com/repos/acme/widget/pulls/3405")))
                .hasValueSatisfying(ref -> {
                    assertThat(ref.repoFullName()).isEqualTo("acme/widget");
                    assertThat(ref.number()).isEqualTo(3405);
                });
    }

    @Test
    void testAttentionPrRefKeepsMention()
    {
        assertThat(GitHubPullRequestReadClient.attentionPrRef(notification(
                "mention", "PullRequest", "owner/repo",
                "https://api.github.com/repos/owner/repo/pulls/7")))
                .hasValueSatisfying(ref -> assertThat(ref.number()).isEqualTo(7));
    }

    @Test
    void testAttentionPrRefDropsOtherReasons()
    {
        // A plain author comment must not re-surface the PR.
        assertThat(GitHubPullRequestReadClient.attentionPrRef(notification(
                "comment", "PullRequest", "owner/repo",
                "https://api.github.com/repos/owner/repo/pulls/7"))).isEmpty();
    }

    @Test
    void testAttentionPrRefDropsNonPullRequestSubject()
    {
        // A mention on an Issue, not a PR — the dashboard lane is PR-only.
        assertThat(GitHubPullRequestReadClient.attentionPrRef(notification(
                "mention", "Issue", "owner/repo",
                "https://api.github.com/repos/owner/repo/issues/7"))).isEmpty();
    }

    @Test
    void testAttentionPrRefEmptyForMalformedUrl()
    {
        assertThat(GitHubPullRequestReadClient.attentionPrRef(notification(
                "review_requested", "PullRequest", "owner/repo",
                "https://api.github.com/repos/owner/repo/pulls/"))).isEmpty();
    }

    // ── extractRepo ────────────────────────────────────────────────────────────

    @Test
    void testExtractRepoNullReturnsEmpty()
    {
        assertThat(GitHubPullRequestReadClient.extractRepo(null)).isEmpty();
    }

    @Test
    void testExtractRepoValidApiUrl()
    {
        assertThat(GitHubPullRequestReadClient.extractRepo("https://api.github.com/repos/owner/my-repo"))
                .isEqualTo("owner/my-repo");
    }

    @Test
    void testExtractRepoNoReposSegmentReturnsOriginal()
    {
        assertThat(GitHubPullRequestReadClient.extractRepo("https://example.com/something"))
                .isEqualTo("https://example.com/something");
    }

    // ── GitHub error messages ─────────────────────────────────────────────────

    @Test
    void testExtractGitHubErrorMessageNullReturnsNull()
    {
        assertThat(GitHubApiSupport.extractGitHubErrorMessage(null)).isNull();
    }

    @Test
    void testExtractGitHubErrorMessageUsesTopLevelMessage()
    {
        assertThat(GitHubApiSupport.extractGitHubErrorMessage(
                "{\"message\":\"Validation Failed\"}"))
                .isEqualTo("Validation Failed");
    }

    @Test
    void testExtractGitHubErrorMessageIncludesFirstDetailedError()
    {
        assertThat(GitHubApiSupport.extractGitHubErrorMessage(
                "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"Can not approve your own pull request\"}]}"))
                .isEqualTo("Validation Failed: Can not approve your own pull request");
    }

    @Test
    void testExtractGitHubErrorMessageInvalidJsonReturnsNull()
    {
        assertThat(GitHubApiSupport.extractGitHubErrorMessage("not-json"))
                .isNull();
    }

    // ── search items → PullRequest: merged vs plainly closed ───────────────────

    private static GitHubSearchResponse.Item searchItem(Instant mergedAt)
    {
        return new GitHubSearchResponse.Item(
                1L, 57, "title", "https://github.com/owner/repo/pull/57",
                Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-03T03:37:02Z"),
                Instant.parse("2026-08-03T03:37:02Z"), "closed",
                "https://api.github.com/repos/owner/repo", null, null, false,
                mergedAt == null ? null : new GitHubSearchResponse.PullRequestLink(mergedAt));
    }

    @Test
    void testSearchItemCarriesMergedAt()
    {
        // Search reports a merged PR as state=closed. Dropping merged_at made
        // every downstream renderer call it "Closed" instead of "Merged".
        assertThat(GitHubPullRequestReadClient.toPullRequest(
                searchItem(Instant.parse("2026-08-03T03:37:02Z")), AUTHORED).mergedAt())
                .isEqualTo(Instant.parse("2026-08-03T03:37:02Z"));
    }

    @Test
    void testSearchItemWithoutMergeLeavesMergedAtNull()
    {
        assertThat(GitHubPullRequestReadClient.toPullRequest(searchItem(null), AUTHORED).mergedAt()).isNull();
    }
}
