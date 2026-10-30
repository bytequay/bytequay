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
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.HostingUrls;
import com.bytequay.core.hosting.PullRequestFetch;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.hosting.PullRequestReader;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.DiffRefs;
import com.bytequay.integration.gitlab.impl.GitLabMergeRequestResponse.Milestone;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkSha;
import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.PrRawDetail.UNKNOWN_COUNT;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.mergeRequestPath;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.projectPath;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Reads MRs by target project ID and project-scoped IID; provider payloads stay inside this module. */
public final class GitLabPullRequestReader
        implements PullRequestReader
{
    private final GitLabHttpClient http;

    public GitLabPullRequestReader(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitLabHttpClient(http, mapper);
    }

    @Override
    public PullRequestListPage list(HostingTarget target, boolean openOnly, int page, int pageSize)
    {
        requireNonNull(target, "target is null");
        checkArgument(page > 0, "page must be positive");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
        String path = projectPath(target) + "/merge_requests?scope=all&state=" + (openOnly ? "opened" : "all")
                + "&order_by=updated_at&sort=desc&per_page=" + pageSize + "&page=" + page;
        GitLabHttpClient.Response<GitLabMergeRequestResponse[]> response = http.get(target.access(), path, Optional.empty(), GitLabMergeRequestResponse[].class);
        try {
            List<FetchedPullRequest> items = Arrays.stream(response.body().orElseThrow()).map(mr -> summary(target, mr)).collect(toImmutableList());
            return new PullRequestListPage(items, response.hasNext());
        }
        catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned invalid merge request metadata");
        }
    }

    @Override
    public PullRequestFetch fetch(HostingTarget target, int number, Optional<String> validator)
    {
        requireNonNull(target, "target is null");
        var response = http.get(target.access(), mergeRequestPath(target, number), validator, GitLabMergeRequestResponse.class);
        if (response.body().isEmpty()) {
            return new PullRequestFetch(Optional.empty(), response.etag());
        }
        try {
            GitLabMergeRequestResponse mr = response.body().orElseThrow();
            FetchedPullRequest summary = summary(target, mr);
            checkArgument(summary.number() == number, "invalid merge request identity");
            String authorAvatar = Optional.ofNullable(mr.author()).map(GitLabUser::avatarUrl).orElse(null);
            String headSha = optionalSha(mr.sha());
            Optional<DiffRefs> diffRefs = Optional.ofNullable(mr.diffRefs());
            String diffHead = diffRefs.map(DiffRefs::headSha).map(GitLabPullRequestReader::optionalSha).orElse(null);
            // start_sha is the target tip. GitLab's base_sha is a different coordinate: the merge base.
            String baseSha = headSha != null && headSha.equals(diffHead)
                    ? diffRefs.map(DiffRefs::startSha).map(GitLabPullRequestReader::optionalSha).orElse(null) : null;
            List<String> assignees = mr.assignees().stream().map(user -> checkText(user.username(), "username")).collect(toImmutableList());
            PrRawDetail detail = new PrRawDetail(summary.title(), summary.htmlUrl(), summary.state(), summary.author(), authorAvatar,
                    summary.createdAt(), summary.updatedAt(), summary.mergedAt(), mr.description(), summary.labels(), summary.labelColors(), assignees,
                    Optional.ofNullable(mr.milestone()).map(Milestone::title).orElse(null),
                    summary.draft(), summary.mergeable(), summary.mergeableState(), UNKNOWN_COUNT, UNKNOWN_COUNT, baseSha == null ? UNKNOWN_COUNT : changedFiles(mr.changesCount()),
                    UNKNOWN_COUNT, summary.requestedReviewers(), headSha, mr.sourceBranch(), mr.targetBranch(), baseSha);
            return new PullRequestFetch(Optional.of(summary), response.etag(), Optional.of(detail));
        }
        catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned invalid merge request metadata");
        }
    }

    private static FetchedPullRequest summary(HostingTarget target, GitLabMergeRequestResponse mr)
    {
        String projectId = target.repository().remoteId();
        checkArgument(mr.projectId() != null && mr.projectId() > 0 && mr.projectId().toString().equals(projectId), "invalid project identity");
        checkArgument(mr.targetProjectId() == null || (mr.targetProjectId() > 0 && mr.targetProjectId().toString().equals(projectId)), "invalid target project");
        return summary(target.repository().fullPath(), target.access().server().webBaseUrl(), mr);
    }

    /** A request found outside a project listing is mapped against the project its reference names. */
    static FetchedPullRequest summary(String projectPath, URI webBaseUrl, GitLabMergeRequestResponse mr)
    {
        checkArgument(mr.id() != null && mr.id() > 0 && mr.iid() != null && mr.iid() > 0 && mr.iid() <= Integer.MAX_VALUE, "invalid merge request identity");
        checkText(mr.state(), "state");
        String state = mr.requestState();
        String url = HostingUrls.requireWithinBase(URI.create(checkText(mr.webUrl(), "webUrl")), webBaseUrl).toASCIIString();
        boolean draft = mr.isDraft();
        String author = Optional.ofNullable(mr.author()).map(GitLabUser::username).orElse(null);
        List<String> labels = mr.labels().stream().map(label -> checkText(label, "label")).collect(toImmutableList());
        // Assigned reviewers include people who already reviewed; this read cannot establish pending requests or approvals.
        List<String> requestedReviewers = ImmutableList.of();
        // GitLab before 15.6 exposes only merge_status; newer detailed states must take precedence.
        String mergeStatus = Optional.ofNullable(mr.detailedMergeStatus()).orElseGet(() -> switch (Optional.ofNullable(mr.mergeStatus()).orElse("")) {
            case "can_be_merged" -> "mergeable";
            case "cannot_be_merged" -> "conflict";
            default -> "";
        });
        Boolean mergeable = switch (mergeStatus) {
            case "mergeable" -> Boolean.TRUE;
            case "conflict" -> Boolean.FALSE;
            default -> null;
        };
        String mergeableState = mergeable == null ? null : mergeable ? "clean" : "dirty";
        return new FetchedPullRequest(mr.id(), projectPath, mr.iid().intValue(), checkText(mr.title(), "title"), author, url,
                Instant.parse(mr.createdAt()), Instant.parse(mr.updatedAt()), OTHER, labels, ImmutableMap.of(), draft, state,
                Optional.ofNullable(mr.closedAt()).map(Instant::parse).orElse(null), Optional.ofNullable(mr.mergedAt()).map(Instant::parse).orElse(null),
                requestedReviewers, mergeable, mergeableState, mr.sourceBranch());
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
