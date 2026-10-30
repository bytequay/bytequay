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
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.hosting.ActivityNotice;
import com.bytequay.core.hosting.ActivityNotice.Reason;
import com.bytequay.core.hosting.DiscoveryQuery;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.PullRequestCardSummary;
import com.bytequay.core.hosting.PullRequestDiscovery;
import com.bytequay.core.hosting.PullRequestListPage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/** GitHub search, the notifications inbox and GraphQL card summaries. */
@Component
public class GitHubPullRequestDiscovery
        implements PullRequestDiscovery
{
    private final GitHubPullRequestReadClient reads;
    private final GitHubNotificationClient notifications;
    private final GitHubPullRequestSummaryClient summaries;

    public GitHubPullRequestDiscovery(GitHubPullRequestReadClient reads, GitHubNotificationClient notifications, GitHubPullRequestSummaryClient summaries)
    {
        this.reads = requireNonNull(reads, "reads is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.summaries = requireNonNull(summaries, "summaries is null");
    }

    @Override
    public PullRequestListPage find(HostingAccess access, DiscoveryQuery query, int page, int pageSize)
    {
        PullRequestHistoryPage result = reads.searchPullRequestsPaged(access.token(), search(query), page, pageSize);
        return new PullRequestListPage(result.items(), result.hasMore());
    }

    static String search(DiscoveryQuery query)
    {
        String state = switch (query.state()) {
            case OPEN -> " is:open";
            case CLOSED -> " is:closed";
            case ANY -> "";
        };
        String kind = switch (query.kind()) {
            case AUTHORED -> " author:@me";
            case REVIEW_REQUESTED -> " user-review-requested:@me";
            case REVIEWED -> " reviewed-by:@me";
            case INVOLVED -> " involves:@me";
            case REPOSITORY -> " repo:" + query.repository().orElseThrow();
        };
        return "is:pr" + state + kind + query.updatedSince().map(since -> " updated:>=" + since.truncatedTo(ChronoUnit.SECONDS)).orElse("");
    }

    @Override
    public void poll(HostingAccess access, String login, Consumer<ActivityNotice> notices)
    {
        notifications.poll(access.token(), login, notification -> notices.accept(notice(access, notification)));
    }

    private ActivityNotice notice(HostingAccess access, GitHubNotificationRef notification)
    {
        Reason reason = notification.reviewRequested() ? Reason.REVIEW_REQUESTED
                : notification.repositoryWatch() ? Reason.FOLLOWED_REPOSITORY
                : switch (notification.reason()) {
                    case "ci_activity" -> Reason.CI;
                    case "mention", "team_mention" -> Reason.MENTIONED;
                    default -> Reason.OTHER;
                };
        return new ActivityNotice(notification.ref(), notification.pullRequest(), reason, notification.updatedAt(),
                () -> notifications.activity(access.token(), notification));
    }

    @Override
    public Set<PullRequestRef> reviewRequests(String login)
    {
        return notifications.reviewRequests(login);
    }

    @Override
    public Set<PullRequestRef> mentions(String login)
    {
        return notifications.mentions(login);
    }

    @Override
    public List<PullRequestCardSummary> summaries(HostingAccess access, List<PullRequestRef> refs, String login)
    {
        return summaries.fetchSummaries(access.token(), refs, login);
    }

    @Override
    public int summaryBatchSize()
    {
        return GitHubPullRequestSummaryClient.BATCH_SIZE;
    }

    @Override
    public boolean hasAuthorReplyAfter(HostingAccess access, PullRequestRef ref, String viewer, String author, Instant since, String activityVersion)
    {
        return summaries.hasAuthorReplyAfter(access.token(), ref, viewer, author, since, activityVersion);
    }
}
