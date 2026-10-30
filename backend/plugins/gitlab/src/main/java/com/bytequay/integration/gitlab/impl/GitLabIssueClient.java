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
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingRepositoryUser;
import com.bytequay.core.hosting.HostingTarget;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.issuePath;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.mapped;
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.projectPath;
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
    public IssueSyncPage list(HostingTarget target, Optional<Instant> since, int page)
    {
        checkArgument(page > 0, "page must be positive");
        String path = projectPath(target) + "/issues?scope=all&state=" + (since.isPresent() ? "all" : "opened")
                + "&order_by=updated_at&sort=asc&per_page=100&page=" + page + since.map(value -> "&updated_after=" + encode(value.toString())).orElse("");
        var response = http.get(target.access(), path, Optional.empty(), GitLabIssueResponse[].class);
        ImmutableList.Builder<RepositoryIssue> open = ImmutableList.builder();
        ImmutableList.Builder<Integer> closed = ImmutableList.builder();
        for (GitLabIssueResponse issue : response.body().orElseThrow()) {
            RepositoryIssue card = card(target, issue);
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
    public Optional<RepositoryIssue> find(HostingTarget target, int number)
    {
        GitLabIssueResponse issue = read(target, number);
        RepositoryIssue card = card(target, issue);
        return "closed".equals(issue.state()) ? Optional.empty() : Optional.of(card);
    }

    @Override
    public GitHubIssuePreview preview(HostingTarget target, int number)
    {
        GitLabIssueResponse response = read(target, number);
        RepositoryIssue issue = card(target, response);
        return new GitHubIssuePreview(issue.title(), issue.body(), issue.author(), issue.labels(), issue.updatedAt(),
                "closed".equals(response.state()) ? "closed" : "open", false, issue.htmlUrl(), issue.authorAvatarUrl());
    }

    @Override
    public RepositoryIssueDetail detail(HostingTarget target, int number)
    {
        GitLabIssueResponse issue = read(target, number);
        String path = issuePath(target, number);
        ImmutableList.Builder<PrTimelineEvent> events = ImmutableList.builder();
        for (GitLabNote note : http.pages(target.access(), path + "/notes?sort=asc&order_by=created_at", GitLabNote[].class)) {
            events.add(mapped("note", () -> event(note, issue.webUrl())));
        }
        for (ResourceEvent event : http.pages(target.access(), path + "/resource_state_events", ResourceEvent[].class)) {
            events.add(mapped("state event", () -> event.event("closed".equals(event.state()) ? "closed" : "reopened", PrTimelinePayload.empty())));
        }
        for (ResourceEvent event : http.pages(target.access(), path + "/resource_label_events", ResourceEvent[].class)) {
            // GitLab retains these events after the label has been deleted.
            if (event.label() == null) {
                continue;
            }
            events.add(mapped("label event", () -> event.event("add".equals(event.action()) ? "labeled" : "unlabeled",
                    PrTimelinePayload.builder().label(event.label().name()).labelColor(color(event.label().color())).build())));
        }
        for (ResourceEvent event : http.pages(target.access(), path + "/resource_milestone_events", ResourceEvent[].class)) {
            events.add(mapped("milestone event", () -> event.event("add".equals(event.action()) ? "milestoned" : "demilestoned",
                    PrTimelinePayload.builder().milestoneTitle(event.milestone().title()).build())));
        }
        List<String> types = "test_case".equals(issue.issueType()) ? ImmutableList.of("issue", "incident", "test_case") : ImmutableList.of("issue", "incident");
        return new RepositoryIssueDetail(target.repository().id(), number, events.build().stream()
                .sorted(Comparator.comparing(PrTimelineEvent::timestamp)).collect(toImmutableList()), issue.issueType(), types, Optional.empty());
    }

    @Override
    public GitHubIssueSource source(HostingTarget target, int number, boolean requireOpen)
    {
        GitLabIssueResponse issue = read(target, number);
        checkArgument(!requireOpen || "opened".equals(issue.state()), "Issue is closed");
        return source(target, issue);
    }

    @Override
    public GitHubIssueSource create(HostingTarget target, String title, String body)
    {
        GitLabIssueResponse issue = http.post(target.access(), projectPath(target) + "/issues", new CreateIssue(checkText(title, "title"), requireNonNull(body, "body is null")),
                GitLabIssueResponse.class).body().orElseThrow();
        return source(target, issue);
    }

    @Override
    public PrTimelineEvent comment(HostingTarget target, int number, String body)
    {
        GitLabNote note = http.post(target.access(), issuePath(target, number) + "/notes", new Comment(checkText(body, "body")), GitLabNote.class).body().orElseThrow();
        return mapped("note", () -> event(note, target.repository().webUrl() + "/-/issues/" + number));
    }

    @Override
    public void close(HostingTarget target, int number)
    {
        http.put(target.access(), issuePath(target, number), new StateChange("close"), GitLabIssueResponse.class);
    }

    @Override
    public String updateType(HostingTarget target, int number, String type)
    {
        String value = type == null ? "issue" : type;
        checkArgument(ImmutableList.of("issue", "incident", "test_case").contains(value), "Unsupported issue type");
        return http.put(target.access(), issuePath(target, number), new TypeChange(value), GitLabIssueResponse.class).body().orElseThrow().issueType();
    }

    @Override
    public IssueMetadata updateMetadata(HostingTarget target, int number, List<String> assignees, List<String> labels)
    {
        List<Long> ids = assignees == null ? null : assignees.stream().map(login -> http.user(target.access(), login).id()).collect(toImmutableList());
        GitLabIssueResponse updated = http.put(target.access(), issuePath(target, number),
                new MetadataChange(ids, labels == null ? null : String.join(",", labels)), GitLabIssueResponse.class).body().orElseThrow();
        RepositoryIssue card = card(target, updated);
        return new IssueMetadata(card.assignees(), card.labels(), card.updatedAt());
    }

    @Override
    public IssueActivity activity(HostingTarget target, int number)
    {
        RepositoryIssue issue = card(target, read(target, number));
        return new IssueActivity(issue.commentCount(), issue.updatedAt());
    }

    @Override
    public RepositoryOverview overview(HostingTarget target)
    {
        String project = projectPath(target);
        ProjectOverview overview = http.get(target.access(), project, Optional.empty(), ProjectOverview.class).body().orElseThrow();
        if (!target.repository().remoteId().equals(Long.toString(overview.id()))) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned another project");
        }
        Languages languages = http.get(target.access(), project + "/languages", Optional.empty(), Languages.class).body().orElseThrow();
        return mapped("project", () -> new RepositoryOverview(overview.description(), overview.stars(), overview.topics(), languages.values().entrySet().stream()
                .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> Math.round(entry.getValue() * 1000)))));
    }

    @Override
    public Optional<List<RepositoryContributor>> contributors(HostingTarget target)
    {
        projectPath(target);
        // GitLab commit authors have names/emails, not the account IDs required by this projection.
        return Optional.empty();
    }

    @Override
    public List<HostingRepositoryUser> repositoryUsers(HostingTarget target)
    {
        List<GitLabUser> users = http.pages(target.access(), projectPath(target) + "/users", GitLabUser[].class);
        return mapped("project member", () -> users.stream()
                .map(user -> new HostingRepositoryUser(user.id(), user.username(), user.avatarUrl(), user.bot())).collect(toImmutableList()));
    }

    @Override
    public List<String> repositoryTeams(HostingTarget target)
    {
        projectPath(target);
        // GitLab's reviewer field accepts users, not GitHub-style team review requests.
        return ImmutableList.of();
    }

    @Override
    public List<RepositoryLabel> repositoryLabels(HostingTarget target)
    {
        List<Label> labels = http.pages(target.access(), projectPath(target) + "/labels", Label[].class);
        return mapped("label", () -> labels.stream().map(label -> new RepositoryLabel(label.id(), label.name(), color(label.color()))).collect(toImmutableList()));
    }

    private GitLabIssueResponse read(HostingTarget target, int number)
    {
        GitLabIssueResponse issue = http.get(target.access(), issuePath(target, number), Optional.empty(), GitLabIssueResponse.class).body().orElseThrow();
        if (!Integer.valueOf(number).equals(issue.iid())) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned another issue");
        }
        card(target, issue);
        return issue;
    }

    private static GitHubIssueSource source(HostingTarget target, GitLabIssueResponse response)
    {
        RepositoryIssue issue = card(target, response);
        return new GitHubIssueSource(target.access().server().webBaseUrl().toASCIIString(), issue.id(), target.repository().fullPath(), issue.number(), issue.htmlUrl(),
                issue.title(), issue.body(), issue.updatedAt());
    }

    private static RepositoryIssue card(HostingTarget target, GitLabIssueResponse issue)
    {
        return mapped("issue", () -> {
            checkArgument(target.repository().remoteId().equals(requireNonNull(issue.projectId(), "projectId is null").toString()), "Issue belongs to another project");
            checkArgument("opened".equals(issue.state()) || "closed".equals(issue.state()), "Unknown GitLab issue state");
            String url = HostingUrls.requireWithinBase(URI.create(issue.webUrl()), target.access().server().webBaseUrl()).toASCIIString();
            return new RepositoryIssue(issue.id(), issue.iid(), issue.title(), Optional.ofNullable(issue.description()).orElse(""),
                    issue.author().username(), Optional.ofNullable(issue.author().avatarUrl()).orElse(""), url, issue.labels(),
                    issue.assignees().stream().map(GitLabUser::username).collect(toImmutableList()), issue.commentCount(),
                    Instant.parse(issue.createdAt()), Instant.parse(issue.updatedAt()));
        });
    }

    private static PrTimelineEvent event(GitLabNote note, String issueUrl)
    {
        if (note.id() == null && note.summary() != null) {
            return new PrTimelineEvent(null, "system", null, null, String.join("\n", note.summary()), PrTimelinePayload.empty(), Optional.empty());
        }
        return new PrTimelineEvent(note.id(), note.system() ? "system" : "commented", note.author().username(), Instant.parse(note.createdAt()), note.body(),
                PrTimelinePayload.builder().actorAvatarUrl(note.author().avatarUrl()).htmlUrl(issueUrl + "#note_" + note.id()).build(), Optional.empty());
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
    private record ResourceEvent(long id, GitLabUser user, @JsonProperty("created_at") String createdAt, String state, String action, Label label, Milestone milestone)
    {
        PrTimelineEvent event(String kind, PrTimelinePayload payload)
        {
            return new PrTimelineEvent(null, kind, user.username(), Instant.parse(createdAt), null,
                    payload.toBuilder().actorAvatarUrl(user.avatarUrl()).build(), Optional.of("gitlab:" + kind + ":" + id));
        }
    }
}
