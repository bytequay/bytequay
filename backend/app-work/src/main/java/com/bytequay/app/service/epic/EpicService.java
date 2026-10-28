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

import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workitem.WorkItemService;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.EpicChild;
import com.bytequay.core.domain.EpicProposal;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * An Epic's children: reads the sub-issues or child tickets its source reports, and on the user's Sync makes each one work of the Epic.
 * Showing them is a local read; only a refresh reads GitHub or Jira, and nothing here writes to either.
 */
@Service
public class EpicService
{
    private static final String HOST = "github.com";
    // The most a Local work item's context holds, as its own form allows.
    private static final int MAX_CONTEXT = 65_536;

    private final EpicStore epics;
    private final EpicSources sources;
    private final WorkItemService work;
    private final IssueDevelopmentStore markers;
    private final GitHubRepoClient github;
    private final CredentialService credentials;
    // JiraService needs work items, so it is looked up when a Jira Epic's children are read.
    private final ObjectProvider<JiraService> jira;
    private final AgentUnitOfWork unitOfWork;
    private final RepositoryIssueStore issues;
    private final WorkspaceStore workspaces;
    private final EpicBreakdowns breakdowns;
    private final Clock clock;

    public EpicService(EpicStore epics, EpicSources sources, WorkItemService work, IssueDevelopmentStore markers, GitHubRepoClient github, CredentialService credentials,
            ObjectProvider<JiraService> jira, AgentUnitOfWork unitOfWork, RepositoryIssueStore issues, WorkspaceStore workspaces, EpicBreakdowns breakdowns, Clock clock)
    {
        this.epics = requireNonNull(epics, "epics is null");
        this.sources = requireNonNull(sources, "sources is null");
        this.work = requireNonNull(work, "work is null");
        this.markers = requireNonNull(markers, "markers is null");
        this.github = requireNonNull(github, "github is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.breakdowns = requireNonNull(breakdowns, "breakdowns is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public EpicView view(String workspaceId, String epicId)
    {
        sources.require(workspaceId, epicId);
        return view(current(workspaceId, epicId));
    }

    /** The local work item's identity remains the Epic's identity even if its context is later published. */
    public void setLocalEpic(String workspaceId, String itemId, boolean isEpic)
    {
        unitOfWork.execute(() -> {
            WorkItem item = work.requireItem(workspaceId, itemId);
            String epicId = EpicSources.localEpicId(itemId);
            if (isEpic) {
                checkArgument(item.epicId().isEmpty(), "Remove this work item from its Epic before marking it as an Epic");
                epics.save(current(workspaceId, epicId));
            }
            else {
                work.releaseEpic(workspaceId, epicId);
            }
            return null;
        });
    }

    /** New Local work that is an Epic from the start, in one step, so a failed mark never leaves a plain item behind. */
    public void createLocalEpic(String workspaceId, String itemId, String title, String description, String context)
    {
        String body = Stream.of(description.strip(), context.isBlank() ? "" : "### Context\n\n" + context.strip())
                .filter(part -> !part.isEmpty())
                .collect(joining("\n\n"));
        checkArgument(body.length() <= MAX_CONTEXT, "The description and context exceed %s characters", MAX_CONTEXT);
        unitOfWork.execute(() -> {
            work.create(workspaceId, itemId, title, WorkItem.Kind.DEVELOP, body, WorkItem.CompletionRule.MERGED_DELIVERY);
            setLocalEpic(workspaceId, itemId, true);
            return null;
        });
    }

    /** Reads the children the Epic's source reports now; a Jira Epic is read through the connection that shows it, and quiet hours keep the last read. */
    public EpicView refreshChildren(String workspaceId, String epicId, Optional<String> connectionId)
    {
        sources.require(workspaceId, epicId);
        if (EpicSources.localId(epicId).isPresent()) {
            return view(current(workspaceId, epicId));
        }
        Read read = EpicSources.ticket(epicId)
                .map(ticket -> childTickets(ticket, connectionId.orElseThrow(() -> new IllegalArgumentException("A Jira Epic's connection is required"))))
                .orElseGet(() -> subIssues(workspaceId, epicId));
        return view(unitOfWork.execute(() -> {
            Epic next = current(workspaceId, epicId).withChildren(read.children(), clock.instant(), read.truncated());
            epics.save(next);
            return next;
        }));
    }

    /** Makes every unsynced child work of the Epic: the work that already owns a child joins, or new work takes its title. */
    public EpicView sync(String workspaceId, String epicId)
    {
        sources.require(workspaceId, epicId);
        return view(unitOfWork.execute(() -> {
            Epic epic = current(workspaceId, epicId);
            List<EpicChild> unsynced = epic.unsynced();
            Instant now = clock.instant();
            for (EpicChild child : unsynced) {
                work.joinEpic(workspaceId, epicId, new WorkItemLink(child.identity(), child.reference(now)));
            }
            Epic next = epic.withSynced(unsynced);
            epics.save(next);
            return next;
        }));
    }

    /** Not now: hides the children unsynced now; another child the source reports later offers syncing again. */
    public EpicView dismiss(String workspaceId, String epicId)
    {
        sources.require(workspaceId, epicId);
        return view(unitOfWork.execute(() -> {
            Epic next = current(workspaceId, epicId).withDismissed();
            epics.save(next);
            return next;
        }));
    }

    /** Records the outcome the user accepted once all the Epic's work is done; concluding it again changes nothing. */
    public EpicView conclude(String workspaceId, String epicId, String outcome)
    {
        sources.require(workspaceId, epicId);
        return view(unitOfWork.execute(() -> {
            Epic epic = current(workspaceId, epicId);
            if (epic.concludedAt().isPresent()) {
                return epic;
            }
            work.requireEpicDone(workspaceId, epicId);
            Epic next = epic.withConcluded(outcome, clock.instant());
            epics.save(next);
            return next;
        }));
    }

    /** Asks the development model to propose work from the Epic's context; the proposals wait in its view. */
    public EpicView propose(String workspaceId, String epicId, Optional<String> connectionId)
    {
        sources.require(workspaceId, epicId);
        breakdowns.start(workspaceId, epicId, source(workspaceId, epicId, connectionId));
        return view(current(workspaceId, epicId));
    }

    /** Makes a proposal the Epic's work, under the proposal's id, so accepting it again changes nothing. */
    public EpicView acceptProposal(String workspaceId, String epicId, String proposalId)
    {
        sources.require(workspaceId, epicId);
        return view(unitOfWork.execute(() -> {
            Epic epic = current(workspaceId, epicId);
            Optional<EpicProposal> proposal = epic.proposals().stream().filter(candidate -> candidate.id().equals(proposalId)).findFirst();
            if (proposal.isEmpty()) {
                return epic;
            }
            EpicProposal accepted = proposal.orElseThrow();
            work.createInEpic(workspaceId, accepted.id(), epicId, accepted.title(), WorkItem.Kind.DEVELOP, accepted.context(), WorkItem.CompletionRule.MERGED_DELIVERY);
            Epic next = epic.withoutProposal(proposalId);
            epics.save(next);
            return next;
        }));
    }

    public EpicView dismissProposal(String workspaceId, String epicId, String proposalId)
    {
        sources.require(workspaceId, epicId);
        return view(unitOfWork.execute(() -> {
            Epic next = current(workspaceId, epicId).withoutProposal(proposalId);
            epics.save(next);
            return next;
        }));
    }

    /** The workspace's concluded Epics, which the drawer leaves out; a local read. */
    public List<String> concluded(String workspaceId)
    {
        return epics.list(workspaceId).stream().filter(epic -> epic.concludedAt().isPresent()).map(Epic::id).collect(toImmutableList());
    }

    private Epic current(String workspaceId, String epicId)
    {
        return epics.find(epicId).orElseGet(() -> Epic.of(epicId, workspaceId));
    }

    private EpicView view(Epic epic)
    {
        return new EpicView(epic.id(), epic.children(), epic.synced().stream().map(EpicChild::identity).collect(toImmutableList()), epic.former(),
                epic.childrenReadAt(), epic.childrenTruncated(), epic.offered(), epic.concludedAt(), epic.outcome(), epic.proposals(), breakdowns.running(epic.id()),
                breakdowns.failure(epic.id()), breakdowns.note(epic.id()));
    }

    /** The Epic's context, read from local work or a cached remote source. */
    private EpicBreakdowns.Source source(String workspaceId, String epicId, Optional<String> connectionId)
    {
        Optional<String> local = EpicSources.localId(epicId);
        if (local.isPresent()) {
            WorkItem item = work.requireItem(workspaceId, local.orElseThrow());
            return new EpicBreakdowns.Source("Local", item.title(), item.acceptanceCriteria());
        }
        Optional<EpicSources.Ticket> ticket = EpicSources.ticket(epicId);
        if (ticket.isPresent()) {
            JiraIssue issue = jira.getObject().issue(connectionId.orElseThrow(() -> new IllegalArgumentException("A Jira Epic's connection is required")),
                    ticket.orElseThrow().cloudId(), ticket.orElseThrow().issueId());
            return new EpicBreakdowns.Source(issue.key(), issue.title(), issue.body());
        }
        var workspace = workspaces.findById(workspaceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
        IssueEpic marker = markers.findEpic(workspace.issueHost(), EpicSources.issueId(epicId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Epic not found"));
        RepositoryIssue issue = issues.find(workspaces, workspace, marker.number())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The Epic's issue is not available locally. Refresh issues first."));
        return new EpicBreakdowns.Source("#" + issue.number(), issue.title(), issue.body());
    }

    private Read subIssues(String workspaceId, String epicId)
    {
        var workspace = workspaces.findById(workspaceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));
        if (!workspace.isGitHub()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Fetching GitLab issue children is not supported yet");
        }
        long issueId = EpicSources.issueId(epicId);
        IssueEpic marker = markers.findEpic(HOST, issueId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Epic not found"));
        RepositoryIdentity identity = RepositoryIdentity.valueOf(marker.repo());
        RepoRef repository = RepoRef.of(identity.owner(), identity.name());
        List<EpicChild> children = github.fetchSubIssues(credentials.resolveGitHubToken(), repository, marker.number()).stream()
                .map(issue -> {
                    String repo = issue.repository().fullName();
                    // A sub-issue of another repository names its repository, as GitHub does.
                    String label = repo.equalsIgnoreCase(marker.repo()) ? "#" + issue.number() : repo + "#" + issue.number();
                    return new EpicChild(WorkItemLink.issueIdentity(HOST, issue.id()), GoalItemKind.ISSUE, GoalItem.numberedKey(repo, issue.number()), label,
                            issue.title(), issue.closed() ? "Closed" : "Open", issue.closed(), issue.url());
                })
                .collect(toImmutableList());
        return new Read(children, false);
    }

    private Read childTickets(EpicSources.Ticket ticket, String connectionId)
    {
        JiraDtos.Detail detail = jira.getObject().refreshDetail(connectionId, ticket.cloudId(), ticket.issueId(), false);
        List<EpicChild> children = detail.children().stream()
                .map(child -> new EpicChild(WorkItemLink.jiraIdentity(ticket.cloudId(), child.id()), GoalItemKind.JIRA,
                        GoalItem.jiraKey(connectionId, ticket.cloudId(), child.id()), child.key(), child.title(), child.status().name(),
                        "done".equals(child.status().category()), Optional.empty()))
                .collect(toImmutableList());
        return new Read(children, detail.childrenTruncated());
    }

    private record Read(List<EpicChild> children, boolean truncated) {}
}
