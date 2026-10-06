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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.app.review.ReviewCodeService.WorkingCopySource;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.LocalRun;
import com.bytequay.core.review.LocalRun.Status;
import com.bytequay.core.review.LocalRun.TestClass;
import com.bytequay.core.review.LocalRunStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Retained run history and review-agent command evidence; native execution belongs to IdeExecutions. */
@Component
public class LocalRunService
        implements SmartLifecycle
{
    static final String RECOVERY_NEEDED = "The working copy needs recovery — see Review & Learning";
    static final String TASK_RECOVERY_NEEDED = "The working copy needs recovery — open the task";
    private static final String AGENT_WORKING = "The review agent is working";
    private static final Logger log = LoggerFactory.getLogger(LocalRunService.class);
    // A start whose completion never arrived, as when its turn was cut short, is forgotten after this.
    private static final Duration AGENT_COMMAND_MEMORY = Duration.ofHours(24);
    private static final int LIST_LIMIT = 200;
    private final AgentReviewStore reviewStore;
    private final AgentReviewService reviews;
    private final LocalRunStore runs;
    private final ManagedWorktreeService worktrees;
    private final IdeExecutions executions;
    private final Clock clock;
    private final ReviewCodeService code;
    private final Map<String, AgentStart> agentStarts = new ConcurrentHashMap<>();
    private volatile boolean running;

    public LocalRunService(AgentReviewStore reviewStore, AgentReviewService reviews, LocalRunStore runs,
            ManagedWorktreeService worktrees, ReviewCodeService code, IdeExecutions executions, Clock clock)
    {
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.code = requireNonNull(code, "code is null");
        this.executions = requireNonNull(executions, "executions is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** A review's runs, newest first. */
    public List<LocalRun> list(String reviewId)
    {
        return runs.listByTask(requireReview(reviewId).taskId(), LIST_LIMIT);
    }

    /** A development task's runs, newest first. */
    public List<LocalRun> list(TaskCodeHost host)
    {
        return runs.listByTask(code.task(host).id().toString(), LIST_LIMIT);
    }

    private LocalRun get(String reviewId, String runId)
    {
        return run(requireReview(reviewId).taskId(), runId);
    }

    private LocalRun get(TaskCodeHost host, String runId)
    {
        return run(code.task(host).id().toString(), runId);
    }

    private LocalRun run(String taskId, String runId)
    {
        return runs.find(runId).filter(run -> run.taskId().equals(taskId)).orElseThrow(() -> new ReviewNotFoundException("Run not found: " + runId));
    }

    public LocalRun stop(String reviewId, String runId)
    {
        return executions.stop(get(reviewId, runId));
    }

    public LocalRun stop(TaskCodeHost host, String runId)
    {
        return executions.stop(get(host, runId));
    }

    /**
     * Output after a character offset in the run's full stream.
     *
     * <p>A live or finished run of this backend reads its in-memory buffer, whose dropped middle is reported as skipped.
     * Any other run reads its stored, already bounded output.
     */
    public OutputChunk output(String reviewId, String runId, long after)
    {
        checkArgument(after >= 0, "after must not be negative");
        return output(get(reviewId, runId), after);
    }

    public OutputChunk output(TaskCodeHost host, String runId, long after)
    {
        checkArgument(after >= 0, "after must not be negative");
        return output(get(host, runId), after);
    }

    private OutputChunk output(LocalRun run, long after)
    {
        Optional<OutputChunk> current = run.status() == Status.RUNNING ? executions.output(run, after) : Optional.empty();
        if (current.isPresent()) {
            return current.orElseThrow();
        }
        String output = runs.findOutput(run.id()).orElse("");
        int from = (int) Math.min(after, output.length());
        return new OutputChunk(output.substring(from), output.length(), 0, run.status() != Status.RUNNING);
    }

    /**
     * Mirrors a Maven command the review agent ran into its task's run history when it completes. Its source facts are read when it
     * starts; a command whose start this backend did not see has none, and without a provider duration it reads no reports.
     * Nothing is caught up after a restart.
     */
    public void recordAgentCommand(String reviewId, AgentEventEnvelope envelope)
    {
        if (!(envelope.payload() instanceof AgentEventPayload.CommandActivity command) || command.background() || envelope.source().providerItemId().isEmpty()) {
            return;
        }
        Optional<MavenCommands.Maven> maven = MavenCommands.detect(command.argv());
        if (maven.isEmpty()) {
            return;
        }
        AgentTask task = reviews.task(reviewId);
        String key = task.id() + "/" + envelope.source().providerItemId().orElseThrow();
        Optional<TaskWorktree> worktree = task.taskWorktree().filter(ignored -> task.worktreeRemovedAt().isEmpty());
        if (worktree.isEmpty()) {
            return;
        }
        Path root = worktrees.ownedCheckout(worktree.orElseThrow());
        switch (command.state()) {
            case STARTED -> {
                agentStarts.values().removeIf(start -> start.occurredAt().isBefore(envelope.occurredAt().minus(AGENT_COMMAND_MEMORY)));
                agentStarts.put(key, new AgentStart(envelope.occurredAt(), code.workingCopySource(root)));
            }
            case COMPLETED, FAILED -> recordAgentFinish(task, worktree.orElseThrow(), root, envelope, command, maven.orElseThrow(), Optional.ofNullable(agentStarts.remove(key)));
            default -> {}
        }
    }

    private void recordAgentFinish(
            AgentTask task,
            TaskWorktree worktree,
            Path root,
            AgentEventEnvelope envelope,
            AgentEventPayload.CommandActivity command,
            MavenCommands.Maven maven,
            Optional<AgentStart> start)
    {
        // Codex reports a declined or interrupted command as failed with no exit code: it never ran. Claude also gives none for a command that timed out, which did run.
        if (!task.laneId().usesClaude() && command.state() == ActivityState.FAILED && command.exitCode().isEmpty()) {
            return;
        }
        Instant endedAt = envelope.occurredAt();
        Optional<Instant> startedAt = command.durationMs().map(endedAt::minusMillis).or(() -> start.map(AgentStart::occurredAt));
        Optional<List<TestClass>> reports = startedAt.isEmpty() ? Optional.of(ImmutableList.of()) : SurefireReports.readWindow(root, startedAt.orElseThrow(), endedAt);
        List<TestClass> results = reports.orElse(ImmutableList.of());
        RunOutput output = new RunOutput();
        output.append(command.output().orElse(""));
        RunOutput.Stored stored = output.finish();
        Optional<WorkingCopySource> source = start.map(AgentStart::source);
        int ordinal = nextOrdinal(task.id());
        LocalRun run = LocalRun.mirrored(
                UUID.randomUUID().toString(),
                task.id().toString(),
                worktree.workspaceId(),
                ordinal,
                maven.testClass(),
                maven.testMethod(),
                maven.script(),
                source.map(WorkingCopySource::head),
                source.map(WorkingCopySource::localPaths).orElse(ImmutableList.of()),
                source.map(WorkingCopySource::fingerprint),
                mirroredStatus(maven, reports, command.exitCode()),
                startedAt.orElse(endedAt),
                endedAt,
                command.exitCode().map(OptionalInt::of).orElse(OptionalInt.empty()),
                results.isEmpty() ? Optional.empty() : Optional.of(SurefireReports.counts(results)),
                results,
                stored.truncated() || command.truncated(),
                // The provider cut the output itself, so how much is missing is unknown.
                command.truncated() ? OptionalLong.empty() : stored.omittedCharacters(),
                envelope.eventId().toString());
        runs.update(runs.insert(run), stored.output());
    }

    /**
     * Only a lone Maven invocation's exit is Maven's: without one, rows that all pass
     * show no aggregate, and a run with no rows is a build failure only for a class or method selector.
     */
    static Status mirroredStatus(MavenCommands.Maven maven, Optional<List<TestClass>> reports, Optional<Integer> exitCode)
    {
        if (reports.isEmpty()) {
            return Status.RESULTS_UNAVAILABLE;
        }
        Optional<Integer> mavenExit = maven.lone() ? exitCode : Optional.empty();
        List<LocalRun.MethodStatus> methods = reports.orElseThrow().stream().flatMap(result -> result.methods().stream()).map(LocalRun.TestMethod::status).toList();
        if (methods.isEmpty()) {
            return maven.testClass().isPresent() && mavenExit.filter(code -> code != 0).isPresent() ? Status.BUILD_FAILED : Status.RESULTS_UNAVAILABLE;
        }
        if (methods.contains(LocalRun.MethodStatus.FAILED) || methods.contains(LocalRun.MethodStatus.ERROR)) {
            return Status.FAILED_ASSERTION;
        }
        if (mavenExit.isEmpty()) {
            return Status.RESULTS_UNAVAILABLE;
        }
        if (mavenExit.orElseThrow() != 0) {
            return Status.BUILD_FAILED;
        }
        return methods.contains(LocalRun.MethodStatus.PASSED) ? Status.PASSED : Status.NO_TESTS_RAN;
    }

    @Override
    public void start()
    {
        // Startup recovery has already classified the previous backend's launches and settled their claims.
        Instant now = Instant.now(clock);
        for (LocalRun run : runs.listUnsettled()) {
            try {
                LocalRun settled = run.status() == Status.RUNNING
                        ? run.finished(Status.INTERRUPTED, now, OptionalInt.empty(), Optional.empty(), ImmutableList.of(), run.outputTruncated(), run.omittedBytes())
                        : run;
                runs.update(settled.withTreeUnverified(false));
            }
            catch (RuntimeException failure) {
                log.warn("Local run {} could not be marked interrupted: {}", run.id(), failure.toString());
            }
        }
        running = true;
    }

    @Override
    public void stop()
    {
        running = false;
    }

    @Override
    public boolean isRunning()
    {
        return running;
    }

    /** Starts before the web server accepts requests and stops after it drains them, both while the datasource is open. */
    @Override
    public int getPhase()
    {
        return SmartLifecycle.DEFAULT_PHASE - 4096;
    }

    /** The availability rules the Code and Tests tab mirrors, checked under the review lock by runs and working-copy edits. */
    static void requireIdle(AgentTurnService turns, List<ReviewRun> reviewRuns, AgentReview review, AgentTask task)
    {
        requireIdle(turns, task, AGENT_WORKING, RECOVERY_NEEDED);
        if (!reviewRuns.isEmpty() && reviewRuns.getLast().state() == ReviewRun.State.RUNNING && !review.followUpPaused()) {
            throw new ReviewConflictException(AGENT_WORKING);
        }
    }

    /**
     * A development task's availability rules, with the reasons its tab shows. The core claim refuses the same states atomically; a
     * development turn's follow-up keeps the task unfinished between its steps.
     */
    static void requireTaskIdle(AgentTurnService turns, AgentTask task)
    {
        requireIdle(turns, task, "The task's agent is working", TASK_RECOVERY_NEEDED);
        if (task.developmentContinuationUnfinished()) {
            throw new ReviewConflictException("Development automation is working");
        }
    }

    private static void requireIdle(AgentTurnService turns, AgentTask task, String agentWorking, String recoveryNeeded)
    {
        Optional<WorktreeMutationClaim> claim = task.worktreeMutationClaim();
        if (claim.isPresent()) {
            WorktreeMutationClaim held = claim.orElseThrow();
            throw new ReviewConflictException(switch (held.kind()) {
                case LOCAL_RUN -> "A run is using this working copy";
                case COMPLETION -> "The working copy is being updated";
                case TURN -> turns.get(TurnId.valueOf(held.ownerId())).state().active() ? agentWorking : recoveryNeeded;
                case RECOVERY -> recoveryNeeded;
            });
        }
        if (!turns.queued(task.id()).isEmpty()) {
            throw new ReviewConflictException("Remove or resume the queued question");
        }
    }

    private int nextOrdinal(TaskId taskId)
    {
        return runs.listByTask(taskId.toString(), 1).stream().findFirst().map(LocalRun::ordinal).orElse(0) + 1;
    }

    private AgentReview requireReview(String reviewId)
    {
        return reviewStore.findReview(reviewId).orElseThrow(() -> new ReviewNotFoundException("Review not found: " + reviewId));
    }

    /**
     * Output after a requested offset.
     *
     * @param nextOffset the offset to request next
     * @param skippedBytes characters between the requested offset and {@code text} that are no longer held
     * @param complete the run's record is final and nothing follows {@code nextOffset}
     */
    public record OutputChunk(String text, long nextOffset, long skippedBytes, boolean complete)
    {
        public OutputChunk
        {
            requireNonNull(text, "text is null");
        }
    }

    private record AgentStart(Instant occurredAt, WorkingCopySource source) {}
}
