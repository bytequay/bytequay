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
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Resolves the workspace's locally marked Epics: local work, GitHub issues and assigned Jira tickets.
 */
@Component
public class EpicSources
{
    private static final String ISSUE = "ISSUE:";
    private static final String JIRA = "JIRA:";
    private static final String LOCAL = "LOCAL:";

    private final IssueDevelopmentStore markers;
    private final WorkspaceStore workspaces;
    // JiraService needs work items, so it is looked up when an Epic is checked.
    private final ObjectProvider<JiraService> jira;
    private final WorkItemStore items;
    private final EpicStore epics;

    public EpicSources(IssueDevelopmentStore markers, WorkspaceStore workspaces, ObjectProvider<JiraService> jira, WorkItemStore items, EpicStore epics)
    {
        this.markers = requireNonNull(markers, "markers is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.items = requireNonNull(items, "items is null");
        this.epics = requireNonNull(epics, "epics is null");
    }

    public void require(String workspaceId, String epicId)
    {
        if (!marked(workspaceId, epicId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Epic not found. Mark the work item, issue or ticket as an Epic first.");
        }
    }

    public boolean marked(String workspaceId, String epicId)
    {
        Optional<String> local = localId(epicId);
        if (local.isPresent()) {
            return items.find(local.orElseThrow()).filter(item -> item.workspaceId().equals(workspaceId)).isPresent()
                    && epics.find(epicId).filter(epic -> epic.workspaceId().equals(workspaceId)).isPresent();
        }
        Optional<Ticket> ticket = ticket(epicId);
        if (ticket.isEmpty()) {
            long issueId = issueId(epicId);
            return workspaces.findById(workspaceId).filter(workspace -> epicId.startsWith(ISSUE + workspace.issueHost() + ":"))
                    .flatMap(workspace -> markers.findEpic(workspace.issueHost(), issueId).filter(IssueEpic::isEpic)
                            .filter(epic -> epic.repo().equalsIgnoreCase(workspace.monitoredRepository().fullName()))).isPresent();
        }
        JiraService tickets = jira.getObject();
        String cloudId = ticket.orElseThrow().cloudId();
        String issueId = ticket.orElseThrow().issueId();
        return tickets.isEpic(cloudId, issueId) && tickets.assignedWorkspace(cloudId, issueId).filter(workspaceId::equals).isPresent();
    }

    /** The stable GitHub issue id of an issue Epic. */
    public static long issueId(String epicId)
    {
        checkArgument(epicId.startsWith(ISSUE), "Expected an issue Epic identity");
        return Long.parseLong(epicId.substring(epicId.lastIndexOf(':') + 1));
    }

    public static String localEpicId(String itemId)
    {
        return LOCAL + itemId;
    }

    public static Optional<String> localId(String epicId)
    {
        requireNonNull(epicId, "epicId is null");
        return epicId.startsWith(LOCAL) ? Optional.of(epicId.substring(LOCAL.length())) : Optional.empty();
    }

    /** A Jira Epic's ticket; empty for an issue Epic. */
    public static Optional<Ticket> ticket(String epicId)
    {
        requireNonNull(epicId, "epicId is null");
        if (!epicId.startsWith(JIRA)) {
            return Optional.empty();
        }
        // The identity is JIRA:<length of the cloud id>:<cloud id>:<issue id>, so a cloud id may itself contain colons.
        String rest = epicId.substring(JIRA.length());
        int separator = rest.indexOf(':');
        checkArgument(separator > 0, "Invalid Jira Epic");
        int length = Integer.parseInt(rest.substring(0, separator));
        checkArgument(length > 0 && rest.length() > separator + length + 2 && rest.charAt(separator + 1 + length) == ':', "Invalid Jira Epic");
        return Optional.of(new Ticket(rest.substring(separator + 1, separator + 1 + length), rest.substring(separator + length + 2)));
    }

    public record Ticket(String cloudId, String issueId) {}
}
