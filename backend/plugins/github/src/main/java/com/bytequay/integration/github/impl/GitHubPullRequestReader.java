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
package com.bytequay.integration.github.impl;

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingUrls;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.PullRequestReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.PrRawDetail.UNKNOWN_COUNT;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

public final class GitHubPullRequestReader
        implements PullRequestReader
{
    private final GitHubHttpClient http;

    public GitHubPullRequestReader(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitHubHttpClient(http, mapper);
    }

    @Override
    public PullRequestFetch fetch(HostingAccess access, HostingRepository repository, int number, Optional<String> validator)
    {
        requireNonNull(access, "access is null");
        requireNonNull(repository, "repository is null");
        checkArgument(access.instanceId().orElseThrow() == repository.instanceId(), "repository belongs to another hosting instance");
        checkArgument(number > 0, "number must be positive");
        GitHubHttpClient.Response<GitHubPullRequestResponse> response = http.get(
                access, GitHubHttpClient.repositoryPath(repository.fullPath()) + "/pulls/" + number, GitHubPullRequestResponse.class, validator);
        if (response.body().isEmpty()) {
            return new PullRequestFetch(Optional.empty(), response.validator());
        }
        try {
            GitHubPullRequestResponse item = response.body().orElseThrow();
            if (item.id() <= 0 || item.number() != number || item.base() == null || item.base().repo() == null || item.base().repo().id() <= 0 ||
                    !Long.toString(item.base().repo().id()).equals(repository.remoteId())) {
                throw new HostingFailure(INVALID_RESPONSE, "GitHub returned invalid pull request metadata");
            }
            checkText(item.title(), "title");
            requireNonNull(item.createdAt(), "createdAt is null");
            requireNonNull(item.updatedAt(), "updatedAt is null");
            requireNonNull(item.draft(), "draft is null");
            String state = switch (item.state()) {
                case "open", "closed" -> item.mergedAt() == null ? item.state() : "merged";
                default -> throw new HostingFailure(INVALID_RESPONSE, "GitHub returned an invalid pull request state");
            };
            String url = HostingUrls.requireWithinBase(URI.create(checkText(item.htmlUrl(), "htmlUrl")), access.server().webBaseUrl()).toASCIIString();
            List<String> reviewers = Optional.ofNullable(item.requestedReviewers()).orElse(ImmutableList.of()).stream()
                    .map(GitHubPullRequestResponse.User::login).filter(Objects::nonNull).collect(toImmutableList());
            PrRawDetail detail = toPrRawDetail(repository.fullPath(), item, reviewers, state, url);
            FetchedPullRequest summary = new FetchedPullRequest(
                    item.id(), repository.fullPath(), item.number(), detail.title(), detail.author(), detail.htmlUrl(), detail.createdAt(), detail.updatedAt(),
                    OTHER, detail.labels(), detail.labelColors(), detail.draft(), detail.state(), item.closedAt(), detail.mergedAt(), reviewers,
                    detail.mergeable(), detail.mergeableState(), detail.headRef());
            return new PullRequestFetch(Optional.of(summary), response.validator(), Optional.of(detail));
        }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new HostingFailure(INVALID_RESPONSE, "GitHub returned invalid pull request metadata");
        }
    }

    private static PrRawDetail toPrRawDetail(String fullPath, GitHubPullRequestResponse r, List<String> reviewers, String state, String url)
    {
        List<GitHubPullRequestResponse.Label> remoteLabels = Optional.ofNullable(r.labels()).orElse(ImmutableList.of());
        List<String> labels = remoteLabels.stream().map(GitHubPullRequestResponse.Label::name).collect(toImmutableList());
        Map<String, String> labelColors = remoteLabels.stream()
                .filter(label -> label.name() != null && label.color() != null)
                .collect(toImmutableMap(GitHubPullRequestResponse.Label::name, GitHubPullRequestResponse.Label::color, (left, right) -> left));
        List<String> assignees = Optional.ofNullable(r.assignees()).orElse(ImmutableList.of()).stream()
                .map(GitHubPullRequestResponse.User::login)
                .filter(Objects::nonNull)
                .collect(toImmutableList());
        // A requested team is named owner/slug, as the timeline names it; removing it addresses team_reviewers.
        List<String> reviewerLogins = Stream.concat(
                        reviewers.stream(),
                        Optional.ofNullable(r.requestedTeams()).orElse(ImmutableList.of()).stream().map(GitHubPullRequestResponse.Team::slug)
                                .filter(Objects::nonNull).map(slug -> fullPath.substring(0, fullPath.indexOf('/')) + "/" + slug))
                .collect(toImmutableList());
        int reviewerCount = r.requestedReviewers() == null || r.requestedTeams() == null ? UNKNOWN_COUNT : reviewerLogins.size();
        Optional<GitHubPullRequestResponse.Head> head = Optional.ofNullable(r.head());
        Optional<GitHubPullRequestResponse.User> author = Optional.ofNullable(r.user());
        return new PrRawDetail(
                r.title(),
                url,
                state,
                author.map(GitHubPullRequestResponse.User::login).orElse(null),
                author.map(GitHubPullRequestResponse.User::avatarUrl).orElse(null),
                r.createdAt(),
                r.updatedAt(),
                r.mergedAt(),
                r.body(),
                labels,
                labelColors,
                assignees,
                Optional.ofNullable(r.milestone()).map(GitHubPullRequestResponse.Milestone::title).orElse(null),
                r.draft(),
                r.mergeable(),
                r.mergeableState(),
                countOrUnknown(r.additions()),
                countOrUnknown(r.deletions()),
                countOrUnknown(r.changedFiles()),
                reviewerCount,
                reviewerLogins,
                head.map(GitHubPullRequestResponse.Head::sha).orElse(null),
                head.map(GitHubPullRequestResponse.Head::ref).orElse(null),
                r.base().ref(),
                r.base().sha());
    }

    private static int countOrUnknown(Integer value)
    {
        return value == null || value < 0 ? UNKNOWN_COUNT : value;
    }
}
