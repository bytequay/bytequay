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
package com.bytequay.app.service.epic;

import com.bytequay.app.jira.JiraService;
import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Tells whether a work item's Epic identity, the canonical identity of an issue or ticket, names one marked as an Epic in the workspace:
 * a GitHub issue of its monitored repository, or a Jira ticket assigned to it.
 */
@Component
public class EpicSources
{
    private static final String HOST = "github.com";
    private static final String ISSUE = "ISSUE:" + HOST + ":";
    private static final String JIRA = "JIRA:";

    private final IssueDevelopmentStore markers;
    private final WorkspaceStore workspaces;
    // JiraService needs work items, so it is looked up when an Epic is checked.
    private final ObjectProvider<JiraService> jira;

    public EpicSources(IssueDevelopmentStore markers, WorkspaceStore workspaces, ObjectProvider<JiraService> jira)
    {
        this.markers = requireNonNull(markers, "markers is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.jira = requireNonNull(jira, "jira is null");
    }

    public void require(String workspaceId, String epicId)
    {
        if (!marked(workspaceId, epicId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Epic not found. Mark the issue or ticket as an Epic first.");
        }
    }

    public boolean marked(String workspaceId, String epicId)
    {
        requireNonNull(epicId, "epicId is null");
        if (epicId.startsWith(ISSUE)) {
            long issueId = Long.parseLong(epicId.substring(ISSUE.length()));
            String repository = workspaces.findById(workspaceId).map(workspace -> workspace.monitoredRepository().fullName().toLowerCase(Locale.ROOT)).orElse("");
            return markers.findEpic(HOST, issueId).filter(IssueEpic::isEpic).filter(epic -> epic.repo().equals(repository)).isPresent();
        }
        checkArgument(epicId.startsWith(JIRA), "Epic must be a GitHub issue or a Jira ticket");
        // The identity is JIRA:<length of the cloud id>:<cloud id>:<issue id>, so a cloud id may itself contain colons.
        String rest = epicId.substring(JIRA.length());
        int separator = rest.indexOf(':');
        checkArgument(separator > 0, "Invalid Jira Epic");
        int length = Integer.parseInt(rest.substring(0, separator));
        checkArgument(length > 0 && rest.length() > separator + length + 2 && rest.charAt(separator + 1 + length) == ':', "Invalid Jira Epic");
        String cloudId = rest.substring(separator + 1, separator + 1 + length);
        String issueId = rest.substring(separator + length + 2);
        JiraService tickets = jira.getObject();
        return tickets.isEpic(cloudId, issueId) && tickets.assignedWorkspace(cloudId, issueId).filter(workspaceId::equals).isPresent();
    }
}
