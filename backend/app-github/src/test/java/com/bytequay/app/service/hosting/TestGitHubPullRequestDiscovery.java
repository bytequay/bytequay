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

import com.bytequay.app.repository.github.GitHubNotificationClient;
import com.bytequay.app.repository.github.GitHubNotificationRef;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestSummaryClient;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.hosting.ActivityNotice;
import com.bytequay.core.hosting.ActivityNotice.Reason;
import com.bytequay.core.hosting.DiscoveryQuery;
import com.bytequay.core.hosting.DiscoveryQuery.Kind;
import com.bytequay.core.hosting.DiscoveryQuery.State;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestGitHubPullRequestDiscovery
{
    private static final HostingAccess ACCESS = new HostingAccess(
            new HostingInstance(1, HostingProvider.GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com")), "pat");

    @Test
    void searchesWithTheQueriesGitHubUnderstands()
    {
        Instant since = Instant.parse("2026-10-01T08:30:15.250Z");

        assertThat(GitHubPullRequestDiscovery.search(DiscoveryQuery.of(Kind.AUTHORED, State.OPEN))).isEqualTo("is:pr is:open author:@me");
        assertThat(GitHubPullRequestDiscovery.search(DiscoveryQuery.of(Kind.AUTHORED, State.CLOSED).updatedSince(since)))
                .isEqualTo("is:pr is:closed author:@me updated:>=2026-10-01T08:30:15Z");
        assertThat(GitHubPullRequestDiscovery.search(DiscoveryQuery.of(Kind.REVIEW_REQUESTED, State.OPEN))).isEqualTo("is:pr is:open user-review-requested:@me");
        assertThat(GitHubPullRequestDiscovery.search(DiscoveryQuery.of(Kind.REVIEWED, State.ANY))).isEqualTo("is:pr reviewed-by:@me");
        assertThat(GitHubPullRequestDiscovery.search(DiscoveryQuery.of(Kind.INVOLVED, State.ANY))).isEqualTo("is:pr involves:@me");
        assertThat(GitHubPullRequestDiscovery.search(DiscoveryQuery.repository("owner/repo").updatedSince(since)))
                .isEqualTo("is:pr repo:owner/repo updated:>=2026-10-01T08:30:15Z");
    }

    @Test
    void namesEachNotificationReasonAndReadsTheCommentOnlyWhenAsked()
    {
        GitHubNotificationClient notifications = mock(GitHubNotificationClient.class);
        List<GitHubNotificationRef> threads = List.of(thread("review_requested"), thread("subscribed"), thread("ci_activity"), thread("mention"), thread("author"));
        doAnswer(invocation -> {
            threads.forEach(invocation.<Consumer<GitHubNotificationRef>>getArgument(2));
            return null;
        }).when(notifications).poll(eq("pat"), eq("me"), any());
        GitHubPullRequestDiscovery discovery = new GitHubPullRequestDiscovery(mock(GitHubPullRequestReadClient.class), notifications,
                mock(GitHubPullRequestSummaryClient.class));
        List<ActivityNotice> notices = new ArrayList<>();

        discovery.poll(ACCESS, "me", notices::add);

        assertThat(notices).extracting(ActivityNotice::reason)
                .containsExactly(Reason.REVIEW_REQUESTED, Reason.FOLLOWED_REPOSITORY, Reason.CI, Reason.MENTIONED, Reason.OTHER);
        verify(notifications, never()).activity(any(), any());
        notices.getFirst().latestComment().get();
        verify(notifications).activity("pat", threads.getFirst());
    }

    private static GitHubNotificationRef thread(String reason)
    {
        return new GitHubNotificationRef(reason, PullRequestRef.of("owner", "repo", 7), true, "Title", reason, Instant.parse("2026-10-01T00:00:00Z"), Optional.empty());
    }
}
