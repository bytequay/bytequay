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
import com.bytequay.app.jira.JiraIssue;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.service.goal.GoalReferenceReader;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.JiraIssueTask;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Jira admission supplies a trusted source to the ordinary interactive task creator. */
@Service
public class JiraDevelopmentService
{
    private final JiraIssueTaskStore sources;
    private final WorkspaceStore workspaces;
    private final AgentTaskService tasks;
    private final AgentTaskStore taskStore;
    private final AgentTurnStore turns;
    private final AgentUnitOfWork unitOfWork;
    private final JiraService jira;
    private final Clock clock;
    private final WorkItemService workItems;

    public JiraDevelopmentService(JiraIssueTaskStore sources, WorkspaceStore workspaces, AgentTaskService tasks,
            AgentTaskStore taskStore, AgentTurnStore turns, AgentUnitOfWork unitOfWork, JiraService jira, Clock clock, WorkItemService workItems)
    {
        this.sources = requireNonNull(sources, "sources is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
    }

    public Optional<Source> source(String taskId)
    {
        return sources.findByTaskId(taskId).map(link -> new Source(link.source(),
                turns.list(TaskId.valueOf(taskId)).stream().filter(turn -> turn.developmentOrigin().isEmpty()).findFirst().map(AgentTurn::id).map(Object::toString),
                link.instructions()));
    }

    public void requireUnattachedCreate(TaskId taskId)
    {
        if (sources.findByTaskId(taskId.toString()).isPresent()) {
            throw new AgentConflictException("Retry this task with its Jira source attached.");
        }
    }

    public AgentTask start(String workspaceId, TaskId taskId, JiraSourceInput input, String instructions, Function<JiraIssueSource, AgentTask> create)
    {
        workspaces.findById(workspaceId).orElseThrow(() -> WorkspaceException.notFound(workspaceId));
        return JiraIssueLocks.withLock(input.cloudId(), input.issueId(), () -> {
            Optional<JiraIssueTask> linked = sources.findBySource(input.cloudId(), input.issueId());
            if (linked.isPresent()) {
                JiraIssueTask link = linked.orElseThrow();
                boolean another = input.replaces().filter(link.taskId()::equals).isPresent() && !link.taskId().equals(taskId.toString());
                if (!another) {
                    if (!link.taskId().equals(taskId.toString())) {
                        return tasks.getInteractive(link.workspaceId(), TaskId.valueOf(link.taskId()));
                    }
                    checkArgument(link.workspaceId().equals(workspaceId), "Task belongs to another workspace");
                    if (!link.instructions().equals(instructions)) {
                        throw new AgentConflictException("Task creation request changed: " + taskId);
                    }
                    return create.apply(link.source());
                }
                checkArgument(link.workspaceId().equals(workspaceId), "Task belongs to another workspace");
                // An idle open task is still the ticket's attempt, and a delivery still under way belongs to it.
                AgentTask current = taskStore.find(TaskId.valueOf(link.taskId())).orElseThrow(() -> new AgentConflictException("The ticket's task is gone."));
                if (!current.state().terminal() || current.developmentContinuationUnfinished()) {
                    throw new AgentConflictException("The ticket's task has not ended. Continue it, or end it before starting another attempt.");
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
        if (issue.isGoal() || jira.isGoal(input.cloudId(), input.issueId())) {
            throw new AgentConflictException("This Jira issue is a Goal. Unmark it before starting development.");
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
            workspaces.findById(workspaceId).orElseThrow(() -> WorkspaceException.notFound(workspaceId));
            taskStore.insert(task);
            workItems.recordAttempt(task, GoalReferenceReader.jiraLink(source, clock.instant()));
            // Another attempt takes the ticket from the ended one, which keeps its source and stays the work item's earlier attempt.
            sources.findBySource(source.cloudId(), source.issueId()).filter(previous -> !previous.taskId().equals(task.id().toString()))
                    .ifPresent(previous -> sources.retire(previous.taskId()));
            sources.insert(new JiraIssueTask(task.id().toString(), workspaceId, source, instructions, clock.instant()));
            return null;
        });
    }

    public record Source(JiraIssueSource issue, Optional<String> initialTurnId, String instructions) {}
}
