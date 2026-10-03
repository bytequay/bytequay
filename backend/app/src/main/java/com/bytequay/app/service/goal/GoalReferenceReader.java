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
package com.bytequay.app.service.goal;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.jira.JiraIssue;
import com.bytequay.app.jira.JiraService;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

@Service
public class GoalReferenceReader
{
    private static final String HOST = "github.com";
    private static final int CANDIDATES_PER_KIND = 8;
    private static final int ISSUE_SCAN = 500;
    private static final int TASK_SCAN = 100;

    private final WorkspaceStore workspaces;
    private final PullRequestStore pullRequests;
    private final RepositoryIssueStore issues;
    private final IssueDevelopmentStore development;
    // Lazy: marking a Jira ticket as a Goal seeds its goal through this service.
    private final ObjectProvider<JiraService> jira;
    private final AgentTaskService tasks;
    private final Clock clock;
    private final JiraIssueTaskStore jiraTasks;
    private final PullRequestTaskStore assignments;
    private final TaskPullRequestOwnership pullOwnership;

    public GoalReferenceReader(WorkspaceStore workspaces, PullRequestStore pullRequests, RepositoryIssueStore issues,
            IssueDevelopmentStore development, ObjectProvider<JiraService> jira, AgentTaskService tasks, Clock clock,
            JiraIssueTaskStore jiraTasks, PullRequestTaskStore assignments, TaskPullRequestOwnership pullOwnership)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.development = requireNonNull(development, "development is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.jiraTasks = requireNonNull(jiraTasks, "jiraTasks is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.pullOwnership = requireNonNull(pullOwnership, "pullOwnership is null");
    }

    public List<GoalItemView> candidates(String workspaceId, String query)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Resolver resolver = new Resolver(workspace);
        String term = query.trim().toLowerCase(Locale.ROOT);
        Stream<GoalItemView> pulls = pullRequests.findRepositoryPage(workspace.monitoredRepository().fullName(), 0, CANDIDATES_PER_KIND, term)
                .pullRequests().stream().map(resolver::pullRequestView);
        Stream<GoalItemView> repositoryIssues = issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), 0, ISSUE_SCAN).issues().stream()
                .filter(issue -> matches(term, "#" + issue.number(), issue.title())).limit(CANDIDATES_PER_KIND).map(resolver::issueView);
        Stream<GoalItemView> tickets = jira.getObject().cachedIssues().stream()
                .filter(issue -> issue.workspace().filter(assigned -> assigned.id().equals(workspaceId)).isPresent())
                .filter(issue -> matches(term, issue.key(), issue.title())).limit(CANDIDATES_PER_KIND).map(resolver::jiraView);
        Stream<GoalItemView> workspaceTasks = tasks.list(workspaceId, TASK_SCAN).stream()
                .filter(task -> matches(term, "", task.title())).limit(CANDIDATES_PER_KIND).map(resolver::taskView);
        return Stream.of(pulls, repositoryIssues, tickets, workspaceTasks).flatMap(stream -> stream).collect(toImmutableList());
    }

    public GoalItemView view(String workspaceId, GoalItem item)
    {
        Resolver resolver = new Resolver(requireWorkspace(workspaces, workspaceId));
        return resolver.find(item.kind(), item.key()).orElseGet(() -> resolver.missing(item)).withAddedAt(item.addedAt());
    }

    public WorkItemLink requireLink(String workspaceId, GoalItemKind kind, String key)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        String canonical = canonical(kind, key);
        GoalItemView view = new Resolver(workspace).find(kind, canonical)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ByteQuay has not stored this item. Refresh it first."));
        String identity = switch (kind) {
            case ISSUE -> {
                NumberedKey ref = NumberedKey.parse(canonical);
                checkArgument(ref.repo().equalsIgnoreCase(workspace.monitoredRepository().fullName()), "Issue belongs to another workspace");
                yield WorkItemLink.issueIdentity(HOST, issues.find(workspace.monitoredRepositoryId(), ref.number()).orElseThrow().id());
            }
            case JIRA -> {
                String[] parts = canonical.split(":", 3);
                JiraIssue issue = jira.getObject().issue(parts[0], parts[1], parts[2]);
                checkArgument(issue.workspace().filter(assigned -> assigned.id().equals(workspaceId)).isPresent(), "Ticket belongs to another workspace");
                yield WorkItemLink.jiraIdentity(issue.cloudId(), issue.id());
            }
            case TASK -> WorkItemLink.taskIdentity(canonical);
            case PULL_REQUEST -> {
                checkArgument(NumberedKey.parse(canonical).repo().equalsIgnoreCase(workspace.monitoredRepository().fullName()), "PR belongs to another workspace");
                yield "PULL_REQUEST:github.com:" + canonical;
            }
        };
        if (kind == GoalItemKind.ISSUE) {
            RepositoryIssue issue = issues.find(workspace.monitoredRepositoryId(), NumberedKey.parse(canonical).number()).orElseThrow();
            return issueLink(new GitHubIssueSource(HOST, issue.id(), workspace.monitoredRepository().fullName(), issue.number(), issue.htmlUrl(),

                    issue.title(), issue.body(), issue.updatedAt()), clock.instant());
        }
        if (kind == GoalItemKind.JIRA) {
            String[] parts = canonical.split(":", 3);
            JiraIssue issue = jira.getObject().issue(parts[0], parts[1], parts[2]);
            return jiraLink(new JiraIssueSource(issue.connectionId(), issue.cloudId(), issue.id(), issue.key(), issue.url(),
                    issue.title(), issue.body(), issue.updatedAt()), clock.instant());
        }
        return new WorkItemLink(identity, new GoalItem(kind, view.key(), view.label(), view.title(), clock.instant()));
    }

    /** Only durable source bindings and existing task/PR ownership connect records; never similar titles. */
    public List<WorkItemLink> related(String workspaceId, WorkItemLink link)
    {
        ImmutableList.Builder<WorkItemLink> result = ImmutableList.builder();
        result.add(link);
        Optional<String> taskId = switch (link.reference().kind()) {
            case TASK -> Optional.of(link.reference().key());
            case ISSUE -> development.findTask(HOST, Long.parseLong(link.identity().substring(link.identity().lastIndexOf(':') + 1)))
                    .map(value -> scopedTask(workspaceId, value.workspaceId(), value.taskId()));
            case JIRA -> {
                String[] parts = link.reference().key().split(":", 3);
                yield jiraTasks.findBySource(parts[1], parts[2]).map(value -> scopedTask(workspaceId, value.workspaceId(), value.taskId()));
            }
            case PULL_REQUEST -> {
                NumberedKey ref = NumberedKey.parse(link.reference().key());
                yield assignments.find(workspaceId, ref.repo(), ref.number()).map(value -> scopedTask(workspaceId, value.workspaceId(), value.taskId()));
            }
        };
        taskId.ifPresent(id -> {
            development.findByTaskId(id).ifPresent(value -> result.add(issueLink(value.source(), value.createdAt())));
            jiraTasks.findByTaskId(id).ifPresent(value -> result.add(jiraLink(value.source(), value.createdAt())));
            try {
                AgentTask task = tasks.getInteractive(workspaceId, TaskId.valueOf(id));
                result.add(taskLink(task));
                pullOwnership.linkedPullRequest(task).ifPresent(pull -> result.add(requireLink(workspaceId, GoalItemKind.PULL_REQUEST,
                        GoalItem.numberedKey(pull.repo(), pull.number()))));
            }
            catch (AgentTaskNotFoundException e) {
                // The durable link still identifies an unavailable task after retention.
                result.add(new WorkItemLink(WorkItemLink.taskIdentity(id), new GoalItem(GoalItemKind.TASK, id, "Task", "Previous attempt", clock.instant())));
            }
        });
        return result.build().stream().collect(toMap(WorkItemLink::identity, value -> value, (first, second) -> first,
                LinkedHashMap::new)).values().stream().collect(toImmutableList());
    }

    private static String scopedTask(String workspaceId, String ownerWorkspaceId, String taskId)
    {
        if (!workspaceId.equals(ownerWorkspaceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source already has task " + taskId + " in workspace " + ownerWorkspaceId + ". Open that existing work.");
        }
        return taskId;
    }

    public static WorkItemLink taskLink(AgentTask task)
    {
        return new WorkItemLink(WorkItemLink.taskIdentity(task.id().toString()),
                new GoalItem(GoalItemKind.TASK, task.id().toString(), "Task", task.title(), task.createdAt()));
    }

    public static WorkItemLink issueLink(GitHubIssueSource source, Instant at)
    {
        return new WorkItemLink(WorkItemLink.issueIdentity(source.host(), source.issueId()),
                new GoalItem(GoalItemKind.ISSUE, GoalItem.numberedKey(source.repo(), source.number()), "#" + source.number(), source.title(), at),
                Optional.of(source), Optional.empty());
    }

    public static WorkItemLink jiraLink(JiraIssueSource source, Instant at)
    {
        return new WorkItemLink(WorkItemLink.jiraIdentity(source.cloudId(), source.issueId()),
                new GoalItem(GoalItemKind.JIRA, GoalItem.jiraKey(source.connectionId(), source.cloudId(), source.issueId()), source.key(), source.title(), at),
                Optional.empty(), Optional.of(source));
    }

    private static String text(String value, String name)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), "%s is blank", name);
        return value.trim();
    }

    private static boolean matches(String term, String label, String title)
    {
        return term.isEmpty() || label.toLowerCase(Locale.ROOT).contains(term) || title.toLowerCase(Locale.ROOT).contains(term);
    }

    private static String canonical(GoalItemKind kind, String key)
    {
        return switch (kind) {
            case PULL_REQUEST, ISSUE -> NumberedKey.parse(key).key();
            case JIRA, TASK -> text(key, "key");
        };
    }

    /** {@code owner/repo#number}, the key of a pull request or issue item. */
    private record NumberedKey(String repo, int number)
    {
        static NumberedKey parse(String key)
        {
            int hash = key.lastIndexOf('#');
            checkArgument(hash > 0 && key.indexOf('/') > 0, "Invalid item key: %s", key);
            try {
                return new NumberedKey(key.substring(0, hash), Integer.parseInt(key.substring(hash + 1)));
            }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid item key: " + key, e);
            }
        }

        String key()
        {
            return GoalItem.numberedKey(repo, number);
        }
    }

    /** Reads items of one workspace from local stores; never from GitHub or Jira. */
    private final class Resolver
    {
        private final Workspace workspace;

        Resolver(Workspace workspace)
        {
            this.workspace = workspace;
        }

        Optional<GoalItemView> find(GoalItemKind kind, String key)
        {
            return switch (kind) {
                case PULL_REQUEST -> pullRequest(key);
                case ISSUE -> issue(key);
                case JIRA -> jiraTicket(key);
                case TASK -> task(key);
            };
        }

        private GoalItemView missing(GoalItem item)
        {
            return new GoalItemView(item.kind(), item.key(), item.label(), item.title(), GoalItemState.UNKNOWN, "Evidence unavailable", false,
                    Optional.empty(), Optional.empty(), Optional.of(item.addedAt()));
        }

        private Optional<GoalItemView> pullRequest(String key)
        {
            NumberedKey ref = NumberedKey.parse(key);
            return pullRequests.findIdByRepoAndNumber(ref.repo(), ref.number()).flatMap(pullRequests::findById).map(this::pullRequestView);
        }

        GoalItemView pullRequestView(PullRequest pull)
        {
            boolean merged = "merged".equals(pull.state()) || pull.mergedAt() != null;
            boolean closed = !merged && "closed".equals(pull.state());
            String status = merged ? "Merged" : closed ? "Closed" : pull.draft() ? "Draft" : "Open";
            Instant at = merged && pull.mergedAt() != null ? pull.mergedAt()
                    : closed && pull.closedAt() != null ? pull.closedAt() : Optional.ofNullable(pull.activityAt()).orElse(pull.updatedAt());
            String label = "#" + pull.number();
            return new GoalItemView(GoalItemKind.PULL_REQUEST, GoalItem.numberedKey(pull.repo(), pull.number()), label, pull.title(),
                    merged ? GoalItemState.MERGED : closed ? GoalItemState.ENDED : GoalItemState.WORKING, status, false, Optional.of(pull.htmlUrl()),
                    Optional.of(new GoalActivity(label + (merged ? " merged" : closed ? " closed" : " updated"), at)), Optional.empty());
        }

        private Optional<GoalItemView> issue(String key)
        {
            NumberedKey ref = NumberedKey.parse(key);
            if (!ownRepository(ref.repo())) {
                return Optional.empty();
            }
            return issues.find(workspace.monitoredRepositoryId(), ref.number()).map(this::issueView);
        }

        GoalItemView issueView(RepositoryIssue issue)
        {
            boolean developing = development.findTask(HOST, issue.id()).isPresent();
            String label = "#" + issue.number();
            return new GoalItemView(GoalItemKind.ISSUE, GoalItem.numberedKey(workspace.monitoredRepository().fullName(), issue.number()), label, issue.title(),
                    developing ? GoalItemState.WORKING : GoalItemState.IDLE, "Open", false, Optional.of(issue.htmlUrl()),
                    Optional.of(new GoalActivity(label + " updated", issue.updatedAt())), Optional.empty());
        }

        private Optional<GoalItemView> jiraTicket(String key)
        {
            String[] parts = key.split(":", 3);
            if (parts.length != 3) {
                return Optional.empty();
            }
            try {
                return Optional.of(jiraView(jira.getObject().issue(parts[0], parts[1], parts[2])));
            }
            catch (ResponseStatusException e) {
                // Not cached: the ticket reads as saved until Jira is refreshed.
                return Optional.empty();
            }
        }

        GoalItemView jiraView(JiraIssue issue)
        {
            String category = issue.status().category();
            GoalItemState state = "done".equals(category) ? GoalItemState.ENDED : "indeterminate".equals(category) ? GoalItemState.WORKING : GoalItemState.IDLE;
            String status = issue.status().name();
            return new GoalItemView(GoalItemKind.JIRA, GoalItem.jiraKey(issue.connectionId(), issue.cloudId(), issue.id()), issue.key(), issue.title(), state, status,
                    status.toLowerCase(Locale.ROOT).contains("blocked"), Optional.ofNullable(issue.url()),
                    Optional.of(new GoalActivity(issue.key() + " is " + status, issue.updatedAt())), Optional.empty());
        }

        private Optional<GoalItemView> task(String key)
        {
            try {
                return Optional.of(taskView(tasks.getInteractive(workspace.id(), TaskId.valueOf(key))));
            }
            catch (AgentTaskNotFoundException | IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        GoalItemView taskView(AgentTask task)
        {
            boolean running = task.developmentContinuationUnfinished()
                    || task.worktreeMutationClaim().filter(claim -> claim.kind() != WorktreeMutationClaim.Kind.RECOVERY).isPresent();
            String status = switch (task.state()) {
                case COMPLETED -> "Completed";
                case FAILED -> "Failed";
                case OPEN -> running ? "Running" : "Idle";
            };
            GoalItemState state = task.state().terminal() ? GoalItemState.ENDED : running ? GoalItemState.WORKING : GoalItemState.IDLE;
            return new GoalItemView(GoalItemKind.TASK, task.id().toString(), "Task", task.title(), state, status, false, Optional.empty(),
                    Optional.of(new GoalActivity(task.title() + " · " + status, task.updatedAt())), Optional.empty());
        }

        private boolean ownRepository(String repo)
        {
            return repo.equalsIgnoreCase(workspace.monitoredRepository().fullName());
        }
    }
}
