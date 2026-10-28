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
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingRepositoryUser;
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
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
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
    public HostingProvider provider()
    {
        return GITHUB;
    }

    @Override
    public IssueSyncPage list(HostingAccess access, HostingRepository repository, Optional<Instant> since, int page)
    {
        RepoRef ref = repository(access, repository);
        if (since.isPresent()) {
            var fetched = reads.fetchChangedIssuesPage(access.token(), ref, since.orElseThrow(), page);
            return new IssueSyncPage(fetched.openIssues(), fetched.closedNumbers(), fetched.hasNext());
        }
        var fetched = reads.fetchOpenIssuesPage(access.token(), ref, page);
        return new IssueSyncPage(fetched.issues(), ImmutableList.of(), fetched.hasNext());
    }

    @Override
    public Optional<RepositoryIssue> find(HostingAccess access, HostingRepository repository, int number)
    {
        return reads.fetchIssue(access.token(), repository(access, repository), number);
    }

    @Override
    public GitHubIssuePreview preview(HostingAccess access, HostingRepository repository, int number)
    {
        return reads.fetchIssuePreview(access.token(), repository(access, repository), number);
    }

    @Override
    public RepositoryIssueDetail detail(HostingAccess access, HostingRepository repository, int number)
    {
        RepoRef ref = repository(access, repository);
        PullRequestRef issue = PullRequestRef.of(ref.owner(), ref.repo(), number);
        List<PrTimelineEvent> events = Stream.concat(timeline.fetchPrTimeline(access.token(), issue).stream().filter(event -> !"commented".equals(event.event())),
                        timeline.fetchPrIssueComments(access.token(), issue).stream())
                .sorted(Comparator.comparing(PrTimelineEvent::timestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(toImmutableList());
        return new RepositoryIssueDetail(repository.id(), number, events, reads.fetchIssueType(access.token(), ref, number),
                reads.fetchIssueTypes(access.token(), ref), Optional.empty());
    }

    @Override
    public GitHubIssueSource source(HostingAccess access, HostingRepository repository, int number, boolean requireOpen)
    {
        RepoRef ref = repository(access, repository);
        return requireOpen ? reads.fetchOpenIssueSource(access.token(), ref, number) : reads.fetchIssueSource(access.token(), ref, number);
    }

    @Override
    public GitHubIssueSource create(HostingAccess access, HostingRepository repository, String title, String body)
    {
        return reads.publishWorkItem(access.token(), repository(access, repository), title, body);
    }

    @Override
    public PrTimelineEvent comment(HostingAccess access, HostingRepository repository, int number, String body)
    {
        return writes.createIssueComment(access.token(), issue(access, repository, number), body);
    }

    @Override
    public void close(HostingAccess access, HostingRepository repository, int number)
    {
        writes.closeIssue(access.token(), issue(access, repository, number));
    }

    @Override
    public String updateType(HostingAccess access, HostingRepository repository, int number, String type)
    {
        return writes.updateIssueType(access.token(), issue(access, repository, number), type);
    }

    @Override
    public IssueMetadata updateMetadata(HostingAccess access, HostingRepository repository, int number, List<String> assignees, List<String> labels)
    {
        var updated = writes.updateIssueMetadata(access.token(), issue(access, repository, number), assignees, labels);
        return new IssueMetadata(updated.assignees(), updated.labels(), updated.updatedAt());
    }

    @Override
    public RepositoryOverview overview(HostingAccess access, HostingRepository repository)
    {
        var overview = reads.fetchOverview(access.token(), repository(access, repository));
        return new RepositoryOverview(overview.description(), overview.stargazersCount(), overview.topics(), overview.languages());
    }

    @Override
    public Optional<List<RepositoryContributor>> contributors(HostingAccess access, HostingRepository repository)
    {
        return Optional.of(reads.fetchContributors(access.token(), repository(access, repository)));
    }

    @Override
    public List<HostingRepositoryUser> repositoryUsers(HostingAccess access, HostingRepository repository)
    {
        return timeline.fetchAssignableRepositoryUsers(access.token(), repository(access, repository)).stream()
                .map(user -> new HostingRepositoryUser(user.id(), user.login(), user.avatarUrl(), user.bot())).collect(toImmutableList());
    }

    @Override
    public List<String> repositoryTeams(HostingAccess access, HostingRepository repository)
    {
        return timeline.fetchOrganizationTeams(access.token(), repository(access, repository).owner());
    }

    @Override
    public List<RepositoryLabel> repositoryLabels(HostingAccess access, HostingRepository repository)
    {
        return reads.fetchLabels(access.token(), repository(access, repository));
    }

    @Override
    public IssueActivity activity(HostingAccess access, HostingRepository repository, int number)
    {
        var value = reads.fetchIssueActivity(access.token(), repository(access, repository), number);
        return new IssueActivity(value.commentCount(), value.updatedAt());
    }

    private static PullRequestRef issue(HostingAccess access, HostingRepository repository, int number)
    {
        RepoRef ref = repository(access, repository);
        return PullRequestRef.of(ref.owner(), ref.repo(), number);
    }

    private static RepoRef repository(HostingAccess access, HostingRepository repository)
    {
        checkArgument(access.server().equals(GITHUB_SERVER), "GitHub issue operations require GitHub.com");
        checkArgument(repository.instanceId() == access.instanceId().orElseThrow(), "repository belongs to another hosting instance");
        String[] parts = repository.fullPath().split("/", -1);
        checkArgument(parts.length == 2, "invalid GitHub repository path");
        return RepoRef.of(parts[0], parts[1]);
    }
}
