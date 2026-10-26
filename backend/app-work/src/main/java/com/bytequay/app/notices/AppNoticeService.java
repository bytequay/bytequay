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
package com.bytequay.app.notices;

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.app.service.epic.EpicNextWork;
import com.bytequay.app.utils.ContextText;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.Artifact;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewRunOverview;
import com.bytequay.core.store.ArtifactStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimaps;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.agent.spi.ProviderApprovalRequest.QUESTION_TOOL;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/** Reads local facts without opening transcripts, contacting providers, or changing execution. */
@Service
public class AppNoticeService
{
    // Marks a notice that opens an Artifacts conversation, whose id the notice's task id holds; the app reads the same prefix.
    private static final String ARTIFACT_NOTICE = "artifact:";

    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentApprovalStore approvals;
    private final QueuedInputStore queues;
    private final AgentReviewStore reviews;
    private final PullRequestStore pullRequests;
    private final CiAutofixStore ciRuns;
    private final EpicNextWork next;
    private final ArtifactStore artifacts;
    private final WorkspaceStore workspaces;
    private final ObjectMapper mapper;

    public AppNoticeService(
            AgentTaskStore tasks,
            AgentTurnStore turns,
            AgentApprovalStore approvals,
            QueuedInputStore queues,
            AgentReviewStore reviews,
            PullRequestStore pullRequests,
            CiAutofixStore ciRuns,
            EpicNextWork next,
            ArtifactStore artifacts,
            WorkspaceStore workspaces,
            ObjectMapper mapper)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.queues = requireNonNull(queues, "queues is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.ciRuns = requireNonNull(ciRuns, "ciRuns is null");
        this.next = requireNonNull(next, "next is null");
        this.artifacts = requireNonNull(artifacts, "artifacts is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** What the app alerts on: the task notices, and each Artifacts conversation whose question timed out unanswered. */
    public List<AppNotice> alerts()
    {
        ImmutableList.Builder<AppNotice> alerts = ImmutableList.<AppNotice>builder().addAll(current());
        for (Artifact artifact : artifacts.missed()) {
            Artifact.MissedQuestion missed = artifact.missed().orElseThrow();
            workspaces.findById(artifact.workspaceId()).ifPresent(workspace -> alerts.add(new AppNotice(ARTIFACT_NOTICE + missed.approvalId(), AppNotice.Kind.QUESTION,
                    artifact.id(), artifact.workspaceId(), Optional.empty(), Optional.empty(), clip(artifact.title(), 160), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), clip("Timed out: " + question(missed.detail()), 280), workspace.monitoredRepository().fullName(),
                    missed.laneId(), Optional.empty(), missed.at())));
        }
        return alerts.build();
    }

    /** The task notices, which the Assistant and the agenda read as tasks. */
    public List<AppNotice> current()
    {
        Map<TaskId, AgentTask> candidates = new LinkedHashMap<>();
        // Unresolved requests must survive even when newer completed tasks fill the recent page.
        tasks.listRecent(200).forEach(task -> candidates.put(task.id(), task));
        tasks.listLive().forEach(task -> candidates.put(task.id(), task));
        Map<String, AgentReview> reviewTasks = reviews.listOpenReviews().stream().collect(toImmutableMap(AgentReview::taskId, review -> review));
        Map<TaskId, AgentReview> scopedReviews = new LinkedHashMap<>();
        candidates.values().forEach(task -> Optional.ofNullable(reviewTasks.get(task.id().toString()))
                .filter(review -> task.workspaceId().filter(review.workspaceId()::equals).isPresent())
                .ifPresent(review -> scopedReviews.put(task.id(), review)));
        candidates.values().removeIf(task -> task.archivedAt().isPresent() || (!task.interactive() && !scopedReviews.containsKey(task.id())));
        Map<TaskId, AgentTurn> latestTurns = turns.findLatest(candidates.keySet());
        Map<TaskId, AgentTurn> reports = turns.findLatestDevelopmentResults(candidates.keySet());
        Map<String, ReviewRunOverview> runs = reviews.summarizeRuns(candidates.keySet().stream()
                .filter(scopedReviews::containsKey).map(id -> scopedReviews.get(id).id()).collect(toImmutableSet()));
        var pending = Multimaps.index(approvals.listPending().stream().map(AgentApproval::request).iterator(), ApprovalRequest::taskId);
        ImmutableList.Builder<AppNotice> notices = ImmutableList.builder();
        for (AgentTask task : candidates.values()) {
            Optional<AgentReview> review = Optional.ofNullable(scopedReviews.get(task.id()));
            Optional<AgentTurn> latest = Optional.ofNullable(latestTurns.get(task.id()));
            Optional<ReviewRunOverview> overview = review.map(value -> runs.get(value.id()));
            Optional<ApprovalRequest> approval = pending.get(task.id()).stream()
                    .filter(request -> latest.filter(turn -> turn.state().active() && turn.id().equals(request.turnId())).isPresent())
                    .min(comparing(ApprovalRequest::requestedAt));
            select(task, latest, Optional.ofNullable(reports.get(task.id())), approval, review, overview).ifPresent(notices::add);
        }
        return notices.build();
    }

    private Optional<AppNotice> select(AgentTask task, Optional<AgentTurn> latest, Optional<AgentTurn> report, Optional<ApprovalRequest> approval,
            Optional<AgentReview> review, Optional<ReviewRunOverview> overview)
    {
        if (task.state() == TaskState.FAILED) {
            return Optional.of(notice(task, review, overview, latest, "failed:" + task.id(), AppNotice.Kind.FAILURE,
                    task.terminalReason().orElse("Task failed"), task.terminalAt().orElseThrow()));
        }
        if (task.state() == TaskState.OPEN && approval.isPresent()) {
            ApprovalRequest request = approval.orElseThrow();
            boolean question = request.providerRequest().requestedPermissions().contains(QUESTION_TOOL);
            return Optional.of(notice(task, review, overview, latest, "approval:" + request.id(), question ? AppNotice.Kind.QUESTION : AppNotice.Kind.APPROVAL,
                    question ? question(request.providerRequest().detail()) : request.providerRequest().title(), request.requestedAt()));
        }
        Optional<WorktreeMutationClaim> recovery = task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.RECOVERY
                || claim.kind() == WorktreeMutationClaim.Kind.TURN && latest.filter(turn -> turn.state().active()).isEmpty());
        if (recovery.isPresent()) {
            WorktreeMutationClaim claim = recovery.orElseThrow();
            return Optional.of(notice(task, review, overview, latest, "recovery:" + task.id() + ":" + claim.ownerId(), AppNotice.Kind.RECOVERY,
                    "ByteQuay could not verify that the agent stopped. New work is blocked; your files are kept.",
                    latest.flatMap(AgentTurn::endedAt).orElse(claim.acquiredAt())));
        }
        if (task.worktreeMutationClaim().isPresent() || task.developmentContinuationUnfinished() || latest.filter(turn -> turn.state().active()).isPresent()) {
            return Optional.empty();
        }
        Optional<EpicNextWork.Pick> pick = next.find(task.id().toString());
        if (pick.isPresent()) {
            EpicNextWork.Pick work = pick.orElseThrow();
            return Optional.of(new AppNotice("next:" + task.id() + ":" + work.itemId(), AppNotice.Kind.NEXT_WORK, task.id().toString(), task.workspaceId().orElseThrow(),
                    Optional.empty(), Optional.empty(), clip(work.title(), 160), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), clip(work.message(), 280),
                    task.repository().toString(), task.laneId().value(), Optional.of(work.itemId()), work.at()));
        }
        if (task.state() == TaskState.OPEN && latest.filter(turn -> turn.state() == TurnState.COMPLETED).isPresent()
                && report.filter(turn -> turn.state() == TurnState.COMPLETED).flatMap(AgentTurn::developmentResult).filter(result -> !result.readyForDelivery()).isPresent()) {
            AgentTurn asked = report.orElseThrow();
            return Optional.of(notice(task, review, overview, Optional.of(asked), "question:" + asked.id(), AppNotice.Kind.QUESTION,
                    asked.developmentResult().orElseThrow().message().orElse("The agent is waiting for your answer."), asked.endedAt().orElse(asked.createdAt())));
        }
        List<QueuedAgentInput> queue = task.state() == TaskState.OPEN ? queues.listQueued(task.id()).stream()
                .filter(input -> !input.state().historical()).collect(ImmutableList.toImmutableList()) : ImmutableList.of();
        Optional<QueuedAgentInput> paused = queue.stream().findFirst()
                .filter(input -> input.state() == QueuedAgentInput.State.PAUSED || input.state() == QueuedAgentInput.State.UNCERTAIN);
        if (latest.filter(turn -> turn.state() == TurnState.FAILED || turn.state() == TurnState.INTERRUPTED).isPresent()) {
            AgentTurn failed = latest.orElseThrow();
            String message = failed.terminalReason().orElse("The turn stopped before it finished.");
            if (paused.isPresent()) {
                message += " Queued messages are paused.";
            }
            return Optional.of(notice(task, review, overview, latest, "failed:" + failed.id(), AppNotice.Kind.FAILURE, message, failed.endedAt().orElseThrow()));
        }
        if (paused.isPresent()) {
            QueuedAgentInput input = paused.orElseThrow();
            String message = input.state() == QueuedAgentInput.State.UNCERTAIN ? "Delivery is uncertain. Review the queue before sending anything again."
                    : input.pauseReason().orElse("Queued messages are waiting for you to resume them.");
            return Optional.of(notice(task, review, overview, latest, "queue:" + input.id() + ":" + input.updatedAt(), AppNotice.Kind.QUEUE_PAUSED, message, input.updatedAt()));
        }
        Optional<AppNotice> ci = task.state() == TaskState.OPEN ? ciNotice(task, review, overview, latest) : Optional.empty();
        if (ci.isPresent()) {
            return ci;
        }
        // A completed turn can still hand off to a queued message or another review step.
        if (!queue.isEmpty() || overview.filter(ReviewRunOverview::active).isPresent()) {
            return Optional.empty();
        }
        return latest.filter(turn -> turn.state() == TurnState.COMPLETED)
                .map(turn -> notice(task, review, overview, latest, "finished:" + turn.id(), AppNotice.Kind.TURN_FINISHED,
                        "The latest response is ready to read.", turn.endedAt().orElseThrow()));
    }

    /** The latest CI run watching a development task, when it needs the user: a pause, failures the base also has, or no repair left. */
    private Optional<AppNotice> ciNotice(AgentTask task, Optional<AgentReview> review, Optional<ReviewRunOverview> overview, Optional<AgentTurn> latest)
    {
        return ciRuns.listByParentTaskId(task.id().toString()).stream().max(comparing(CiAutofixRun::createdAt)).flatMap(run -> {
            String key = "ci:" + run.id() + ":";
            if (run.status() == CiAutofixRun.Status.PAUSED) {
                return Optional.of(notice(task, review, overview, latest, key + "paused:" + run.version(), AppNotice.Kind.CI_ATTENTION,
                        "CI Autofix paused: " + run.summary().orElseThrow(), run.updatedAt()));
            }
            if (run.finishReason().filter(CiAutofixRun.FinishReason.MAX_ATTEMPTS::equals).isPresent()) {
                return Optional.of(notice(task, review, overview, latest, key + "attempts", AppNotice.Kind.CI_ATTENTION,
                        run.summary().orElseThrow(), run.terminalAt().orElseThrow()));
            }
            return run.baseFailureWarning().filter(warning -> run.status() == CiAutofixRun.Status.ACTIVE)
                    .map(warning -> notice(task, review, overview, latest, key + warning.code(), AppNotice.Kind.CI_ATTENTION, warning.message(), warning.occurredAt()));
        });
    }

    private String question(String detail)
    {
        try {
            String text = Optional.ofNullable(mapper.readTree(detail)).map(node -> node.path("questions").path(0).path("question").asText()).orElse("");
            if (!text.isBlank()) {
                return text;
            }
        }
        catch (JsonProcessingException ignored) {
            // Provider input can be incomplete; answering still takes place in the full task view.
        }
        return "The agent has a question for you.";
    }

    private AppNotice notice(AgentTask task, Optional<AgentReview> review, Optional<ReviewRunOverview> overview, Optional<AgentTurn> turn,
            String id, AppNotice.Kind kind, String message, Instant occurredAt)
    {
        Optional<AgentReview> numbered = review.filter(value -> value.number().isPresent());
        Optional<String> pullRequest = numbered.map(value -> value.repo() + "#" + value.number().getAsInt());
        Optional<PullRequest> stored = numbered
                .flatMap(value -> pullRequests.findIdByRepoAndNumber(value.repo(), value.number().getAsInt()))
                .flatMap(pullRequests::findById);
        Optional<String> pullRequestTitle = stored.map(PullRequest::title).filter(title -> !title.isBlank()).map(title -> clip(title, 240));
        Optional<String> branch = (review.isPresent() ? stored.map(PullRequest::headRef) : task.taskWorktree().map(TaskWorktree::generatedBranch))
                .filter(value -> !value.isBlank());
        Optional<AppNotice.Changes> changes = overview.map(run -> new AppNotice.Changes(
                run.scope() == ReviewRun.Scope.INCREMENTAL, run.commits(), run.files(), run.additions(), run.deletions()));
        return new AppNotice(id, kind, task.id().toString(), task.workspaceId().orElseThrow(), turn.map(value -> value.id().toString()), review.map(AgentReview::id),
                clip(task.title(), 160), pullRequest, pullRequestTitle, branch, changes, clip(message, 280), task.repository().toString(), task.laneId().value(),
                Optional.empty(), occurredAt);
    }

    private static String clip(String text, int limit)
    {
        return ContextText.excerpt(text.strip().replaceAll("\\s+", " "), limit);
    }
}
