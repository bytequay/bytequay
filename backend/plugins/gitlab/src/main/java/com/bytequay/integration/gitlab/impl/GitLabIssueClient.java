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

import com.bytequay.core.domain.GitHubIssuePreview;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingRepositoryUser;
import com.bytequay.core.hosting.HostingUrls;
import com.bytequay.core.hosting.IssueActivity;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.hosting.IssueMetadata;
import com.bytequay.core.hosting.IssueSyncPage;
import com.bytequay.core.hosting.RepositoryMetadataReader;
import com.bytequay.core.hosting.RepositoryOverview;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** GitLab project issues normalized to ByteQuay's existing issue contracts. */
public final class GitLabIssueClient
        implements IssueClient, RepositoryMetadataReader
{
    private final GitLabHttpClient http;

    public GitLabIssueClient(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitLabHttpClient(http, mapper);
    }

    @Override
    public HostingProvider provider()
    {
        return GITLAB;
    }

    @Override
    public IssueSyncPage list(HostingAccess access, HostingRepository repository, Optional<Instant> since, int page)
    {
        checkArgument(page > 0, "page must be positive");
        String path = path(access, repository) + "?scope=all&state=" + (since.isPresent() ? "all" : "opened")
                + "&order_by=updated_at&sort=asc&per_page=100&page=" + page + since.map(value -> "&updated_after=" + encode(value.toString())).orElse("");
        var response = http.get(access, path, Optional.empty(), GitLabIssueResponse[].class);
        ImmutableList.Builder<RepositoryIssue> open = ImmutableList.builder();
        ImmutableList.Builder<Integer> closed = ImmutableList.builder();
        for (GitLabIssueResponse issue : response.body().orElseThrow()) {
            RepositoryIssue card = card(access, repository, issue);
            if ("closed".equals(issue.state())) {
                closed.add(card.number());
            }
            else {
                open.add(card);
            }
        }
        return new IssueSyncPage(open.build(), closed.build(), response.hasNext());
    }

    @Override
    public Optional<RepositoryIssue> find(HostingAccess access, HostingRepository repository, int number)
    {
        GitLabIssueResponse issue = read(access, repository, number);
        RepositoryIssue card = card(access, repository, issue);
        return "closed".equals(issue.state()) ? Optional.empty() : Optional.of(card);
    }

    @Override
    public GitHubIssuePreview preview(HostingAccess access, HostingRepository repository, int number)
    {
        GitLabIssueResponse response = read(access, repository, number);
        RepositoryIssue issue = card(access, repository, response);
        return new GitHubIssuePreview(issue.title(), issue.body(), issue.author(), issue.labels(), issue.updatedAt(),
                "closed".equals(response.state()) ? "closed" : "open", false, issue.htmlUrl(), issue.authorAvatarUrl());
    }

    @Override
    public RepositoryIssueDetail detail(HostingAccess access, HostingRepository repository, int number)
    {
        GitLabIssueResponse issue = read(access, repository, number);
        String path = issuePath(access, repository, number);
        ImmutableList.Builder<PrTimelineEvent> events = ImmutableList.builder();
        for (Note note : all(access, path + "/notes?sort=asc&order_by=created_at", Note[].class)) {
            events.add(note.event(issue.webUrl()));
        }
        for (ResourceEvent event : all(access, path + "/resource_state_events?", ResourceEvent[].class)) {
            events.add(event.event("closed".equals(event.state()) ? "closed" : "reopened", PrTimelinePayload.empty()));
        }
        for (ResourceEvent event : all(access, path + "/resource_label_events?", ResourceEvent[].class)) {
            // GitLab retains these events after the label has been deleted.
            if (event.label() == null) {
                continue;
            }
            events.add(event.event("add".equals(event.action()) ? "labeled" : "unlabeled",
                    PrTimelinePayload.builder().label(event.label().name()).labelColor(color(event.label().color())).build()));
        }
        for (ResourceEvent event : all(access, path + "/resource_milestone_events?", ResourceEvent[].class)) {
            events.add(event.event("add".equals(event.action()) ? "milestoned" : "demilestoned",
                    PrTimelinePayload.builder().milestoneTitle(event.milestone().title()).build()));
        }
        List<String> types = "test_case".equals(issue.issueType()) ? ImmutableList.of("issue", "incident", "test_case") : ImmutableList.of("issue", "incident");
        return new RepositoryIssueDetail(repository.id(), number, events.build().stream()
                .sorted(Comparator.comparing(PrTimelineEvent::timestamp)).collect(toImmutableList()), issue.issueType(), types, Optional.empty());
    }

    @Override
    public GitHubIssueSource source(HostingAccess access, HostingRepository repository, int number, boolean requireOpen)
    {
        GitLabIssueResponse issue = read(access, repository, number);
        checkArgument(!requireOpen || "opened".equals(issue.state()), "Issue is closed");
        return source(access, repository, issue);
    }

    @Override
    public GitHubIssueSource create(HostingAccess access, HostingRepository repository, String title, String body)
    {
        GitLabIssueResponse issue = http.post(access, path(access, repository), new CreateIssue(checkText(title, "title"), requireNonNull(body, "body is null")),
                GitLabIssueResponse.class).body().orElseThrow();
        return source(access, repository, issue);
    }

    @Override
    public PrTimelineEvent comment(HostingAccess access, HostingRepository repository, int number, String body)
    {
        return http.post(access, issuePath(access, repository, number) + "/notes", new Comment(checkText(body, "body")), Note.class).body().orElseThrow()
                .event(repository.webUrl() + "/-/issues/" + number);
    }

    @Override
    public void close(HostingAccess access, HostingRepository repository, int number)
    {
        http.put(access, issuePath(access, repository, number), new StateChange("close"), GitLabIssueResponse.class);
    }

    @Override
    public String updateType(HostingAccess access, HostingRepository repository, int number, String type)
    {
        String value = type == null ? "issue" : type;
        checkArgument(ImmutableList.of("issue", "incident", "test_case").contains(value), "Unsupported issue type");
        return http.put(access, issuePath(access, repository, number), new TypeChange(value), GitLabIssueResponse.class).body().orElseThrow().issueType();
    }

    @Override
    public IssueMetadata updateMetadata(HostingAccess access, HostingRepository repository, int number, List<String> assignees, List<String> labels)
    {
        List<Long> ids = assignees == null ? null : assignees.stream().map(login -> {
            GitLabIssueUser[] users = http.get(access, "users?username=" + encode(login), Optional.empty(), GitLabIssueUser[].class).body().orElseThrow();
            return Arrays.stream(users).filter(user -> login.equalsIgnoreCase(user.username())).map(GitLabIssueUser::id).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("GitLab user not found: " + login));
        }).collect(toImmutableList());
        GitLabIssueResponse updated = http.put(access, issuePath(access, repository, number),
                new MetadataChange(ids, labels == null ? null : String.join(",", labels)), GitLabIssueResponse.class).body().orElseThrow();
        RepositoryIssue card = card(access, repository, updated);
        return new IssueMetadata(card.assignees(), card.labels(), card.updatedAt());
    }

    @Override
    public IssueActivity activity(HostingAccess access, HostingRepository repository, int number)
    {
        RepositoryIssue issue = card(access, repository, read(access, repository, number));
        return new IssueActivity(issue.commentCount(), issue.updatedAt());
    }

    @Override
    public RepositoryOverview overview(HostingAccess access, HostingRepository repository)
    {
        path(access, repository);
        String project = "projects/" + encode(repository.remoteId());
        ProjectOverview overview = http.get(access, project, Optional.empty(), ProjectOverview.class).body().orElseThrow();
        checkArgument(repository.remoteId().equals(Long.toString(overview.id())), "Project identity changed");
        Languages languages = http.get(access, project + "/languages", Optional.empty(), Languages.class).body().orElseThrow();
        return new RepositoryOverview(overview.description(), overview.stars(), overview.topics(), languages.values().entrySet().stream()
                .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> Math.round(entry.getValue() * 1000))));
    }

    @Override
    public Optional<List<RepositoryContributor>> contributors(HostingAccess access, HostingRepository repository)
    {
        path(access, repository);
        // GitLab commit authors have names/emails, not the account IDs required by this projection.
        return Optional.empty();
    }

    @Override
    public List<HostingRepositoryUser> repositoryUsers(HostingAccess access, HostingRepository repository)
    {
        path(access, repository);
        return all(access, "projects/" + encode(repository.remoteId()) + "/users?", GitLabIssueUser[].class).stream()
                .map(user -> new HostingRepositoryUser(user.id(), user.username(), user.avatarUrl(), user.bot())).collect(toImmutableList());
    }

    @Override
    public List<String> repositoryTeams(HostingAccess access, HostingRepository repository)
    {
        path(access, repository);
        // GitLab's reviewer field accepts users, not GitHub-style team review requests.
        return ImmutableList.of();
    }

    @Override
    public List<RepositoryLabel> repositoryLabels(HostingAccess access, HostingRepository repository)
    {
        path(access, repository);
        return all(access, "projects/" + encode(repository.remoteId()) + "/labels?", Label[].class).stream()
                .map(label -> new RepositoryLabel(label.id(), label.name(), color(label.color()))).collect(toImmutableList());
    }

    private <T> List<T> all(HostingAccess access, String path, Class<T[]> type)
    {
        ImmutableList.Builder<T> result = ImmutableList.builder();
        for (int page = 1; ; page++) {
            var response = http.get(access, path + "&per_page=100&page=" + page, Optional.empty(), type);
            result.add(response.body().orElseThrow());
            if (!response.hasNext()) {
                return result.build();
            }
        }
    }

    private GitLabIssueResponse read(HostingAccess access, HostingRepository repository, int number)
    {
        GitLabIssueResponse issue = http.get(access, issuePath(access, repository, number), Optional.empty(), GitLabIssueResponse.class).body().orElseThrow();
        checkArgument(issue.iid() == number, "Issue identity changed");
        card(access, repository, issue);
        return issue;
    }

    private static GitHubIssueSource source(HostingAccess access, HostingRepository repository, GitLabIssueResponse response)
    {
        RepositoryIssue issue = card(access, repository, response);
        return new GitHubIssueSource(access.server().webBaseUrl().toASCIIString(), issue.id(), repository.fullPath(), issue.number(), issue.htmlUrl(),
                issue.title(), issue.body(), issue.updatedAt());
    }

    private static RepositoryIssue card(HostingAccess access, HostingRepository repository, GitLabIssueResponse issue)
    {
        checkArgument(repository.remoteId().equals(requireNonNull(issue.projectId(), "projectId is null").toString()), "Issue belongs to another project");
        checkArgument("opened".equals(issue.state()) || "closed".equals(issue.state()), "Unknown GitLab issue state");
        String url = HostingUrls.requireWithinBase(URI.create(issue.webUrl()), access.server().webBaseUrl()).toASCIIString();
        return new RepositoryIssue(issue.id(), issue.iid(), issue.title(), Optional.ofNullable(issue.description()).orElse(""),
                issue.author().username(), Optional.ofNullable(issue.author().avatarUrl()).orElse(""), url, issue.labels(),
                issue.assignees().stream().map(GitLabIssueUser::username).collect(toImmutableList()), issue.commentCount(),
                Instant.parse(issue.createdAt()), Instant.parse(issue.updatedAt()));
    }

    private static String path(HostingAccess access, HostingRepository repository)
    {
        checkArgument(access.server().provider() == GITLAB, "wrong hosting provider");
        checkArgument(repository.instanceId() == access.instanceId().orElseThrow(), "repository belongs to another hosting instance");
        return "projects/" + encode(repository.remoteId()) + "/issues";
    }

    private static String issuePath(HostingAccess access, HostingRepository repository, int number)
    {
        checkArgument(number > 0, "number must be positive");
        return path(access, repository) + "/" + number;
    }

    private static String color(String color)
    {
        return color.startsWith("#") ? color.substring(1) : color;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProjectOverview(long id, String description, @JsonProperty("star_count") int stars, List<String> topics) {}

    private record Languages(Map<String, Double> values)
    {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        private Languages
        {
            values = ImmutableMap.copyOf(values);
        }
    }

    private record Comment(String body) {}

    private record CreateIssue(String title, String description) {}

    private record StateChange(@JsonProperty("state_event") String event) {}

    private record TypeChange(@JsonProperty("issue_type") String type) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record MetadataChange(@JsonProperty("assignee_ids") List<Long> assignees, String labels) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Label(long id, String name, String color) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Milestone(String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Note(Long id, String body, GitLabIssueUser author, boolean system, @JsonProperty("created_at") String createdAt, List<String> summary)
    {
        PrTimelineEvent event(String issueUrl)
        {
            if (id == null && summary != null) {
                return new PrTimelineEvent(null, "system", null, null, String.join("\n", summary), PrTimelinePayload.empty(), Optional.empty());
            }
            return new PrTimelineEvent(id, system ? "system" : "commented", author.username(), Instant.parse(createdAt), body,
                    PrTimelinePayload.builder().actorAvatarUrl(author.avatarUrl()).htmlUrl(issueUrl + "#note_" + id).build(), Optional.empty());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResourceEvent(long id, GitLabIssueUser user, @JsonProperty("created_at") String createdAt, String state, String action, Label label, Milestone milestone)
    {
        PrTimelineEvent event(String kind, PrTimelinePayload payload)
        {
            return new PrTimelineEvent(null, kind, user.username(), Instant.parse(createdAt), null,
                    payload.toBuilder().actorAvatarUrl(user.avatarUrl()).build(), Optional.of("gitlab:" + kind + ":" + id));
        }
    }
}
