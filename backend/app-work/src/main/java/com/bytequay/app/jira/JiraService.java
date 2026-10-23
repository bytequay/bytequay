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
package com.bytequay.app.jira;

import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workitem.WorkItemService;
import com.bytequay.app.workitem.WorkItemView;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraClient;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.bytequay.integration.jira.JiraIssueLocks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Application workflows and scheduling around the independently compiled Jira connector. */
@Service
public class JiraService
{
    private final JiraClient client;
    private final JiraIssueContextImpl context;
    private final JiraIssueTaskStore tasks;
    private final WorkspaceStore workspaces;
    private final ObjectProvider<AppSettingsService> appSettings;
    private final WorkItemStore workItems;
    // Work items depend on Jira tickets, so release an unmarked Epic's work only when needed.
    private final ObjectProvider<WorkItemService> work;

    public JiraService(JiraClient client, JiraIssueContextImpl context, JiraIssueTaskStore tasks, WorkspaceStore workspaces,
            ObjectProvider<AppSettingsService> appSettings, WorkItemStore workItems, ObjectProvider<WorkItemService> work)
    {
        this.client = requireNonNull(client, "client is null");
        this.context = requireNonNull(context, "context is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        // Agent initialization reads Jira context and depends on settings; resolve settings only for refresh.
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.work = requireNonNull(work, "work is null");
    }

    public boolean isEpic(String cloudId, String issueId)
    {
        return context.isEpic(cloudId, issueId);
    }

    public Optional<String> assignedWorkspace(String cloudId, String issueId)
    {
        return context.assignedWorkspace(cloudId, issueId);
    }

    public void recordRead(String connectionId, String cloudId, String issueId, int dwellSeconds)
    {
        context.recordRead(issue(connectionId, cloudId, issueId), dwellSeconds);
    }

    @Scheduled(fixedDelay = 600000, initialDelay = 60000)
    public void poll()
    {
        if (!appSettings.getObject().inQuietHours()) {
            client.refreshWatched();
        }
    }

    public JiraDtos.Detail epic(String connectionId, String cloudId, String issueId, boolean epic)
    {
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            JiraIssue issue = issue(connectionId, cloudId, issueId);
            if (epic && tasks.findBySource(cloudId, issueId).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket already has a development task.");
            }
            if (epic && issue.workspace().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Assign a ByteQuay workspace before marking this ticket as an Epic.");
            }
            if (!epic) {
                // Unmarking removes the Epic's view first: a failure then leaves the ticket marked, and retrying unmarks it again.
                issue.workspace().ifPresent(workspace -> work.getObject().releaseEpic(workspace.id(), WorkItemLink.jiraIdentity(cloudId, issueId)));
            }
            context.setEpic(cloudId, issueId, epic);
            return detail(connectionId, cloudId, issueId, false);
        });
    }

    public JiraDtos.Detail assignWorkspace(String connectionId, String cloudId, String issueId, String workspaceId)
    {
        checkArgument(workspaceId != null && !workspaceId.isBlank(), "Workspace is required");
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            JiraIssue issue = issue(connectionId, cloudId, issueId);
            WorkspaceException.requireWorkspace(workspaces, workspaceId);
            if (issue.task().filter(task -> !task.workspaceId().equals(workspaceId)).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket's development task belongs to another workspace.");
            }
            // A launch must match both this assignment and the work item's workspace; moving only one strands the ticket.
            if (workItems.findByIdentity(WorkItemLink.jiraIdentity(cloudId, issueId)).filter(item -> !item.workspaceId().equals(workspaceId)).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket's work item belongs to another workspace.");
            }
            context.assignWorkspace(cloudId, issueId, workspaceId);
            return detail(connectionId, cloudId, issueId, false);
        });
    }

    public WorkItemView createLocalChild(String connectionId, String cloudId, String issueId, String id, String title, String description)
    {
        JiraIssue parent = issue(connectionId, cloudId, issueId);
        String workspaceId = parent.workspace().orElseThrow(() -> new IllegalArgumentException("Assign a ByteQuay workspace before adding local work")).id();
        if (!parent.isEpic()) {
            epic(connectionId, cloudId, issueId, true);
        }
        return work.getObject().createInEpic(workspaceId, id, WorkItemLink.jiraIdentity(cloudId, issueId), title,
                WorkItem.Kind.DEVELOP, description, WorkItem.CompletionRule.MERGED_DELIVERY);
    }

    public JiraDtos.Detail refreshDetail(String connectionId, String cloudId, String issueId, boolean automatic)
    {
        return detail(connectionId, cloudId, issueId, !automatic || !appSettings.getObject().inQuietHours());
    }

    public List<JiraDtos.Board> boards(String connectionId, String cloudId, boolean refresh)
    {
        return client.boards(connectionId, cloudId, refresh);
    }

    public List<JiraDtos.Sprint> sprints(String connectionId, String cloudId, long boardId, boolean refresh)
    {
        return client.sprints(connectionId, cloudId, boardId, refresh);
    }

    public JiraDtos.IssuePage issues(String connectionId, String cloudId, long boardId, Optional<Long> sprintId, boolean mine, boolean coassigned, boolean completed,
            boolean refresh, Optional<String> nextPageToken)
    {
        return client.issues(connectionId, cloudId, boardId, sprintId, mine, coassigned, completed, refresh, nextPageToken);
    }

    public JiraIssue issue(String connectionId, String cloudId, String issueId)
    {
        return client.issue(connectionId, cloudId, issueId);
    }

    public Optional<JiraIssue> cachedIssue(String cloudId, String issueId)
    {
        return client.cachedIssue(cloudId, issueId);
    }

    public JiraIssue refreshIssue(String connectionId, String cloudId, String issueId)
    {
        return client.refreshIssue(connectionId, cloudId, issueId);
    }

    public JiraIssue refreshIssueByKey(String connectionId, String cloudId, String key)
    {
        return client.refreshIssueByKey(connectionId, cloudId, key);
    }

    public JiraDtos.Detail detail(String connectionId, String cloudId, String issueId, boolean refresh)
    {
        return client.detail(connectionId, cloudId, issueId, refresh);
    }

    public List<JiraDtos.Choice> publicationTypes(String connectionId, String cloudId, String project)
    {
        return client.publicationTypes(connectionId, cloudId, project);
    }

    public JiraIssueSource publishWorkItem(String connectionId, String cloudId, String project, String type, String title, String body)
    {
        return client.publishWorkItem(connectionId, cloudId, project, type, title, body);
    }

    public List<JiraDtos.LinkType> linkTypes(String connectionId, String cloudId)
    {
        return client.linkTypes(connectionId, cloudId);
    }

    public JiraDtos.Detail link(String connectionId, String cloudId, String issueId, String typeId, boolean outward, String key)
    {
        return client.link(connectionId, cloudId, issueId, typeId, outward, key);
    }

    public JiraDtos.Detail update(String connectionId, String cloudId, String issueId, Instant expected, Map<String, Object> fields)
    {
        return client.update(connectionId, cloudId, issueId, expected, fields);
    }

    public JiraDtos.Detail transition(String connectionId, String cloudId, String issueId, Instant expected, String transitionId, Map<String, Object> fields)
    {
        return client.transition(connectionId, cloudId, issueId, expected, transitionId, fields);
    }

    public JiraDtos.Detail move(String connectionId, String cloudId, String issueId, Instant expected, Optional<Long> sprintId)
    {
        return client.move(connectionId, cloudId, issueId, expected, sprintId);
    }

    public JiraDtos.Detail comment(String connectionId, String cloudId, String issueId, String body, String operationId, Optional<String> parentId)
    {
        return client.comment(connectionId, cloudId, issueId, body, operationId, parentId);
    }

    public JiraDtos.Detail editComment(String connectionId, String cloudId, String issueId, String commentId, String body, String expectedUpdated)
    {
        return client.editComment(connectionId, cloudId, issueId, commentId, body, expectedUpdated);
    }

    public List<JiraIssue> cachedIssues()
    {
        return client.cachedIssues();
    }

    public List<JiraIssue> attention()
    {
        return client.attention();
    }
}
