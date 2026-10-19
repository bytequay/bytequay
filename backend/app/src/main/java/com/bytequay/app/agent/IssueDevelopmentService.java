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
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.goal.GoalReferenceReader;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.JiraIssueTask;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraIssue;
import com.bytequay.integration.jira.JiraIssueLocks;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.bytequay.app.utils.Locks.withLock;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Source-local admission for ordinary interactive tasks; execution stays in the existing task service. */
@Service
public class IssueDevelopmentService
{
    private static final String HOST = "github.com";
    private final IssueDevelopmentStore sources;
    private final JiraIssueTaskStore jiraSources;
    private final JiraService jira;
    private final RepositoryIssueStore issues;
    private final WorkspaceStore workspaces;
    private final AgentTaskService tasks;
    private final AgentTaskStore taskStore;
    private final AgentTurnStore turns;
    private final AgentUnitOfWork unitOfWork;
    private final GitHubRepoClient github;
    private final CredentialService credentials;
    private final Clock clock;
    private final WorkItemService workItems;
    private final Striped<Lock> sourceLocks = Striped.lock(64);

    public IssueDevelopmentService(IssueDevelopmentStore sources, RepositoryIssueStore issues, WorkspaceStore workspaces,
            AgentTaskService tasks, AgentTaskStore taskStore, AgentUnitOfWork unitOfWork, GitHubRepoClient github,
            CredentialService credentials, Clock clock, AgentTurnStore turns, WorkItemService workItems, JiraIssueTaskStore jiraSources, JiraService jira)
    {
        this.sources = requireNonNull(sources, "sources is null");
        this.jiraSources = requireNonNull(jiraSources, "jiraSources is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.github = requireNonNull(github, "github is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.turns = requireNonNull(turns, "turns is null");
    }

    public Metadata metadata(long issueId)
    {
        return new Metadata(sources.findEpic(HOST, issueId).map(IssueEpic::isEpic).orElse(false),
                sources.findTask(HOST, issueId).map(link -> new TaskLink(link.taskId(), link.workspaceId())));
    }

    public Metadata setEpic(String workspaceId, int number, boolean epic)
    {
        Workspace workspace = workspace(workspaceId);
        RepositoryIssue issue = issue(workspace, number);
        Lock lock = sourceLocks.get(issue.id());
        return withLock(lock, () -> {
            return unitOfWork.execute(() -> {
                sources.saveEpic(new IssueEpic(HOST, issue.id(), workspace.monitoredRepository().fullName(), number, epic, clock.instant()));
                if (!epic) {
                    workItems.releaseEpic(workspaceId, WorkItemLink.issueIdentity(HOST, issue.id()));
                }
                return metadata(issue.id());
            });
        });
    }

    public RepositoryIssuePage attention(String workspaceId)
    {
        Workspace workspace = workspace(workspaceId);
        return issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), 0, Integer.MAX_VALUE);
    }

    public Optional<Source<GitHubIssueSource>> source(String taskId)
    {
        return sources.findByTaskId(taskId).map(link -> source(taskId, link.source(), link.instructions().orElse("")));
    }

    public Optional<Source<JiraIssueSource>> jiraSource(String taskId)
    {
        return jiraSources.findByTaskId(taskId).map(link -> source(taskId, link.source(), link.instructions()));
    }

    private <T> Source<T> source(String taskId, T issue, String instructions)
    {
        return new Source<>(issue, turns.list(TaskId.valueOf(taskId)).stream().filter(turn -> turn.developmentOrigin().isEmpty())
                .findFirst().map(AgentTurn::id).map(Object::toString), instructions);
    }

    public void requireAttachedSources(TaskId taskId, Optional<IssueSourceInput> issue, Optional<JiraSourceInput> jira)
    {
        if (issue.isEmpty() && sources.findByTaskId(taskId.toString()).isPresent()) {
            throw new AgentConflictException("Retry this task with its issue source attached.");
        }
        if (jira.isEmpty() && jiraSources.findByTaskId(taskId.toString()).isPresent()) {
            throw new AgentConflictException("Retry this task with its Jira source attached.");
        }
    }

    /** Holds the same canonical issue lock as Epic changes through task creation and initial-turn admission. */
    public AgentTask start(String workspaceId, TaskId taskId, IssueSourceInput input, String instructions, Function<GitHubIssueSource, AgentTask> create)
    {
        Workspace workspace = workspace(workspaceId);
        Lock lock = sourceLocks.get(input.issueId());
        return withLock(lock, () -> {
            Optional<IssueTask> linked = sources.findTask(HOST, input.issueId());
            if (linked.isPresent()) {
                IssueTask link = linked.orElseThrow();
                checkArgument(link.source().number() == input.number()
                        && link.source().repo().equalsIgnoreCase(workspace.monitoredRepository().fullName()), "Issue does not belong to this workspace");
                Optional<AgentTask> resumed = resume(workspaceId, taskId, input.replaces(), instructions,
                        new Attempt(link.taskId(), link.workspaceId(), link.instructions(), "issue"),
                        () -> metadata(input.issueId()).isEpic(), () -> create.apply(link.source()));
                if (resumed.isPresent()) {
                    return resumed.orElseThrow();
                }
            }
            RepositoryIssue cached = issue(workspace, input.number());
            checkArgument(cached.id() == input.issueId(), "Issue identity changed");
            if (metadata(input.issueId()).isEpic()) {
                throw new AgentConflictException("This issue is an Epic. Unmark it before starting development.");
            }
            workItems.requireStartable(workspaceId, WorkItemLink.issueIdentity(HOST, input.issueId()));
            if (!cached.updatedAt().equals(input.updatedAt())) {
                throw changed();
            }
            GitHubIssueSource fresh = github.fetchOpenIssueSource(credentials.resolveGitHubToken(), workspace.monitoredRepository(), input.number());
            checkArgument(fresh.issueId() == input.issueId(), "Issue identity changed");
            if (!fresh.title().equals(cached.title()) || !fresh.body().equals(cached.body())) {
                issues.updateContent(workspace.monitoredRepositoryId(), input.number(), fresh.title(), fresh.body(), fresh.updatedAt());
                throw changed();
            }
            if (taskStore.find(taskId).isPresent()) {
                throw new AgentConflictException("Task already exists without this issue source: " + taskId);
            }
            return create.apply(fresh);
        });
    }

    /** Called only after worktree preparation; these two durable facts commit together. */
    public void persistTask(AgentTask task, GitHubIssueSource source, String instructions)
    {
        unitOfWork.execute(() -> {
            Workspace current = workspace(task.workspaceId().orElseThrow());
            if (!current.monitoredRepository().fullName().equalsIgnoreCase(source.repo())) {
                throw new AgentConflictException("Workspace issue repository changed. Refresh the issue before starting development.");
            }
            taskStore.insert(task);
            workItems.recordAttempt(task, GoalReferenceReader.issueLink(source, clock.instant()));
            // Another attempt takes the issue from the ended one, which keeps its source and stays the work item's earlier attempt.
            sources.findTask(source.host(), source.issueId()).filter(previous -> !previous.taskId().equals(task.id().toString()))
                    .ifPresent(previous -> sources.retireTask(previous.taskId()));
            sources.insertTask(new IssueTask(task.id().toString(), task.workspaceId().orElseThrow(), source, Optional.of(instructions), clock.instant()));
            return null;
        });
    }

    public AgentTask start(String workspaceId, TaskId taskId, JiraSourceInput input, String instructions, Function<JiraIssueSource, AgentTask> create)
    {
        workspace(workspaceId);
        return JiraIssueLocks.withLock(input.cloudId(), input.issueId(), () -> {
            Optional<JiraIssueTask> linked = jiraSources.findBySource(input.cloudId(), input.issueId());
            if (linked.isPresent()) {
                JiraIssueTask link = linked.orElseThrow();
                Optional<AgentTask> resumed = resume(workspaceId, taskId, input.replaces(), instructions,
                        new Attempt(link.taskId(), link.workspaceId(), Optional.of(link.instructions()), "ticket"),
                        () -> false, () -> create.apply(link.source()));
                if (resumed.isPresent()) {
                    return resumed.orElseThrow();
                }
            }
            requireRunnable(jira.issue(input.connectionId(), input.cloudId(), input.issueId()), input, workspaceId);
            workItems.requireStartable(workspaceId, WorkItemLink.jiraIdentity(input.cloudId(), input.issueId()));
            JiraIssue fresh = jira.refreshIssue(input.connectionId(), input.cloudId(), input.issueId());
            requireRunnable(fresh, input, workspaceId);
            if (taskStore.find(taskId).isPresent()) {
                throw new AgentConflictException("Task already exists without this Jira source: " + taskId);
            }
            return create.apply(new JiraIssueSource(input.connectionId(), fresh.cloudId(), fresh.id(), fresh.key(), fresh.url(), fresh.title(), fresh.body(), fresh.updatedAt()));
        });
    }

    private void requireRunnable(JiraIssue issue, JiraSourceInput input, String workspaceId)
    {
        checkArgument(issue.connectionId().equals(input.connectionId()) && issue.cloudId().equals(input.cloudId()) && issue.id().equals(input.issueId()),
                "Jira issue identity changed");
        if (issue.workspace().isEmpty()) {
            throw new AgentConflictException("Assign a ByteQuay workspace before starting development.");
        }
        if (!issue.workspace().orElseThrow().id().equals(workspaceId)) {
            throw new AgentConflictException("This Jira ticket is assigned to another workspace. Refresh it before starting development.");
        }
        if (issue.isEpic() || jira.isEpic(input.cloudId(), input.issueId())) {
            throw new AgentConflictException("This Jira issue is an Epic. Unmark it before starting development.");
        }
        if (issue.hasChildren()) {
            throw new AgentConflictException("This Jira issue has children. Start development from a leaf issue.");
        }
        if ("done".equalsIgnoreCase(issue.status().category())) {
            throw new AgentConflictException("This Jira issue is done. Refresh it before starting development.");
        }
        if (!issue.key().equals(input.key()) || !issue.title().equals(input.title()) || !issue.body().equals(input.body())) {
            throw new AgentConflictException("Jira issue changed. Refresh it before starting development.");
        }
    }

    /** The prepared task and accepted Jira association commit before the first turn can start. */
    public void persistTask(AgentTask task, JiraIssueSource source, String instructions)
    {
        unitOfWork.execute(() -> {
            String workspaceId = task.workspaceId().orElseThrow();
            workspace(workspaceId);
            taskStore.insert(task);
            workItems.recordAttempt(task, GoalReferenceReader.jiraLink(source, clock.instant()));
            // Another attempt takes the ticket from the ended one, which keeps its source and stays the work item's earlier attempt.
            jiraSources.findBySource(source.cloudId(), source.issueId()).filter(previous -> !previous.taskId().equals(task.id().toString()))
                    .ifPresent(previous -> jiraSources.retire(previous.taskId()));
            jiraSources.insert(new JiraIssueTask(task.id().toString(), workspaceId, source, instructions, clock.instant()));
            return null;
        });
    }

    /** Empty means a replacement may proceed through the source's current eligibility checks. */
    private Optional<AgentTask> resume(String workspaceId, TaskId taskId, Optional<String> replaces, String instructions,
            Attempt previous, BooleanSupplier reopenOnly, Supplier<AgentTask> retry)
    {
        boolean another = replaces.filter(previous.taskId()::equals).isPresent() && !previous.taskId().equals(taskId.toString());
        if (!another) {
            if (!previous.taskId().equals(taskId.toString()) || reopenOnly.getAsBoolean()) {
                return Optional.of(tasks.getInteractive(previous.workspaceId(), TaskId.valueOf(previous.taskId())));
            }
            checkArgument(previous.workspaceId().equals(workspaceId), "Task belongs to another workspace");
            if (previous.instructions().filter(instructions::equals).isEmpty()) {
                throw new AgentConflictException("Task creation request changed: " + taskId);
            }
            return Optional.of(retry.get());
        }
        checkArgument(previous.workspaceId().equals(workspaceId), "Task belongs to another workspace");
        // An idle open task is still the attempt, and a delivery still under way belongs to it.
        AgentTask current = taskStore.find(TaskId.valueOf(previous.taskId()))
                .orElseThrow(() -> new AgentConflictException("The " + previous.subject() + "'s task is gone."));
        if (!current.state().terminal() || current.developmentContinuationUnfinished()) {
            throw new AgentConflictException("The " + previous.subject() + "'s task has not ended. Continue it, or end it before starting another attempt.");
        }
        return Optional.empty();
    }

    private record Attempt(String taskId, String workspaceId, Optional<String> instructions, String subject) {}

    private static AgentConflictException changed()
    {
        return new AgentConflictException("Issue changed. Refresh the issue before starting development.");
    }

    private Workspace workspace(String id)
    {
        return workspaces.findById(id).orElseThrow(() -> WorkspaceException.notFound(id));
    }

    private RepositoryIssue issue(Workspace workspace, int number)
    {
        return issues.find(workspace.monitoredRepositoryId(), number)
                .orElseThrow(() -> new AgentConflictException("Issue is no longer available locally. Refresh issues before starting development."));
    }

    public record Source<T>(T issue, Optional<String> initialTurnId, String instructions) {}

    public record TaskLink(String taskId, String workspaceId) {}

    public record Metadata(boolean isEpic, Optional<TaskLink> developmentTask) {}
}
