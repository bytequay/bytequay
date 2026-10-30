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

import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.core.domain.GitHubIssuePreview;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.hosting.HostingRepositoryUser;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.IssueActivity;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.hosting.IssueMetadata;
import com.bytequay.core.hosting.IssueSyncPage;
import com.bytequay.core.hosting.RepositoryMetadataReader;
import com.bytequay.core.hosting.RepositoryOverview;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Component
public final class GitHubIssueClient
        implements IssueClient, RepositoryMetadataReader
{
    private final GitHubRepoClient reads;
    private final GitHubPullRequestReadClient timeline;
    private final GitHubPullRequestWriteClient writes;

    public GitHubIssueClient(GitHubRepoClient reads, GitHubPullRequestReadClient timeline, GitHubPullRequestWriteClient writes)
    {
        this.reads = requireNonNull(reads, "reads is null");
        this.timeline = requireNonNull(timeline, "timeline is null");
        this.writes = requireNonNull(writes, "writes is null");
    }

    @Override
    public IssueSyncPage list(HostingTarget target, Optional<Instant> since, int page)
    {
        RepoRef ref = repository(target);
        if (since.isPresent()) {
            var fetched = reads.fetchChangedIssuesPage(target.access().token(), ref, since.orElseThrow(), page);
            return new IssueSyncPage(fetched.openIssues(), fetched.closedNumbers(), fetched.hasNext());
        }
        var fetched = reads.fetchOpenIssuesPage(target.access().token(), ref, page);
        return new IssueSyncPage(fetched.issues(), ImmutableList.of(), fetched.hasNext());
    }

    @Override
    public Optional<RepositoryIssue> find(HostingTarget target, int number)
    {
        return reads.fetchIssue(target.access().token(), repository(target), number);
    }

    @Override
    public GitHubIssuePreview preview(HostingTarget target, int number)
    {
        return reads.fetchIssuePreview(target.access().token(), repository(target), number);
    }

    @Override
    public RepositoryIssueDetail detail(HostingTarget target, int number)
    {
        RepoRef ref = repository(target);
        PullRequestRef issue = PullRequestRef.of(ref.owner(), ref.repo(), number);
        List<PrTimelineEvent> events = Stream.concat(timeline.fetchPrTimeline(target.access().token(), issue).stream().filter(event -> !"commented".equals(event.event())),
                        timeline.fetchPrIssueComments(target.access().token(), issue).stream())
                .sorted(Comparator.comparing(PrTimelineEvent::timestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(toImmutableList());
        return new RepositoryIssueDetail(target.repository().id(), number, events, reads.fetchIssueType(target.access().token(), ref, number),
                reads.fetchIssueTypes(target.access().token(), ref), Optional.empty());
    }

    @Override
    public GitHubIssueSource source(HostingTarget target, int number, boolean requireOpen)
    {
        RepoRef ref = repository(target);
        return requireOpen ? reads.fetchOpenIssueSource(target.access().token(), ref, number) : reads.fetchIssueSource(target.access().token(), ref, number);
    }

    @Override
    public GitHubIssueSource create(HostingTarget target, String title, String body)
    {
        return reads.publishWorkItem(target.access().token(), repository(target), title, body);
    }

    @Override
    public PrTimelineEvent comment(HostingTarget target, int number, String body)
    {
        return writes.createIssueComment(target.access().token(), issue(target, number), body);
    }

    @Override
    public void close(HostingTarget target, int number)
    {
        writes.closeIssue(target.access().token(), issue(target, number));
    }

    @Override
    public String updateType(HostingTarget target, int number, String type)
    {
        return writes.updateIssueType(target.access().token(), issue(target, number), type);
    }

    @Override
    public IssueMetadata updateMetadata(HostingTarget target, int number, List<String> assignees, List<String> labels)
    {
        var updated = writes.updateIssueMetadata(target.access().token(), issue(target, number), assignees, labels);
        return new IssueMetadata(updated.assignees(), updated.labels(), updated.updatedAt());
    }

    @Override
    public RepositoryOverview overview(HostingTarget target)
    {
        var overview = reads.fetchOverview(target.access().token(), repository(target));
        return new RepositoryOverview(overview.description(), overview.stargazersCount(), overview.topics(), overview.languages());
    }

    @Override
    public Optional<List<RepositoryContributor>> contributors(HostingTarget target)
    {
        return Optional.of(reads.fetchContributors(target.access().token(), repository(target)));
    }

    @Override
    public List<HostingRepositoryUser> repositoryUsers(HostingTarget target)
    {
        return timeline.fetchAssignableRepositoryUsers(target.access().token(), repository(target)).stream()
                .map(user -> new HostingRepositoryUser(user.id(), user.login(), user.avatarUrl(), user.bot())).collect(toImmutableList());
    }

    @Override
    public List<String> repositoryTeams(HostingTarget target)
    {
        return timeline.fetchOrganizationTeams(target.access().token(), repository(target).owner());
    }

    @Override
    public List<RepositoryLabel> repositoryLabels(HostingTarget target)
    {
        return reads.fetchLabels(target.access().token(), repository(target));
    }

    @Override
    public IssueActivity activity(HostingTarget target, int number)
    {
        var value = reads.fetchIssueActivity(target.access().token(), repository(target), number);
        return new IssueActivity(value.commentCount(), value.updatedAt());
    }

    private static PullRequestRef issue(HostingTarget target, int number)
    {
        RepoRef ref = repository(target);
        return PullRequestRef.of(ref.owner(), ref.repo(), number);
    }

    private static RepoRef repository(HostingTarget target)
    {
        checkArgument(target.access().server().equals(GITHUB_SERVER), "GitHub issue operations require GitHub.com");
        String[] parts = target.repository().fullPath().split("/", -1);
        checkArgument(parts.length == 2, "invalid GitHub repository path");
        return RepoRef.of(parts[0], parts[1]);
    }
}
