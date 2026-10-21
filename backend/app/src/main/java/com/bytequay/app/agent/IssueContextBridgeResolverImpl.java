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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.IssueContextBridge;
import com.bytequay.app.context.ContextText;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.service.epic.EpicSources;
import com.bytequay.app.workitem.WorkItemCatalog;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.JiraIssueTask;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.RepositoryIssueDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/** Reads only ByteQuay's durable source, work items and local caches; it has no GitHub client or credentials. */
@Component
public class IssueContextBridgeResolverImpl
        implements BridgeResolver<IssueContextBridge>
{
    private static final String HOST = "github.com";
    private static final int MAXIMUM_EVENTS = 20;
    private static final int MAXIMUM_EVENT_BODY = 2_000;
    private static final int MAXIMUM_EPIC_DESCRIPTION = 12_000;
    private static final int MAXIMUM_EPIC_ENTRIES = 100;
    private static final int MAXIMUM_SIBLING_DONE_WHEN = 600;
    private final AgentTaskStore tasks;
    private final IssueDevelopmentStore sources;
    private final JiraIssueTaskStore jiraSources;
    private final JiraService jira;
    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final RepositoryIssueDetailStore details;
    private final ObjectMapper objectMapper;
    private final WorkItemStore workItems;
    private final EpicStore epics;
    private final ObjectProvider<WorkItemCatalog> catalog;

    public IssueContextBridgeResolverImpl(AgentTaskStore tasks, IssueDevelopmentStore sources, WorkspaceStore workspaces,
            RepositoryIssueStore issues, RepositoryIssueDetailStore details, ObjectMapper objectMapper, JiraIssueTaskStore jiraSources, JiraService jira,
            WorkItemStore workItems, EpicStore epics, ObjectProvider<WorkItemCatalog> catalog)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.sources = requireNonNull(sources, "sources is null");
        this.jiraSources = requireNonNull(jiraSources, "jiraSources is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.details = requireNonNull(details, "details is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.epics = requireNonNull(epics, "epics is null");
        this.catalog = requireNonNull(catalog, "catalog is null");
    }

    @Override
    public Optional<IssueContextBridge> resolve(String taskId)
    {
        Optional<WorkItem> taskWork = taskWork(taskId);
        String instruction = taskWork.filter(item -> item.epicId().isPresent()).isPresent()
                ? IssueContextBridge.INSTRUCTION + "\n" + IssueContextBridge.EPIC_INSTRUCTION : IssueContextBridge.INSTRUCTION;
        return scope(taskId).map(ignored -> new IssueContextBridge(() -> read(taskId), instruction))
                .or(() -> jiraScope(taskId).map(ignored -> new IssueContextBridge(() -> readJira(taskId), instruction)))
                .or(() -> taskWork.map(ignored -> new IssueContextBridge(() -> readWork(taskId), IssueContextBridge.WORK_INSTRUCTION
                        + (taskWork.orElseThrow().epicId().isPresent() ? "\n" + IssueContextBridge.EPIC_INSTRUCTION : ""))));
    }

    private Optional<AgentTask> readable(String taskId)
    {
        return tasks.find(TaskId.valueOf(taskId)).filter(task -> task.interactive() && task.state() == TaskState.OPEN && task.worktreeRemovedAt().isEmpty());
    }

    private Optional<IssueTask> scope(String taskId)
    {
        return readable(taskId).flatMap(task -> sources.findByTaskId(taskId).filter(link -> task.workspaceId().filter(link.workspaceId()::equals).isPresent()));
    }

    private Optional<JiraIssueTask> jiraScope(String taskId)
    {
        return readable(taskId).flatMap(task -> jiraSources.findByTaskId(taskId).filter(link -> task.workspaceId().filter(link.workspaceId()::equals).isPresent()));
    }

    /** Retained work is readable only by its own live task in the same workspace. */
    private Optional<WorkItem> taskWork(String taskId)
    {
        return readable(taskId).flatMap(task -> workItems.findByIdentity(WorkItemLink.taskIdentity(taskId))
                .filter(item -> task.workspaceId().filter(item.workspaceId()::equals).isPresent()));
    }

    private String readWork(String taskId)
    {
        WorkItem item = taskWork(taskId).orElseThrow(() -> new AgentConflictException("This task no longer has readable work-item context."));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("source", "ByteQuay local data only; no remote request was made.");
        addWorkContext(result, item);
        return result.toString();
    }

    private void addWorkContext(ObjectNode result, WorkItem item)
    {
        result.set("workItem", objectMapper.valueToTree(item));
        result.set("contextLinks", objectMapper.valueToTree(item.contextLinks().isEmpty() ? ImmutableList.of() : catalog.getObject().contextLinks(item.workspaceId(), item.id())));
        item.epicId().ifPresent(ignored -> result.set("epic", epic(item)));
    }

    /** The Epic as ByteQuay keeps it: its local description or cached source, source children, and its work in the user's order. */
    private ObjectNode epic(WorkItem current)
    {
        String epicId = current.epicId().orElseThrow();
        ObjectNode epic = objectMapper.createObjectNode();
        Optional<String> localId = EpicSources.localId(epicId);
        Optional<EpicText> text;
        if (localId.isPresent()) {
            text = workItems.find(localId.orElseThrow()).filter(item -> item.workspaceId().equals(current.workspaceId()))
                    .map(item -> new EpicText("Local", "", item.title(), item.acceptanceCriteria()));
            epic.put("status", text.isPresent() ? "local" : "missing");
        }
        else {
            epic.put("warning", "The Epic's text and children are cached observations that may be stale; missing data does not mean the Epic is closed or has no other work.");
            text = EpicSources.ticket(epicId)
                    .map(ticket -> jira.cachedIssue(ticket.cloudId(), ticket.issueId()).map(issue -> new EpicText(issue.key(), issue.url(), issue.title(), issue.body())))
                    .orElseGet(() -> epicIssue(current.workspaceId(), EpicSources.issueId(epicId))
                            .map(issue -> new EpicText("#" + issue.number(), issue.htmlUrl(), issue.title(), issue.body())));
            epic.put("status", text.isPresent() ? "cached" : "missing");
        }
        text.ifPresent(cached -> {
            epic.put("reference", cached.reference());
            epic.put("url", cached.url());
            epic.put("title", cached.title());
            epic.put("description", ContextText.excerpt(cached.description(), MAXIMUM_EPIC_DESCRIPTION));
        });
        Optional<Epic> record = epics.find(epicId).filter(found -> found.workspaceId().equals(current.workspaceId()));
        record.flatMap(Epic::concludedAt).ifPresent(at -> epic.put("concludedAt", at.toString()).put("outcome", record.orElseThrow().outcome()));
        record.flatMap(Epic::childrenReadAt).ifPresent(at -> epic.put("childrenReadAt", at.toString()));
        epic.set("children", objectMapper.valueToTree(record.map(Epic::children).orElseGet(ImmutableList::of).stream().limit(MAXIMUM_EPIC_ENTRIES)
                .map(child -> new EpicChildContext(child.label(), child.title(), child.status())).collect(toImmutableList())));
        List<WorkItem> work = workItems.list(current.workspaceId()).stream().filter(item -> item.epicId().equals(current.epicId()))
                .sorted(Comparator.comparingLong(WorkItem::epicRank).thenComparing(WorkItem::createdAt)).collect(toImmutableList());
        Map<String, String> titles = work.stream().collect(toImmutableMap(WorkItem::id, WorkItem::title));
        // Its own item in full even when the list is cut short before it.
        epic.set("currentWork", objectMapper.valueToTree(work(current, work.stream().map(WorkItem::id).collect(toImmutableList()).indexOf(current.id()) + 1, titles, true)));
        epic.put("workCount", work.size());
        epic.put("workTruncated", work.size() > MAXIMUM_EPIC_ENTRIES);
        epic.set("work", objectMapper.valueToTree(IntStream.range(0, Math.min(work.size(), MAXIMUM_EPIC_ENTRIES))
                .mapToObj(index -> work(work.get(index), index + 1, titles, work.get(index).id().equals(current.id()))).collect(toImmutableList())));
        return epic;
    }

    private static EpicWorkContext work(WorkItem item, int position, Map<String, String> titles, boolean current)
    {
        return new EpicWorkContext(position, item.title(), item.state(), current,
                current ? item.acceptanceCriteria() : ContextText.excerpt(item.acceptanceCriteria(), MAXIMUM_SIBLING_DONE_WHEN),
                item.blockedBy().stream().filter(titles::containsKey).map(titles::get).collect(toImmutableList()),
                item.links().stream().map(WorkItemLink::reference).filter(reference -> reference.kind() != GoalItemKind.TASK)
                        .map(reference -> new EpicLinkContext(reference.kind(), reference.label(), reference.title())).collect(toImmutableList()));
    }

    /** The Epic's issue while the workspace still monitors its repository and the store still holds that same issue. */
    private Optional<RepositoryIssue> epicIssue(String workspaceId, long issueId)
    {
        return sources.findEpic(HOST, issueId).flatMap(marker -> workspaces.findById(workspaceId)
                        .filter(workspace -> workspace.monitoredRepository().fullName().equalsIgnoreCase(marker.repo()))
                        .flatMap(workspace -> issues.find(workspace.monitoredRepositoryId(), marker.number())))
                .filter(issue -> issue.id() == issueId);
    }

    private record EpicText(String reference, String url, String title, String description) {}

    private String readJira(String taskId)
    {
        JiraIssueTask link = jiraScope(taskId).orElseThrow(() -> new AgentConflictException("This task no longer has readable Jira context."));
        Optional<JiraIssue> cached = cachedJira(link);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("source", "ByteQuay local data only; no remote request was made.");
        result.put("warning", "The accepted Jira assignment is retained independently of current cached metadata. Missing or stale cache data does not imply completion.");
        result.set("acceptedIssue", objectMapper.valueToTree(link.source()));
        result.put("instructions", link.instructions());
        result.put("acceptedAt", link.createdAt().toString());
        ObjectNode metadata = result.putObject("cachedMetadata");
        metadata.put("status", cached.map(issue -> issue.updatedAt().isBefore(link.source().updatedAt()) ? "stale" : "cached").orElse("missing"));
        metadata.put("mayBeStale", true);
        cached.ifPresent(issue -> {
            metadata.put("updatedAt", issue.updatedAt().toString());
            metadata.put("key", issue.key());
            metadata.set("completedAt", objectMapper.valueToTree(issue.completedAt().map(Instant::toString)));
            metadata.put("type", issue.type());
            metadata.set("issueStatus", objectMapper.valueToTree(issue.status()));
            metadata.set("assignee", objectMapper.valueToTree(issue.assignee()));
            metadata.set("labels", objectMapper.valueToTree(issue.labels()));
            metadata.set("priority", objectMapper.valueToTree(issue.priority()));
            metadata.set("parent", objectMapper.valueToTree(issue.parent()));
            metadata.set("dueDate", objectMapper.valueToTree(issue.dueDate()));
            metadata.put("hasChildren", issue.hasChildren());
            metadata.put("differsFromAcceptedDescription", !issue.title().equals(link.source().title()) || !issue.body().equals(link.source().body()));
        });
        ObjectNode timeline = result.putObject("cachedTimeline");
        timeline.put("status", cached.isPresent() ? "cached" : "missing");
        timeline.put("mayBeStale", true);
        timeline.put("coverage", "Only comments currently present in the local cache; absence does not prove that Jira has no comments.");
        List<JiraDtos.Comment> comments = cached.map(JiraIssue::comments).orElseGet(ImmutableList::of);
        timeline.put("totalCachedComments", comments.size());
        timeline.put("truncated", cached.map(JiraIssue::commentsTruncated).orElse(false) || comments.size() > MAXIMUM_EVENTS);
        timeline.set("events", objectMapper.valueToTree(comments.stream().skip(Math.max(0, comments.size() - MAXIMUM_EVENTS))
                .map(comment -> new JiraCommentContext(comment.id(), "commented", comment.author().displayName(), comment.created(), comment.updated(),
                        comment.body().substring(0, Math.min(comment.body().length(), MAXIMUM_EVENT_BODY)), comment.body().length() > MAXIMUM_EVENT_BODY))
                .collect(toImmutableList())));
        taskWork(taskId).ifPresent(item -> addWorkContext(result, item));
        return result.toString();
    }

    private Optional<JiraIssue> cachedJira(JiraIssueTask link)
    {
        try {
            var source = link.source();
            return Optional.of(jira.issue(source.connectionId(), source.cloudId(), source.issueId()));
        }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode().value() != 404) {
                throw failure;
            }
            return Optional.empty();
        }
    }

    private String read(String taskId)
    {
        IssueTask link = scope(taskId).orElseThrow(() -> new AgentConflictException("This task no longer has readable issue context."));
        Optional<Workspace> workspace = workspaces.findById(link.workspaceId())
                .filter(value -> value.monitoredRepository().fullName().equalsIgnoreCase(link.source().repo()));
        Optional<RepositoryIssue> cached = workspace.flatMap(value -> issues.find(value.monitoredRepositoryId(), link.source().number()))
                .filter(issue -> issue.id() == link.source().issueId());
        Optional<RepositoryIssueDetail> detail = workspace.flatMap(value -> details.find(value.monitoredRepositoryId(), link.source().number()));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("source", "ByteQuay local data only; no remote request was made.");
        result.put("warning", "Accepted issue text is the saved assignment. Cached metadata and timeline may be stale; "
                + "missing data does not mean the issue is closed or has no activity.");
        result.set("acceptedIssue", objectMapper.valueToTree(link.source()));
        result.put("instructions", link.instructions().orElse(""));
        result.put("acceptedAt", link.createdAt().toString());
        ObjectNode metadata = result.putObject("cachedMetadata");
        metadata.put("status", cached.map(issue -> issue.updatedAt().isBefore(link.source().updatedAt()) ? "stale" : "cached").orElse("missing"));
        metadata.put("mayBeStale", true);
        cached.ifPresent(issue -> {
            metadata.put("updatedAt", issue.updatedAt().toString());
            metadata.put("state", "open when cached");
            metadata.put("author", issue.author());
            metadata.put("commentCount", issue.commentCount());
            metadata.set("labels", objectMapper.valueToTree(issue.labels()));
            metadata.set("assignees", objectMapper.valueToTree(issue.assignees()));
            metadata.put("differsFromAcceptedDescription", !issue.title().equals(link.source().title()) || !issue.body().equals(link.source().body()));
        });
        detail.map(RepositoryIssueDetail::currentType).ifPresent(type -> metadata.put("type", type));
        Instant latestKnownUpdate = cached.map(RepositoryIssue::updatedAt).filter(time -> time.isAfter(link.source().updatedAt())).orElse(link.source().updatedAt());
        Optional<Instant> refreshedAt = detail.flatMap(RepositoryIssueDetail::refreshedAt);
        ObjectNode timeline = result.putObject("cachedTimeline");
        timeline.put("status", refreshedAt.map(time -> time.isBefore(latestKnownUpdate) ? "stale" : "cached").orElse("missing"));
        timeline.put("mayBeStale", true);
        refreshedAt.ifPresent(time -> timeline.put("refreshedAt", time.toString()));
        List<PrTimelineEvent> events = detail.map(RepositoryIssueDetail::timeline).orElseGet(ImmutableList::of);
        List<PrTimelineEvent> recent = events.stream()
                .sorted(Comparator.comparing(PrTimelineEvent::timestamp, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAXIMUM_EVENTS).collect(toImmutableList());
        timeline.put("totalCachedEvents", events.size());
        timeline.put("truncated", events.size() > recent.size());
        timeline.set("events", objectMapper.valueToTree(recent.stream().map(IssueContextBridgeResolverImpl::event).collect(toImmutableList())));
        taskWork(taskId).ifPresent(item -> addWorkContext(result, item));
        return result.toString();
    }

    private static TimelineEventContext event(PrTimelineEvent event)
    {
        String body = Optional.ofNullable(event.body()).orElse("");
        return new TimelineEventContext(event.event(), event.actor(), Optional.ofNullable(event.timestamp()).map(Instant::toString),
                body.substring(0, Math.min(body.length(), MAXIMUM_EVENT_BODY)), body.length() > MAXIMUM_EVENT_BODY,
                event.payload().label(), event.payload().state(), event.payload().sourceTitle(), event.payload().sourceUrl(), event.payload().milestoneTitle());
    }

    private record EpicChildContext(String reference, String title, String status) {}

    private record EpicLinkContext(GoalItemKind kind, String reference, String title) {}

    private record EpicWorkContext(int position, String title, WorkItem.State state, boolean current, String doneWhen, List<String> waitsOn, List<EpicLinkContext> links) {}

    private record JiraCommentContext(String id, String event, String actor, String createdAt, String updatedAt, String body, boolean bodyTruncated) {}

    private record TimelineEventContext(
            String event, String actor, @JsonInclude(NON_ABSENT) Optional<String> timestamp, String body, boolean bodyTruncated,
            String label, String state, String sourceTitle, String sourceUrl, String milestone) {}
}
