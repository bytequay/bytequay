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

import com.bytequay.app.repository.github.GitHubClient.GitHubNotification;
import com.bytequay.app.repository.github.GitHubClient.GitHubNotification.NotificationRepo;
import com.bytequay.app.repository.github.GitHubClient.GitHubNotification.Subject;
import org.junit.jupiter.api.Test;

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
        assertThat(GitHubClient.attentionPrRef(notification(
                "review_requested", "PullRequest", "starburstdata/starburst-enterprise-trino",
                "https://api.github.com/repos/starburstdata/starburst-enterprise-trino/pulls/3405")))
                .hasValueSatisfying(ref -> {
                    assertThat(ref.repoFullName()).isEqualTo("starburstdata/starburst-enterprise-trino");
                    assertThat(ref.number()).isEqualTo(3405);
                });
    }

    @Test
    void testAttentionPrRefKeepsMention()
    {
        assertThat(GitHubClient.attentionPrRef(notification(
                "mention", "PullRequest", "owner/repo",
                "https://api.github.com/repos/owner/repo/pulls/7")))
                .hasValueSatisfying(ref -> assertThat(ref.number()).isEqualTo(7));
    }

    @Test
    void testAttentionPrRefDropsOtherReasons()
    {
        // A plain author comment must not re-surface the PR.
        assertThat(GitHubClient.attentionPrRef(notification(
                "comment", "PullRequest", "owner/repo",
                "https://api.github.com/repos/owner/repo/pulls/7"))).isEmpty();
    }

    @Test
    void testAttentionPrRefDropsNonPullRequestSubject()
    {
        // A mention on an Issue, not a PR — the dashboard lane is PR-only.
        assertThat(GitHubClient.attentionPrRef(notification(
                "mention", "Issue", "owner/repo",
                "https://api.github.com/repos/owner/repo/issues/7"))).isEmpty();
    }

    @Test
    void testAttentionPrRefEmptyForMalformedUrl()
    {
        assertThat(GitHubClient.attentionPrRef(notification(
                "review_requested", "PullRequest", "owner/repo",
                "https://api.github.com/repos/owner/repo/pulls/"))).isEmpty();
    }

    // ── extractRepo ────────────────────────────────────────────────────────────

    @Test
    void testExtractRepoNullReturnsEmpty()
    {
        assertThat(GitHubClient.extractRepo(null)).isEmpty();
    }

    @Test
    void testExtractRepoValidApiUrl()
    {
        assertThat(GitHubClient.extractRepo("https://api.github.com/repos/owner/my-repo"))
                .isEqualTo("owner/my-repo");
    }

    @Test
    void testExtractRepoNoReposSegmentReturnsOriginal()
    {
        assertThat(GitHubClient.extractRepo("https://example.com/something"))
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
}
