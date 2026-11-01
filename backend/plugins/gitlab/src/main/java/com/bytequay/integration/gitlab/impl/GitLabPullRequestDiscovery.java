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
import com.bytequay.core.domain.PullRequestDetail.CiStatus;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.ActivityNotice;
import com.bytequay.core.hosting.ActivityNotice.Reason;
import com.bytequay.core.hosting.DiscoveryQuery;
import com.bytequay.core.hosting.DiscoveryQuery.State;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.PullRequestCardSummary;
import com.bytequay.core.hosting.PullRequestDiscovery;
import com.bytequay.core.hosting.PullRequestListPage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.mapped;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.Math.toIntExact;

/** Merge requests across a GitLab account, its to-do list of merge requests and issues, and each request's details for cards. */
public final class GitLabPullRequestDiscovery
        implements PullRequestDiscovery
{
    private final GitLabHttpClient http;
    /** To-dos on other targets, such as Epics, belong to no project and have no notice. */
    private static final Set<String> NOTICE_TARGETS = ImmutableSet.of("MergeRequest", "Issue");

    // Pending to-dos stay listed until done, so each is delivered again only when it changes.
    private final Map<String, Map<Long, Instant>> delivered = new ConcurrentHashMap<>();
    private final Map<String, Set<PullRequestRef>> reviewRequests = new ConcurrentHashMap<>();
    private final Map<String, Set<PullRequestRef>> mentions = new ConcurrentHashMap<>();

    public GitLabPullRequestDiscovery(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitLabHttpClient(http, mapper);
    }

    @Override
    public PullRequestListPage find(HostingAccess access, DiscoveryQuery query, int page, int pageSize)
    {
        checkArgument(page > 0, "page must be positive");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
        Optional<String> filter = switch (query.kind()) {
            case AUTHORED -> Optional.of("scope=created_by_me");
            case REVIEW_REQUESTED -> Optional.of("scope=all&reviewer_username=" + encode(viewer(access).username()));
            case REPOSITORY -> Optional.of("scope=all");
            // GitLab has no "reviewed by" or "involves" filter; mentions arrive through the to-do list instead.
            case REVIEWED, INVOLVED -> Optional.empty();
        };
        if (filter.isEmpty()) {
            return new PullRequestListPage(ImmutableList.of(), false);
        }
        String resource = query.repository().map(path -> "projects/" + encode(path) + "/merge_requests").orElse("merge_requests");
        // GitLab's closed state excludes merged requests, which a closed query also wants.
        String state = query.state() == State.OPEN ? "opened" : "all";
        String path = resource + "?" + filter.orElseThrow() + "&state=" + state
                + query.updatedSince().map(since -> "&updated_after=" + since.truncatedTo(ChronoUnit.SECONDS)).orElse("")
                + "&order_by=updated_at&sort=desc&per_page=" + pageSize + "&page=" + page;
        GitLabHttpClient.Response<GitLabMergeRequestResponse[]> response = http.get(access, path, Optional.empty(), GitLabMergeRequestResponse[].class);
        List<FetchedPullRequest> items = mapped("merge request", () -> Arrays.stream(response.body().orElseThrow())
                .filter(request -> query.state() != State.CLOSED || !"open".equals(request.requestState()))
                .map(request -> GitLabPullRequestReader.summary(request.referencedProject(), access.server().webBaseUrl(), request))
                .collect(toImmutableList()));
        return new PullRequestListPage(items, response.hasNext());
    }

    @Override
    public void poll(HostingAccess access, String login, Consumer<ActivityNotice> notices)
    {
        List<Todo> todos = http.pages(access, "todos?state=pending", Todo[].class).stream()
                .filter(todo -> NOTICE_TARGETS.contains(todo.targetType()))
                .collect(toImmutableList());
        String account = access.server().webBaseUrl() + " " + login.toLowerCase(Locale.ROOT);
        Map<Long, Instant> seen = delivered.computeIfAbsent(account, ignored -> new ConcurrentHashMap<>());
        Set<PullRequestRef> requested = new HashSet<>();
        Set<PullRequestRef> mentioned = new HashSet<>();
        for (Todo todo : todos) {
            PullRequestRef ref = mapped("to-do", todo::ref);
            Instant updatedAt = mapped("to-do", todo::updatedAt);
            Reason reason = switch (Optional.ofNullable(todo.actionName()).orElse("")) {
                case "review_requested" -> Reason.REVIEW_REQUESTED;
                case "mentioned", "directly_addressed" -> Reason.MENTIONED;
                case "build_failed" -> Reason.CI;
                default -> Reason.OTHER;
            };
            boolean mergeRequest = todo.targetType().equals("MergeRequest");
            if (mergeRequest && reason == Reason.REVIEW_REQUESTED) {
                requested.add(ref);
            }
            if (mergeRequest && reason == Reason.MENTIONED) {
                mentioned.add(ref);
            }
            if (!updatedAt.equals(seen.put(todo.id(), updatedAt))) {
                notices.accept(new ActivityNotice(ref, mergeRequest, reason, updatedAt, Optional::empty));
            }
        }
        seen.keySet().retainAll(todos.stream().map(Todo::id).collect(ImmutableSet.toImmutableSet()));
        reviewRequests.put(login.toLowerCase(Locale.ROOT), ImmutableSet.copyOf(requested));
        mentions.put(login.toLowerCase(Locale.ROOT), ImmutableSet.copyOf(mentioned));
    }

    @Override
    public Set<PullRequestRef> reviewRequests(String login)
    {
        return reviewRequests.getOrDefault(login.toLowerCase(Locale.ROOT), ImmutableSet.of());
    }

    @Override
    public Set<PullRequestRef> mentions(String login)
    {
        return mentions.getOrDefault(login.toLowerCase(Locale.ROOT), ImmutableSet.of());
    }

    @Override
    public List<PullRequestCardSummary> summaries(HostingAccess access, List<PullRequestRef> refs, String login)
    {
        return refs.stream().map(ref -> {
            GitLabMergeRequestResponse request = http.get(access, "projects/" + encode(ref.repoFullName()) + "/merge_requests/" + ref.number(), Optional.empty(),
                    GitLabMergeRequestResponse.class).body().orElseThrow();
            return mapped("merge request", () -> card(access, ref.repoFullName(), request, login));
        }).collect(toImmutableList());
    }

    private static PullRequestCardSummary card(HostingAccess access, String projectPath, GitLabMergeRequestResponse request, String login)
    {
        FetchedPullRequest pullRequest = GitLabPullRequestReader.summary(projectPath, access.server().webBaseUrl(), request).withViewerOrigin(login);
        CiStatus ci = Optional.ofNullable(request.headPipeline()).map(pipeline -> switch (Optional.ofNullable(pipeline.status()).orElse("")) {
            case "success" -> CiStatus.PASSING;
            case "failed" -> CiStatus.FAILING;
            case "created", "waiting_for_resource", "preparing", "pending", "running", "scheduled" -> CiStatus.PENDING;
            default -> CiStatus.NONE;
        }).orElse(CiStatus.NONE);
        // Line counts and review decisions need further requests; the card shows what the request itself reports.
        return new PullRequestCardSummary(pullRequest, ci, 0, 0, Optional.ofNullable(request.userNotesCount()).orElse(0), Optional.ofNullable(request.sha()).orElse(""),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), request.updatedAt(), pullRequest.updatedAt(), Optional.empty());
    }

    @Override
    public int summaryBatchSize()
    {
        return 5;
    }

    /** GitLab notes would need a request per thread to find the author's reply, so cards rely on new commits and new requests instead. */
    @Override
    public boolean hasAuthorReplyAfter(HostingAccess access, PullRequestRef ref, String viewer, String author, Instant since, String activityVersion)
    {
        return false;
    }

    private GitLabUser viewer(HostingAccess access)
    {
        return http.get(access, "user", Optional.empty(), GitLabUser.class).body().orElseThrow();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Todo(long id, @JsonProperty("action_name") String actionName, @JsonProperty("target_type") String targetType, Target target, Project project,
            @JsonProperty("updated_at") String updated, @JsonProperty("created_at") String created)
    {
        PullRequestRef ref()
        {
            String path = project.pathWithNamespace();
            int slash = path.lastIndexOf('/');
            checkArgument(slash > 0 && slash < path.length() - 1, "invalid project path");
            RepoRef repository = RepoRef.of(path.substring(0, slash), path.substring(slash + 1));
            return PullRequestRef.of(repository.owner(), repository.repo(), toIntExact(target.iid()));
        }

        Instant updatedAt()
        {
            return Instant.parse(Optional.ofNullable(updated).orElse(created));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Target(Long iid) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Project(@JsonProperty("path_with_namespace") String pathWithNamespace) {}
}
