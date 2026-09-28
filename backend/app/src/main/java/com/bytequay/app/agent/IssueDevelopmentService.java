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
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.goal.GoalService;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.core.domain.IssueGoal;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Striped;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Source-local admission for ordinary interactive tasks; execution stays in the existing task service. */
@Service
public class IssueDevelopmentService
{
    private static final String HOST = "github.com";
    private final IssueDevelopmentStore sources;
    private final RepositoryIssueStore issues;
    private final WorkspaceStore workspaces;
    private final AgentTaskService tasks;
    private final AgentTaskStore taskStore;
    private final AgentTurnStore turns;
    private final AgentUnitOfWork unitOfWork;
    private final GitHubRepoClient github;
    private final CredentialService credentials;
    private final Clock clock;
    private final ObjectProvider<GoalService> goals;
    private final Striped<Lock> sourceLocks = Striped.lock(64);

    public IssueDevelopmentService(IssueDevelopmentStore sources, RepositoryIssueStore issues, WorkspaceStore workspaces,
            AgentTaskService tasks, AgentTaskStore taskStore, AgentUnitOfWork unitOfWork, GitHubRepoClient github,
            CredentialService credentials, Clock clock, AgentTurnStore turns, ObjectProvider<GoalService> goals)
    {
        this.sources = requireNonNull(sources, "sources is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.github = requireNonNull(github, "github is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.turns = requireNonNull(turns, "turns is null");
        // Resolved per marker change: goals read tasks, which reach this service while starting development.
        this.goals = requireNonNull(goals, "goals is null");
    }

    public Metadata metadata(long issueId)
    {
        return new Metadata(sources.findGoal(HOST, issueId).map(IssueGoal::isGoal).orElse(false),
                sources.findTask(HOST, issueId).map(link -> new TaskLink(link.taskId(), link.workspaceId())));
    }

    public Metadata setGoal(String workspaceId, int number, boolean goal)
    {
        Workspace workspace = workspace(workspaceId);
        RepositoryIssue issue = issue(workspace, number);
        Lock lock = sourceLocks.get(issue.id());
        lock.lock();
        try {
            sources.saveGoal(new IssueGoal(HOST, issue.id(), workspace.monitoredRepository().fullName(), number, goal, clock.instant()));
            // The marked issue gets its own workspace goal, named after it, and loses it when unmarked.
            GoalSource source = new GoalSource(GoalItemKind.ISSUE, GoalItem.numberedKey(workspace.monitoredRepository().fullName(), number));
            if (goal) {
                goals.getObject().seed(workspaceId, source, issue.title());
            }
            else {
                goals.getObject().unseed(source);
            }
            return metadata(issue.id());
        }
        finally {
            lock.unlock();
        }
    }

    public RepositoryIssuePage attention(String workspaceId)
    {
        Workspace workspace = workspace(workspaceId);
        List<RepositoryIssue> eligible = credentials.cachedLogin()
                .map(login -> issues.findAssignedEligible(workspace.monitoredRepositoryId(), login, 101))
                .orElseGet(ImmutableList::of);
        RepositoryIssuePage snapshot = issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), 0, 1);
        return new RepositoryIssuePage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(),
                eligible.subList(0, Math.min(100, eligible.size())), 0, 100, eligible.size(), eligible.size() > 100, snapshot.syncedAt());
    }

    public Optional<Source> source(String taskId)
    {
        return sources.findByTaskId(taskId).map(link -> new Source(link.source(),
                turns.list(TaskId.valueOf(taskId)).stream().filter(turn -> turn.developmentOrigin().isEmpty()).findFirst().map(AgentTurn::id).map(Object::toString),
                link.instructions().orElse("")));
    }

    public void requireUnattachedCreate(TaskId taskId)
    {
        if (sources.findByTaskId(taskId.toString()).isPresent()) {
            throw new AgentConflictException("Retry this task with its issue source attached.");
        }
    }

    /** Holds the same canonical issue lock as Goal changes through task creation and initial-turn admission. */
    public AgentTask start(String workspaceId, TaskId taskId, IssueSourceInput input, String instructions, Function<GitHubIssueSource, AgentTask> create)
    {
        Workspace workspace = workspace(workspaceId);
        Lock lock = sourceLocks.get(input.issueId());
        lock.lock();
        try {
            Optional<IssueTask> linked = sources.findTask(HOST, input.issueId());
            if (linked.isPresent()) {
                IssueTask link = linked.orElseThrow();
                checkArgument(link.source().number() == input.number()
                        && link.source().repo().equalsIgnoreCase(workspace.monitoredRepository().fullName()), "Issue does not belong to this workspace");
                if (!link.taskId().equals(taskId.toString()) || metadata(input.issueId()).isGoal()) {
                    return tasks.getInteractive(link.workspaceId(), TaskId.valueOf(link.taskId()));
                }
                checkArgument(link.workspaceId().equals(workspaceId), "Task belongs to another workspace");
                if (link.instructions().filter(instructions::equals).isEmpty()) {
                    throw new AgentConflictException("Task creation request changed: " + taskId);
                }
                return create.apply(link.source());
            }
            RepositoryIssue cached = issue(workspace, input.number());
            checkArgument(cached.id() == input.issueId(), "Issue identity changed");
            if (metadata(input.issueId()).isGoal()) {
                throw new AgentConflictException("This issue is a Goal. Unmark it before starting development.");
            }
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
        }
        finally {
            lock.unlock();
        }
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
            sources.insertTask(new IssueTask(task.id().toString(), task.workspaceId().orElseThrow(), source, Optional.of(instructions), clock.instant()));
            return null;
        });
    }

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

    public record Source(GitHubIssueSource issue, Optional<String> initialTurnId, String instructions) {}

    public record TaskLink(String taskId, String workspaceId) {}

    public record Metadata(boolean isGoal, Optional<TaskLink> developmentTask) {}
}
