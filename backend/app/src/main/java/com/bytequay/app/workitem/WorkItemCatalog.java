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
package com.bytequay.app.workitem;

import com.bytequay.app.jira.JiraService;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemContextLink;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraIssue;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/** Reads snapshots only. Browsing never imports requests into Goals or writes to a remote service. */
@Service
public class WorkItemCatalog
{
    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final JiraService jira;
    private final WorkItemStore store;
    private final WorkItemService work;

    public WorkItemCatalog(WorkspaceStore workspaces, RepositoryIssueStore issues, JiraService jira, WorkItemStore store, WorkItemService work)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.store = requireNonNull(store, "store is null");
        this.work = requireNonNull(work, "work is null");
    }

    public List<UnifiedWorkItem> list(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Map<String, UnifiedWorkItem> result = new LinkedHashMap<>();
        Map<String, WorkItemView> views = work.list(workspaceId).stream().collect(toImmutableMap(WorkItemView::id, item -> item));
        Map<String, String> owners = new LinkedHashMap<>();
        for (WorkItem item : store.list(workspaceId)) {
            item.links().forEach(link -> owners.put(link.identity(), item.id()));
            Optional<WorkItemLink> source = item.links().stream().filter(link -> link.reference().kind() == GoalItemKind.ISSUE
                    || link.reference().kind() == GoalItemKind.JIRA).findFirst();
            String body = source.flatMap(link -> link.issueSource().map(GitHubIssueSource::body)
                    .or(() -> link.jiraSource().map(value -> value.body()))).orElse(item.acceptanceCriteria());
            result.put(item.id(), new UnifiedWorkItem(item.id(), source.map(link -> type(link.reference().kind())).orElse(UnifiedWorkItem.Source.LOCAL),
                    item.title(), body, item.state().name(), item.state() == WorkItem.State.DONE || item.state() == WorkItem.State.DROPPED, item.updatedAt(),
                    source.map(WorkItemLink::reference), Optional.ofNullable(views.get(item.id()))));
        }
        int page = 0;
        RepositoryIssuePage found;
        do {
            found = issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), page++, 100);
            found.issues().forEach(issue -> {
                WorkItemLink link = GoalReferenceReader.issueLink(new GitHubIssueSource("github.com", issue.id(), workspace.monitoredRepository().fullName(),
                        issue.number(), issue.htmlUrl(), issue.title(), issue.body(), issue.updatedAt()), issue.updatedAt());
                add(result, owners, views, link, issue.title(), issue.body(), "Open", false);
            });
        }
        while (found.hasMore());
        for (JiraIssue issue : jira.cachedIssues()) {
            if (issue.workspace().filter(value -> value.id().equals(workspaceId)).isEmpty()) {
                continue;
            }
            WorkItemLink link = GoalReferenceReader.jiraLink(new JiraIssueSource(issue.connectionId(), issue.cloudId(), issue.id(),
                    issue.key(), issue.url(), issue.title(), issue.body(), issue.updatedAt()), issue.updatedAt());
            add(result, owners, views, link, issue.title(), issue.body(), issue.status().name(), issue.status().category().equals("done"));
        }
        return result.values().stream().sorted(Comparator.comparing(UnifiedWorkItem::updatedAt).reversed()).collect(toImmutableList());
    }

    private static void add(Map<String, UnifiedWorkItem> result, Map<String, String> owners, Map<String, WorkItemView> views,
            WorkItemLink link, String title, String body, String status, boolean closed)
    {
        String id = owners.getOrDefault(link.identity(), link.identity());
        // One local item may be exposed to both providers; its links retain both destinations.
        if (result.containsKey(id) && result.get(id).reference().filter(ref -> !ref.equals(link.reference())).isPresent()
                && result.get(id).source() != type(link.reference().kind())) {
            return;
        }
        result.put(id, new UnifiedWorkItem(id, type(link.reference().kind()), title, body, status, closed, link.reference().addedAt(),
                Optional.of(link.reference()), Optional.ofNullable(views.get(id))));
    }

    public UnifiedWorkItem read(String workspaceId, String id)
    {
        String resolved = resolveId(workspaceId, id);
        return list(workspaceId).stream().filter(item -> item.id().equals(resolved)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Work item not found in this workspace"));
    }

    public WorkItemView linkContext(String workspaceId, String id, long expectedRevision, String targetId)
    {
        requireContextRequest(expectedRevision, targetId);
        WorkItem item = work.requireItem(workspaceId, id);
        UnifiedWorkItem target = read(workspaceId, targetId);
        checkArgument(!target.id().equals(id), "A work item cannot reference itself");
        if (item.contextLinks().stream().anyMatch(link -> resolveId(workspaceId, link.targetId()).equals(target.id()))) {
            return work.changeContextLinks(workspaceId, id, expectedRevision, item.contextLinks(), "");
        }
        checkArgument(item.contextLinks().size() < 50, "A work item can have at most 50 context links");
        return work.changeContextLinks(workspaceId, id, expectedRevision, ImmutableList.<WorkItemContextLink>builder().addAll(item.contextLinks())
                .add(new WorkItemContextLink(target.id(), target.title())).build(), "Linked context: " + target.title());
    }

    public WorkItemView unlinkContext(String workspaceId, String id, long expectedRevision, String targetId)
    {
        requireContextRequest(expectedRevision, targetId);
        WorkItem item = work.requireItem(workspaceId, id);
        String resolved = resolveId(workspaceId, targetId);
        List<WorkItemContextLink> remaining = item.contextLinks().stream()
                .filter(link -> !link.targetId().equals(targetId) && !resolveId(workspaceId, link.targetId()).equals(resolved)).collect(toImmutableList());
        String title = item.contextLinks().stream().filter(link -> !remaining.contains(link)).map(WorkItemContextLink::title).findFirst().orElse(targetId);
        return work.changeContextLinks(workspaceId, id, expectedRevision, remaining, "Unlinked context: " + title);
    }

    /** Direct summaries only: cycles never recursively expand another item's private context. */
    public List<WorkItemContextView> contextLinks(String workspaceId, String id)
    {
        WorkItem item = work.requireItem(workspaceId, id);
        if (item.contextLinks().isEmpty()) {
            return ImmutableList.of();
        }
        Map<String, UnifiedWorkItem> available = list(workspaceId).stream().collect(toImmutableMap(UnifiedWorkItem::id, target -> target));
        return item.contextLinks().stream().map(link -> {
            String resolved = resolveId(workspaceId, link.targetId());
            Optional<UnifiedWorkItem> target = Optional.ofNullable(available.get(resolved)).filter(value -> !value.id().equals(id));
            return target.map(value -> new WorkItemContextView(link.targetId(), value.id(), value.source(), value.title(), value.status(), true, value.reference()))
                    .orElseGet(() -> new WorkItemContextView(link.targetId(), resolved, sourceOf(link.targetId()), link.title(),
                            resolved.equals(id) ? "Same work item" : "Unavailable", false, Optional.empty()));
        }).collect(toImmutableList());
    }

    private String resolveId(String workspaceId, String id)
    {
        return store.findByIdentity(id).filter(item -> item.workspaceId().equals(workspaceId)).map(WorkItem::id).orElse(id);
    }

    private static void requireContextRequest(long expectedRevision, String targetId)
    {
        checkArgument(expectedRevision >= 0 && targetId != null && !targetId.isBlank(), "Target work item and revision are required");
    }

    private static UnifiedWorkItem.Source sourceOf(String id)
    {
        if (id.startsWith("ISSUE:")) {
            return UnifiedWorkItem.Source.GITHUB;
        }
        return id.startsWith("JIRA:") ? UnifiedWorkItem.Source.JIRA : UnifiedWorkItem.Source.LOCAL;
    }

    private static UnifiedWorkItem.Source type(GoalItemKind kind)
    {
        return kind == GoalItemKind.ISSUE ? UnifiedWorkItem.Source.GITHUB : UnifiedWorkItem.Source.JIRA;
    }
}
