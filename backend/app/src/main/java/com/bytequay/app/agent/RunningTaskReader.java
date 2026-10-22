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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.app.agent.RunningTask.Kind;
import com.bytequay.app.agent.RunningTask.Status;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Comparator.comparing;
import static java.util.Comparator.reverseOrder;
import static java.util.Objects.requireNonNull;

/** Projects existing local lifecycle facts; opening the sidebar never starts or changes work. */
@Service
public class RunningTaskReader
{
    private static final Duration COMPLETION_RETENTION = Duration.ofMinutes(10);

    private final WorkspaceStore workspaces;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentApprovalStore approvals;
    private final QueuedInputStore queues;
    private final AgentReviewStore reviews;
    private final CherryPickExecutionStore cherryPicks;
    private final CiAutofixStore ciFixes;
    private final PullRequestStore pullRequests;
    private final Clock clock;

    public RunningTaskReader(WorkspaceStore workspaces, AgentTaskStore tasks, AgentTurnStore turns, AgentApprovalStore approvals, QueuedInputStore queues,
            AgentReviewStore reviews, CherryPickExecutionStore cherryPicks, CiAutofixStore ciFixes, PullRequestStore pullRequests, Clock clock)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.queues = requireNonNull(queues, "queues is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.cherryPicks = requireNonNull(cherryPicks, "cherryPicks is null");
        this.ciFixes = requireNonNull(ciFixes, "ciFixes is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public List<RunningTask> current()
    {
        Instant now = clock.instant();
        Set<String> waitingTurns = approvals.listPending().stream().map(approval -> approval.request().turnId().toString()).collect(toImmutableSet());
        ImmutableList.Builder<RunningTask> result = ImmutableList.builder();
        // Workspace history has no recent-page limit that could hide an older running task.
        workspaces.findAllRetained().forEach(workspace -> tasks.listByWorkspace(workspace.id()).stream()
                .filter(task -> task.archivedAt().isEmpty())
                .filter(task -> !task.state().terminal() || task.updatedAt().plus(COMPLETION_RETENTION).isAfter(now))
                .forEach(task -> read(task, waitingTurns).filter(item -> item.expiresAt().map(at -> at.isAfter(now)).orElse(true)).ifPresent(result::add)));
        return result.build().stream()
                .sorted(comparing((RunningTask task) -> task.expiresAt().isPresent()).thenComparing(RunningTask::startedAt, reverseOrder())
                        .thenComparing(RunningTask::id))
                .collect(ImmutableList.toImmutableList());
    }

    private Optional<RunningTask> read(AgentTask task, Set<String> waitingTurns)
    {
        Kind kind = Kind.TASK;
        String targetId = task.id().toString();
        String title = task.title();
        Optional<ReviewRun> run = Optional.empty();
        if (!task.interactive()) {
            var review = reviews.findReviewByTask(task.id().toString());
            if (review.isPresent()) {
                kind = Kind.REVIEW;
                targetId = review.orElseThrow().id();
                run = reviews.listRuns(targetId).stream().max(comparing(ReviewRun::createdAt));
                // The stored task title is only "Review owner/repo#N"; the cached pull request title says what is being reviewed.
                OptionalLong pullRequestId = review.orElseThrow().pullRequestId();
                if (pullRequestId.isPresent()) {
                    title = pullRequests.findById(pullRequestId.getAsLong()).map(PullRequest::title).orElse(title);
                }
            }
            else {
                var cherry = cherryPicks.findByAgentTaskId(task.id().toString());
                if (cherry.isPresent()) {
                    return Optional.of(cherryPick(task, cherry.orElseThrow()));
                }
                var ci = ciFixes.findByAgentTaskId(task.id().toString()).filter(value -> value.scope().parentTaskId().isEmpty());
                return ci.map(value -> ciFix(task, value));
            }
        }
        Optional<AgentTurn> latest = turns.list(task.id()).stream().max(comparing(AgentTurn::createdAt));
        Instant startedAt = latest.map(AgentTurn::createdAt).orElse(task.createdAt());
        Optional<Instant> endedAt = latest.flatMap(AgentTurn::endedAt);
        Status status = latest.map(turn -> switch (turn.state()) {
            case ACCEPTED, RUNNING -> waitingTurns.contains(turn.id().toString()) ? Status.WAITING : Status.RUNNING;
            case COMPLETED -> Status.COMPLETED;
            case FAILED -> Status.FAILED;
            case CANCELLED, INTERRUPTED -> Status.STOPPED;
        }).orElse(Status.COMPLETED);
        if (task.state() == TaskState.FAILED) {
            status = Status.FAILED;
            endedAt = task.terminalAt();
        }
        else if (task.state() == TaskState.OPEN) {
            boolean queued = queues.listQueued(task.id()).stream()
                    .anyMatch(input -> !input.state().historical() && input.state() != QueuedAgentInput.State.PAUSED && input.state() != QueuedAgentInput.State.UNCERTAIN);
            if (task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.RECOVERY).isPresent()) {
                status = Status.BLOCKED;
                endedAt = Optional.empty();
            }
            else if (task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.TURN).isPresent()
                    || task.developmentContinuationUnfinished() || run.filter(ReviewRun::active).isPresent() || queued) {
                if (status != Status.WAITING) {
                    status = Status.RUNNING;
                }
                endedAt = Optional.empty();
            }
        }
        // A review can settle after its last turn; internal follow-up turns do not finish the row.
        if (status == Status.COMPLETED && run.isPresent() && run.orElseThrow().endedAt().filter(at -> !at.isBefore(startedAt)).isPresent()) {
            ReviewRun finished = run.orElseThrow();
            endedAt = finished.endedAt();
            status = switch (finished.state()) {
                case ACCEPTED -> Status.COMPLETED;
                case STOPPED -> Status.STOPPED;
                case FAILED, INCOMPLETE -> Status.FAILED;
                case RUNNING -> Status.RUNNING;
            };
        }
        if (latest.isEmpty() && status == Status.COMPLETED) {
            return Optional.empty();
        }
        return Optional.of(item(task, kind, targetId, title, status, startedAt, endedAt));
    }

    private static RunningTask cherryPick(AgentTask task, CherryPickExecution execution)
    {
        Status status = switch (execution.status()) {
            case RUNNING -> Status.RUNNING;
            case PAUSED -> Status.WAITING;
            case FINISHED -> execution.finishReason().orElseThrow() == CherryPickExecution.FinishReason.COMPLETED ? Status.COMPLETED : Status.STOPPED;
        };
        return item(task, Kind.CHERRY_PICK, execution.id().toString(), task.title(), status, execution.createdAt(), execution.terminalAt());
    }

    private static RunningTask ciFix(AgentTask task, CiAutofixRun run)
    {
        Status status = switch (run.status()) {
            case ACTIVE -> Status.RUNNING;
            case PAUSED -> Status.WAITING;
            case FINISHED -> switch (run.finishReason().orElseThrow()) {
                case GREEN -> Status.COMPLETED;
                case ERROR, MAX_ATTEMPTS -> Status.FAILED;
                case CANCELLED, STALE_HEAD, AUTHORIZATION_EXPIRED -> Status.STOPPED;
            };
        };
        return item(task, Kind.CI_FIX, Integer.toString(run.scope().pullRequest().number()), "Get CI green on #" + run.scope().pullRequest().number(),
                status, run.createdAt(), run.terminalAt());
    }

    private static RunningTask item(AgentTask task, Kind kind, String targetId, String title, Status status, Instant startedAt, Optional<Instant> endedAt)
    {
        return new RunningTask(task.id().toString(), task.workspaceId().orElseThrow(), title, task.repository().toString(), kind, targetId,
                status, startedAt, endedAt.map(at -> at.plus(COMPLETION_RETENTION)));
    }
}
