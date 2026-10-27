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
package com.bytequay.integration.gitlab.impl;

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
import com.google.common.collect.ImmutableMap;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkSha;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.PrRawDetail.UNKNOWN_COUNT;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Reads one MR by target project ID and project-scoped IID; provider payloads stay inside this module. */
public final class GitLabPullRequestReader
        implements PullRequestReader
{
    private final GitLabHttpClient http;

    public GitLabPullRequestReader(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitLabHttpClient(http, mapper);
    }

    @Override
    public PullRequestFetch fetch(HostingAccess access, HostingRepository repository, int number, Optional<String> validator)
    {
        requireNonNull(access, "access is null");
        requireNonNull(repository, "repository is null");
        checkArgument(repository.instanceId() == access.instanceId().orElseThrow(), "repository belongs to another hosting instance");
        checkArgument(number > 0, "number must be positive");
        GitLabHttpClient.Response<GitLabMergeRequestResponse> response = http.get(access,
                "projects/" + encode(repository.remoteId()) + "/merge_requests/" + number, validator, GitLabMergeRequestResponse.class);
        if (response.body().isEmpty()) {
            return new PullRequestFetch(Optional.empty(), response.etag());
        }
        try {
            GitLabMergeRequestResponse mr = response.body().orElseThrow();
            checkArgument(mr.id() != null && mr.id() > 0 && mr.iid() != null && mr.iid() == number, "invalid merge request identity");
            checkArgument(mr.projectId() != null && mr.projectId() > 0 && mr.projectId().toString().equals(repository.remoteId()), "invalid project identity");
            checkArgument(mr.targetProjectId() == null || (mr.targetProjectId() > 0 && mr.targetProjectId().toString().equals(repository.remoteId())), "invalid target project");
            String state = switch (checkText(mr.state(), "state")) {
                case "opened", "locked" -> "open";
                case "closed" -> "closed";
                case "merged" -> "merged";
                default -> throw new IllegalArgumentException("invalid merge request state");
            };
            String url = HostingUrls.requireWithinBase(URI.create(checkText(mr.webUrl(), "webUrl")), access.server().webBaseUrl()).toASCIIString();
            String title = checkText(mr.title(), "title");
            Instant createdAt = Instant.parse(mr.createdAt());
            Instant updatedAt = Instant.parse(mr.updatedAt());
            Instant closedAt = Optional.ofNullable(mr.closedAt()).map(Instant::parse).orElse(null);
            Instant mergedAt = Optional.ofNullable(mr.mergedAt()).map(Instant::parse).orElse(null);
            boolean draft = Optional.ofNullable(mr.draft()).or(() -> Optional.ofNullable(mr.workInProgress()))
                    .orElseThrow(() -> new IllegalArgumentException("missing draft status"));
            Optional<GitLabMergeRequestResponse.User> remoteAuthor = Optional.ofNullable(mr.author());
            String author = remoteAuthor.map(GitLabMergeRequestResponse.User::username).orElse(null);
            String authorAvatar = remoteAuthor.map(GitLabMergeRequestResponse.User::avatarUrl).orElse(null);
            String headSha = optionalSha(mr.sha());
            Optional<GitLabMergeRequestResponse.DiffRefs> diffRefs = Optional.ofNullable(mr.diffRefs());
            String diffHead = diffRefs.map(GitLabMergeRequestResponse.DiffRefs::headSha).map(GitLabPullRequestReader::optionalSha).orElse(null);
            // start_sha is the target tip. GitLab's base_sha is a different coordinate: the merge base.
            String baseSha = headSha != null && headSha.equals(diffHead)
                    ? diffRefs.map(GitLabMergeRequestResponse.DiffRefs::startSha).map(GitLabPullRequestReader::optionalSha).orElse(null) : null;
            List<String> labels = mr.labels().stream().map(label -> checkText(label, "label")).collect(toImmutableList());
            List<String> assignees = mr.assignees().stream().map(user -> checkText(user.username(), "username")).collect(toImmutableList());
            // Assigned reviewers include people who already reviewed; this read cannot establish pending requests or approvals.
            List<String> requestedReviewers = ImmutableList.of();
            Boolean mergeable = "conflict".equals(mr.detailedMergeStatus()) ? Boolean.FALSE : null;
            FetchedPullRequest summary = new FetchedPullRequest(mr.id(), repository.fullPath(), number, title, author, url, createdAt, updatedAt,
                    OTHER, labels, ImmutableMap.of(), draft, state, closedAt, mergedAt, requestedReviewers, mergeable, null, mr.sourceBranch());
            PrRawDetail detail = new PrRawDetail(title, url, state, author, authorAvatar, createdAt, updatedAt, mergedAt,
                    mr.description(), labels, ImmutableMap.of(), assignees, Optional.ofNullable(mr.milestone()).map(GitLabMergeRequestResponse.Milestone::title).orElse(null),
                    draft, mergeable, null, UNKNOWN_COUNT, UNKNOWN_COUNT, baseSha == null ? UNKNOWN_COUNT : changedFiles(mr.changesCount()), UNKNOWN_COUNT, requestedReviewers,
                    headSha, mr.sourceBranch(), mr.targetBranch(), baseSha);
            return new PullRequestFetch(Optional.of(summary), response.etag(), Optional.of(detail));
        }
        catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned invalid merge request metadata");
        }
    }

    private static int changedFiles(String value)
    {
        if (value == null || !value.matches("[0-9]+")) {
            return UNKNOWN_COUNT;
        }
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            return UNKNOWN_COUNT;
        }
    }

    private static String optionalSha(String value)
    {
        return Optional.ofNullable(value).filter(sha -> !sha.isEmpty()).map(sha -> checkSha(sha, "sha")).orElse(null);
    }
}
