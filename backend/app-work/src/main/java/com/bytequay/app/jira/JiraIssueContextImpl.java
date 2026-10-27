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

import com.bytequay.app.service.recent.RecentItem;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.core.domain.RecentItemType;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.bytequay.integration.jira.JiraIssueContext;
import com.bytequay.integration.jira.impl.JiraCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;
import java.util.function.Consumer;

import static com.bytequay.integration.jira.JiraIdentifiers.segment;
import static java.util.Objects.requireNonNull;

/** Local associations are independent of remote connection and cache ownership. */
@Component
public class JiraIssueContextImpl
        implements JiraIssueContext
{
    private static final Logger log = LoggerFactory.getLogger(JiraIssueContextImpl.class);

    private final JiraCache cache;
    private final JiraIssueTaskStore tasks;
    private final WorkspaceStore workspaces;
    private final WorkItemStore workItems;
    private final RecentTouchRecorder recent;

    public JiraIssueContextImpl(JiraCache cache, JiraIssueTaskStore tasks, WorkspaceStore workspaces, WorkItemStore workItems, RecentTouchRecorder recent)
    {
        this.cache = requireNonNull(cache, "cache is null");
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
        return cache.read("goal." + segment(cloudId) + "." + segment(issueId), Boolean.class).orElse(false);
    }

    public Optional<String> assignedWorkspace(String cloudId, String issueId)
    {
        return tasks.findBySource(cloudId, issueId).map(link -> link.workspaceId())
                .or(() -> workItems.findByIdentity(WorkItemLink.jiraIdentity(cloudId, issueId)).map(WorkItem::workspaceId))
                .or(() -> cache.read("workspace." + segment(cloudId) + "." + segment(issueId), String.class));
    }

    public void setEpic(String cloudId, String issueId, boolean epic)
    {
        cache.write("goal." + segment(cloudId) + "." + segment(issueId), epic);
    }

    public void assignWorkspace(String cloudId, String issueId, String workspaceId)
    {
        cache.write("workspace." + segment(cloudId) + "." + segment(issueId), workspaceId);
    }

    public void recordRead(JiraIssue issue, int dwellSeconds)
    {
        recordRecent(issue, item -> recent.read(item, dwellSeconds));
    }

    @Override
    public void jiraAction(JiraIssue issue, RecentReason reason, int dwellSeconds)
    {
        recordRecent(issue, item -> recent.action(item, reason, dwellSeconds));
    }

    @Override
    public void wroteOnJira(JiraIssue issue, String commentId, String body)
    {
        recordRecent(issue, item -> recent.wrote(item, RecentTextKind.COMMENT, "comment:" + commentId, body));
    }

    private void recordRecent(JiraIssue issue, Consumer<RecentItem> record)
    {
        // Recent metadata must not fail a Jira action, even when a remote URL is malformed.
        try {
            record.accept(new RecentItem(RecentItemType.JIRA, "jira:" + issue.cloudId() + ":" + issue.id(), Optional.empty(), issue.title(), Optional.empty(),
                    issue.workspace().map(workspace -> workspace.id()), Optional.of(issue.url()), false, Optional.empty(),
                    Optional.of(issue.key() + " · " + URI.create(issue.url()).getHost())));
        }
        catch (RuntimeException failure) {
            log.debug("Jira recent touch not recorded: {}", failure.toString());
        }
    }
}
