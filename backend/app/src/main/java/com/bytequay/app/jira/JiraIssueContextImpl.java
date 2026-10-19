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

import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.bytequay.integration.jira.JiraIssueContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static com.bytequay.integration.jira.JiraIdentifiers.segment;
import static java.util.Objects.requireNonNull;

/** Local associations are independent of remote connection and cache ownership. */
@Component
public class JiraIssueContextImpl
        implements JiraIssueContext
{
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final JiraIssueTaskStore tasks;
    private final WorkspaceStore workspaces;
    private final WorkItemStore workItems;
    private final RecentTouchRecorder recent;

    public JiraIssueContextImpl(AppSettingsStore settings, ObjectMapper mapper, JiraIssueTaskStore tasks, WorkspaceStore workspaces,
            WorkItemStore workItems, RecentTouchRecorder recent)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.recent = requireNonNull(recent, "recent is null");
    }

    @Override
    public JiraIssue metadata(JiraIssue issue)
    {
        Optional<JiraDtos.TaskLink> task = tasks.findBySource(issue.cloudId(), issue.id()).map(link -> new JiraDtos.TaskLink(link.taskId(), link.workspaceId()));
        Optional<JiraDtos.WorkspaceLink> workspace = assignedWorkspace(issue.cloudId(), issue.id())
                .flatMap(workspaces::findById).map(value -> new JiraDtos.WorkspaceLink(value.id(), value.name()));
        return issue.withMetadata(isEpic(issue.cloudId(), issue.id()), task, workspace);
    }

    public boolean isEpic(String cloudId, String issueId)
    {
        // Keep the stored marker key so the terminology change preserves existing Epic selections.
        return read("goal." + segment(cloudId) + "." + segment(issueId), Boolean.class).orElse(false);
    }

    public Optional<String> assignedWorkspace(String cloudId, String issueId)
    {
        return tasks.findBySource(cloudId, issueId).map(link -> link.workspaceId())
                .or(() -> workItems.findByIdentity(WorkItemLink.jiraIdentity(cloudId, issueId)).map(WorkItem::workspaceId))
                .or(() -> read("workspace." + segment(cloudId) + "." + segment(issueId), String.class));
    }

    public void setEpic(String cloudId, String issueId, boolean epic)
    {
        write("goal." + segment(cloudId) + "." + segment(issueId), epic);
    }

    public void assignWorkspace(String cloudId, String issueId, String workspaceId)
    {
        write("workspace." + segment(cloudId) + "." + segment(issueId), workspaceId);
    }

    public void recordRead(JiraIssue issue, int dwellSeconds)
    {
        recent.jiraRead(issue, dwellSeconds);
    }

    @Override
    public void jiraAction(JiraIssue issue, RecentReason reason, int dwellSeconds)
    {
        recent.jiraAction(issue, reason, dwellSeconds);
    }

    @Override
    public void wroteOnJira(JiraIssue issue, String commentId, String body)
    {
        recent.wroteOnJira(issue, commentId, body);
    }

    private <T> Optional<T> read(String key, Class<T> type)
    {
        return settings.get("jira." + key).map(value -> {
            try {
                return mapper.readValue(value, type);
            }
            catch (JsonProcessingException e) {
                throw new IllegalStateException("Unable to read saved Jira data", e);
            }
        });
    }

    private void write(String key, Object value)
    {
        try {
            settings.put("jira." + key, mapper.writeValueAsString(value));
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to encode Jira data", e);
        }
    }
}
