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
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.IssueContextBridge;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.RepositoryIssueDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Reads only ByteQuay's durable source and local caches; it has no GitHub client or credentials. */
@Component
public class IssueContextBridgeResolverImpl
        implements BridgeResolver<IssueContextBridge>
{
    private static final int MAXIMUM_EVENTS = 20;
    private static final int MAXIMUM_EVENT_BODY = 2_000;
    private final AgentTaskStore tasks;
    private final IssueDevelopmentStore sources;
    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final RepositoryIssueDetailStore details;
    private final ObjectMapper objectMapper;

    public IssueContextBridgeResolverImpl(AgentTaskStore tasks, IssueDevelopmentStore sources, WorkspaceStore workspaces,
            RepositoryIssueStore issues, RepositoryIssueDetailStore details, ObjectMapper objectMapper)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.sources = requireNonNull(sources, "sources is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.details = requireNonNull(details, "details is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    public Optional<IssueContextBridge> resolve(String taskId)
    {
        return scope(taskId).map(ignored -> new IssueContextBridge(() -> read(taskId), IssueContextBridge.INSTRUCTION));
    }

    private Optional<IssueTask> scope(String taskId)
    {
        return tasks.find(TaskId.valueOf(taskId))
                .filter(task -> task.interactive() && task.state() == TaskState.OPEN && task.worktreeRemovedAt().isEmpty())
                .flatMap(task -> sources.findByTaskId(taskId).filter(link -> task.workspaceId().filter(link.workspaceId()::equals).isPresent()));
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
        ArrayNode entries = timeline.putArray("events");
        for (PrTimelineEvent event : recent) {
            ObjectNode entry = entries.addObject();
            entry.put("event", event.event());
            entry.put("actor", event.actor());
            Optional.ofNullable(event.timestamp()).ifPresent(time -> entry.put("timestamp", time.toString()));
            String body = Optional.ofNullable(event.body()).orElse("");
            entry.put("body", body.substring(0, Math.min(body.length(), MAXIMUM_EVENT_BODY)));
            entry.put("bodyTruncated", body.length() > MAXIMUM_EVENT_BODY);
            entry.put("label", event.payload().label());
            entry.put("state", event.payload().state());
            entry.put("sourceTitle", event.payload().sourceTitle());
            entry.put("sourceUrl", event.payload().sourceUrl());
            entry.put("milestone", event.payload().milestoneTitle());
        }
        return result.toString();
    }
}
