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
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraIssue;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Only the desktop HTTP action calls this publisher; agent capabilities have no reference to it. */
@Service
public class WorkItemPublicationService
{
    private final WorkItemService work;
    private final WorkspaceStore workspaces;
    private final GitHubRepoClient github;
    private final CredentialService credentials;
    private final JiraService jira;
    private final Clock clock;

    public WorkItemPublicationService(WorkItemService work, WorkspaceStore workspaces, GitHubRepoClient github, CredentialService credentials, JiraService jira, Clock clock)
    {
        this.work = requireNonNull(work, "work is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.github = requireNonNull(github, "github is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public WorkItemView publish(String workspaceId, String id, Publication request)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        WorkItem item = work.requireItem(workspaceId, id);
        checkArgument(request.source() == UnifiedWorkItem.Source.GITHUB || request.source() == UnifiedWorkItem.Source.JIRA, "Choose GitHub or Jira");
        checkArgument(item.links().stream().noneMatch(link -> request.source() == UnifiedWorkItem.Source.GITHUB ? link.issueSource().isPresent()
                : link.jiraSource().isPresent()), "This work already has a source on that provider");
        checkArgument(item.title().length() <= (request.source() == UnifiedWorkItem.Source.GITHUB ? 256 : 255), "Shorten the title before publishing");
        Optional<String> token = request.source() == UnifiedWorkItem.Source.GITHUB ? Optional.of(credentials.resolveGitHubToken(workspaceId)) : Optional.empty();
        if (request.source() == UnifiedWorkItem.Source.JIRA) {
            checkArgument(jira.publicationTypes(request.connectionId().orElseThrow(), request.cloudId().orElseThrow(), request.project().orElseThrow()).stream()
                    .anyMatch(type -> type.id().equals(request.issueTypeId().orElseThrow())), "Choose an available Jira work type");
        }
        String operation = "publication:" + UUID.randomUUID();
        // Use the accepted revision's content, never an edited draft supplied separately by the caller.
        item = work.beginPublication(workspaceId, id, request.expectedRevision(), operation, destination(workspace, request));
        WorkItemLink source;
        try {
            source = request.source() == UnifiedWorkItem.Source.GITHUB
                    ? GoalReferenceReader.issueLink(github.publishWorkItem(token.orElseThrow(), workspace.monitoredRepository(),
                            item.title(), item.acceptanceCriteria()), clock.instant())
                    : GoalReferenceReader.jiraLink(jira.publishWorkItem(request.connectionId().orElseThrow(), request.cloudId().orElseThrow(), request.project().orElseThrow(),
                            request.issueTypeId().orElseThrow(), item.title(), item.acceptanceCriteria()), clock.instant());
        }
        catch (ResponseStatusException failure) {
            int status = failure.getStatusCode().value();
            if (status == 400 || status == 401 || status == 403 || status == 404 || status == 422 || status == 429) {
                work.publicationRejected(workspaceId, id, operation);
            }
            throw failure;
        }
        return work.published(workspaceId, id, source, operation);
    }

    /** A user reconciles an uncertain outcome by naming the actual remote request. Never retries a remote create. */
    public WorkItemView reconcile(String workspaceId, String id, Publication request, String key)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        WorkItem item = work.requireItem(workspaceId, id);
        checkArgument(item.revision() == request.expectedRevision(), "Work changed; refresh before linking");
        Optional<WorkItemEvent> pending = item.history().stream().filter(event -> event.action().equals("PUBLISH_STARTED")
                && item.history().stream().noneMatch(done -> done.operationId().equals(event.operationId() + ":settled"))).findFirst();
        pending.ifPresent(event -> checkArgument(event.detail().equals(destination(workspace, request)),
                "Choose the same destination as the pending publication: " + event.detail()));
        WorkItemLink source;
        if (request.source() == UnifiedWorkItem.Source.GITHUB) {
            source = GoalReferenceReader.issueLink(github.fetchIssueSource(credentials.resolveGitHubToken(workspaceId), workspace.monitoredRepository(),
                    Integer.parseInt(key.replace("#", ""))), clock.instant());
        }
        else {
            checkArgument(request.source() == UnifiedWorkItem.Source.JIRA, "Choose GitHub or Jira");
            JiraIssue issue = jira.refreshIssueByKey(request.connectionId().orElseThrow(), request.cloudId().orElseThrow(), key);
            pending.ifPresent(event -> checkArgument(issue.key().startsWith(request.project().orElseThrow().toUpperCase(Locale.ROOT) + "-"),
                    "The ticket must belong to the project of the pending publication"));
            source = GoalReferenceReader.jiraLink(new JiraIssueSource(issue.connectionId(), issue.cloudId(), issue.id(), issue.key(), issue.url(),
                    issue.title(), issue.body(), issue.updatedAt()), clock.instant());
        }
        String operation = pending.map(WorkItemEvent::operationId).orElse("link:" + UUID.randomUUID());
        return work.published(workspaceId, id, source, operation);
    }

    private static String destination(Workspace workspace, Publication request)
    {
        return request.source() == UnifiedWorkItem.Source.GITHUB ? "GITHUB " + workspace.monitoredRepository().fullName().toLowerCase(Locale.ROOT)
                : "JIRA " + request.connectionId().orElseThrow() + "/" + request.cloudId().orElseThrow() + "/" + request.project().orElseThrow().toUpperCase(Locale.ROOT);
    }

    public record Publication(UnifiedWorkItem.Source source, long expectedRevision, Optional<String> connectionId, Optional<String> cloudId,
            Optional<String> project, Optional<String> issueTypeId) {}
}
