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
package com.bytequay.app.cherrypick;

import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.SendCiAutofixMessageCommand;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.rebase.BranchRebaseService;
import com.bytequay.app.rebase.StartBranchRebaseCommand;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickLifecycle;
import com.bytequay.core.cherrypick.CherryPickLifecycle.BranchCommit;
import com.bytequay.core.cherrypick.CherryPickLifecycle.BranchCommit.Origin;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Hold.Reason;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Rebase;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Squash;
import com.bytequay.core.cherrypick.CherryPickLifecycle.Stage;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseId;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.bytequay.app.agent.runtime.WorkflowAgentTasks.repositoryIdentity;
import static java.util.Objects.requireNonNull;

/**
 * Carries a delivering execution from its built branch to the merged pull request.
 *
 * <p>Starting the execution authorized publishing the branch, opening its draft pull request,
 * CI Autofix's pushes, and the force-pushes that rebase it onto a moved target. Folding the
 * fixups waits for the user's approval and merging for the user's click. Each call performs at
 * most one bounded step under the caller's execution lock.
 */
@Service
public class CherryPickDelivery
{
    private static final Logger log = LoggerFactory.getLogger(CherryPickDelivery.class);
    private static final Duration DRIFT_CHECK_INTERVAL = Duration.ofMinutes(2);
    private static final Duration MERGE_CHECK_INTERVAL = Duration.ofSeconds(30);
    private static final int MAX_SUMMARY_CHARACTERS = 8 * 1024;

    private final CherryPickExecutionStore store;
    private final WorkspacePullRequestService publication;
    private final CiAutofixService ci;
    private final BranchRebaseService rebases;
    private final CherryPickPreviewService previews;
    private final CherryPickBranch branch;
    private final Clock clock;
    // Remote polls are throttled in memory: a restart simply checks again.
    private final Map<String, Instant> remoteChecks = new ConcurrentHashMap<>();

    public CherryPickDelivery(
            CherryPickExecutionStore store,
            WorkspacePullRequestService publication,
            CiAutofixService ci,
            BranchRebaseService rebases,
            CherryPickPreviewService previews,
            GitCommandRunner git,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.publication = requireNonNull(publication, "publication is null");
        this.ci = requireNonNull(ci, "ci is null");
        this.rebases = requireNonNull(rebases, "rebases is null");
        this.previews = requireNonNull(previews, "previews is null");
        this.branch = new CherryPickBranch(git);
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** One step of a running, unheld execution past its build stage. */
    CherryPickExecution advance(CherryPickExecution execution)
    {
        CherryPickLifecycle lifecycle = execution.lifecycle();
        if (lifecycle.liveRebase().isPresent()) {
            return watchRebase(execution);
        }
        return switch (lifecycle.stage()) {
            case PUBLISH -> publish(execution);
            case CI_FIX, MERGE -> watchCi(execution);
            case SQUASH -> lifecycle.squash().isPresent() ? squash(execution) : checkDrift(execution);
            case APPLY, BUILD -> throw new IllegalStateException("Stage " + lifecycle.stage() + " is not a delivery stage");
        };
    }

    /** Stops CI Autofix from pushing while the user holds the execution. */
    void pause(CherryPickExecution execution)
    {
        currentRun(execution).filter(run -> run.status() != CiAutofixRun.Status.FINISHED).ifPresent(run -> ci.cancel(run.id()));
    }

    /**
     * Releases a hold and restarts what it stopped: a paused rebase continues, a paused CI round
     * takes the note, and a stopped CI round is replaced by a new one.
     */
    CherryPickExecution resume(CherryPickExecution execution, Optional<String> note)
    {
        CherryPickExecution released = store.save(execution.withLifecycle(CherryPickLifecycle::released, clock.instant()));
        Optional<Rebase> live = released.lifecycle().liveRebase();
        if (live.isPresent()) {
            BranchRebaseId rebaseId = BranchRebaseId.valueOf(live.orElseThrow().rebaseId().orElseThrow());
            if (rebases.get(rebaseId).status() == BranchRebase.Status.PAUSED) {
                rebases.sendMessage(rebaseId, note.orElse("Continue the rebase."));
            }
            return released;
        }
        Stage stage = released.lifecycle().stage();
        if (stage != Stage.CI_FIX && stage != Stage.MERGE) {
            return released;
        }
        Optional<CiAutofixRun> run = currentRun(released);
        if (run.isPresent() && run.orElseThrow().status() == CiAutofixRun.Status.PAUSED && note.isPresent()) {
            ci.sendMessage(new SendCiAutofixMessageCommand(run.orElseThrow().id(), note.orElseThrow(), UUID.randomUUID().toString(), Optional.empty()));
            return released;
        }
        if (run.isPresent() && (run.orElseThrow().status() == CiAutofixRun.Status.ACTIVE || green(run.orElseThrow()))) {
            return released;
        }
        run.filter(value -> value.status() == CiAutofixRun.Status.PAUSED).ifPresent(value -> ci.cancel(value.id()));
        return startCiRound(released);
    }

    /** Sends a message into the running CI turn; false when no CI turn can take it now. */
    boolean steerCi(CherryPickExecution execution, String message)
    {
        Optional<CiAutofixRun> run = currentRun(execution)
                .filter(value -> value.status() == CiAutofixRun.Status.ACTIVE && value.currentTurnId().isPresent() && ci.canMessage(value));
        if (run.isEmpty()) {
            return false;
        }
        ci.sendMessage(new SendCiAutofixMessageCommand(run.orElseThrow().id(), message, UUID.randomUUID().toString(),
                Optional.of(TurnId.valueOf(run.orElseThrow().currentTurnId().orElseThrow()))));
        return true;
    }

    /** Stops CI and any rebase, then withdraws the pull request and the published branch. */
    List<String> withdraw(CherryPickExecution execution)
    {
        ImmutableList.Builder<String> problems = ImmutableList.builder();
        try {
            pause(execution);
        }
        catch (RuntimeException failure) {
            problems.add("Could not stop CI Autofix: " + failure.getMessage());
        }
        execution.lifecycle().liveRebase().flatMap(Rebase::rebaseId).ifPresent(rebaseId -> {
            try {
                rebases.cancel(BranchRebaseId.valueOf(rebaseId));
            }
            catch (RuntimeException failure) {
                problems.add("Could not stop the rebase: " + failure.getMessage());
            }
        });
        problems.addAll(publication.withdraw(execution, true));
        return problems.build();
    }

    private CherryPickExecution publish(CherryPickExecution execution)
    {
        CherryPickExecution published;
        try {
            published = publication.publish(execution.id());
        }
        catch (RuntimeException failure) {
            return hold(execution, Reason.PUBLISH_FAILED, "Could not publish the work branch: " + failure.getMessage());
        }
        return startCiRound(store.save(published.withLifecycle(lifecycle -> lifecycle.inStage(Stage.CI_FIX), clock.instant())));
    }

    private CherryPickExecution startCiRound(CherryPickExecution execution)
    {
        CiAutofixRun run;
        try {
            run = publication.startCi(execution, execution.lifecycle().ciRunIds().size() + 1);
        }
        catch (RuntimeException failure) {
            return hold(execution, Reason.CI_FAILED, "Could not start CI Autofix: " + failure.getMessage());
        }
        return store.save(execution.withLifecycle(lifecycle -> lifecycle.withCiRun(run.id().toString()), clock.instant()));
    }

    private CherryPickExecution watchCi(CherryPickExecution execution)
    {
        Optional<CiAutofixRun> current = currentRun(execution);
        if (current.isEmpty()) {
            return startCiRound(execution);
        }
        CiAutofixRun run = current.orElseThrow();
        if (run.status() == CiAutofixRun.Status.ACTIVE) {
            return execution.lifecycle().stage() == Stage.CI_FIX ? checkDrift(execution) : execution;
        }
        if (!green(run)) {
            return hold(execution, Reason.CI_FAILED, run.summary().orElse("CI Autofix stopped before the checks were green"));
        }
        CherryPickExecution followed = follow(execution, run.expectedRemoteHead(), Origin.CI);
        if (followed.lifecycle().hold().isPresent()) {
            return followed;
        }
        if (!followed.lifecycle().fixups().isEmpty()) {
            return store.save(followed.withLifecycle(lifecycle -> lifecycle.inStage(Stage.SQUASH).withSquash(Optional.empty()), clock.instant()));
        }
        if (followed.lifecycle().stage() == Stage.CI_FIX) {
            return store.save(followed.withLifecycle(lifecycle -> lifecycle.inStage(Stage.MERGE), clock.instant()));
        }
        return awaitMerge(followed);
    }

    /** Moves the worktree to the head CI Autofix pushed and reads the branch again. */
    private CherryPickExecution follow(CherryPickExecution execution, String remoteHead, Origin origin)
    {
        Path worktree = execution.workspace().worktree();
        String tip = execution.currentTargetSha();
        if (!remoteHead.equals(tip)) {
            Optional<String> moved = branch.moveTo(worktree, tip, remoteHead);
            if (moved.isPresent()) {
                return hold(execution, Reason.CI_FAILED, moved.orElseThrow());
            }
        }
        String base = execution.lifecycle().baseSha();
        if (!branch.isAncestor(worktree, base, remoteHead)) {
            // CI Autofix rebases a failing branch onto a moved target itself; read from the new fork point.
            base = previews.synchronizeTarget(worktree, execution);
            base = branch.mergeBase(worktree, remoteHead, base).orElse(base);
        }
        CherryPickBranch.Inspection inspection = branch.inspect(worktree, base, remoteHead, CherryPickBranch.appliedSources(execution), execution.lifecycle().commits(), origin);
        if (inspection.problem().isPresent()) {
            return hold(execution, Reason.CI_FAILED, "CI Autofix left a branch ByteQuay cannot fold: " + inspection.problem().orElseThrow());
        }
        String forkPoint = base;
        CherryPickExecution current = remoteHead.equals(tip) ? execution : execution.withTip(remoteHead, clock.instant());
        return store.save(current.withLifecycle(lifecycle -> lifecycle.withBranch(forkPoint, inspection.commits()), clock.instant()));
    }

    private CherryPickExecution awaitMerge(CherryPickExecution execution)
    {
        if (!due(execution, "merge", MERGE_CHECK_INTERVAL)) {
            return execution;
        }
        GitHubPullRequestIdentity pull;
        try {
            pull = publication.pullRequest(execution);
        }
        catch (RuntimeException failure) {
            // A background poll; the next one tries again.
            log.warn("Cannot read the pull request of execution {}", execution.id(), failure);
            return execution;
        }
        CherryPickExecution current = execution;
        if (!current.lifecycle().readyForReview() && pull.state() == GitHubPullRequestIdentity.State.OPEN) {
            try {
                if (pull.draft()) {
                    publication.markReady(current);
                }
                current = store.save(current.withLifecycle(CherryPickLifecycle::markedReady, clock.instant()));
            }
            catch (RuntimeException failure) {
                log.warn("Cannot mark the pull request of execution {} ready for review", execution.id(), failure);
            }
        }
        return switch (pull.state()) {
            case OPEN -> current;
            case CLOSED -> hold(current, Reason.PULL_REQUEST_CLOSED, "The pull request was closed on GitHub");
            case MERGED -> merged(current);
        };
    }

    private CherryPickExecution merged(CherryPickExecution execution)
    {
        List<String> problems = publication.withdraw(execution, false);
        String summary = "Merged into " + execution.workspace().targetBranch() + (problems.isEmpty() ? "" : ". " + String.join(". ", problems));
        return store.save(execution.finish(FinishReason.COMPLETED, bounded(summary), clock.instant()));
    }

    private CherryPickExecution checkDrift(CherryPickExecution execution)
    {
        if (!due(execution, "drift", DRIFT_CHECK_INTERVAL)) {
            return execution;
        }
        Path worktree = execution.workspace().worktree();
        String target;
        try {
            target = previews.synchronizeTarget(worktree, execution);
        }
        catch (RuntimeException failure) {
            log.warn("Cannot check the target of execution {}", execution.id(), failure);
            return execution;
        }
        if (branch.isAncestor(worktree, target, execution.currentTargetSha())) {
            return execution;
        }
        return startRebase(execution, target);
    }

    private CherryPickExecution startRebase(CherryPickExecution execution, String target)
    {
        CherryPickExecution current = execution;
        Optional<CiAutofixRun> run = currentRun(current);
        if (run.filter(value -> value.status() != CiAutofixRun.Status.FINISHED).isPresent()) {
            run = Optional.of(ci.cancel(run.orElseThrow().id()));
        }
        // The pull request may hold CI repairs this worktree has not followed yet.
        String head = run.map(CiAutofixRun::expectedRemoteHead).orElse(current.currentTargetSha());
        if (!head.equals(current.currentTargetSha())) {
            current = follow(current, head, Origin.CI);
            if (current.lifecycle().hold().isPresent()) {
                return current;
            }
        }
        Path worktree = current.workspace().worktree();
        int number = current.lifecycle().rebases().size() + 1;
        int moved = branch.count(worktree, current.lifecycle().baseSha(), target);
        BranchRebase rebase;
        try {
            // The execution's agent task is held to exactly this worktree each time the execution advances.
            TaskWorktree taskWorktree = new TaskWorktree(TaskId.valueOf(current.agentTaskId().orElseThrow()), current.workspace().workspaceId(),
                    repositoryIdentity(current.workspace().physicalRepository()), worktree, current.workspace().workBranch(), current.range().initialTargetSha());
            rebase = rebases.start(new StartBranchRebaseCommand(taskWorktree, current.workspace().localTargetBranch(), "cherry-pick-rebase:" + current.id() + ":" + number));
        }
        catch (RuntimeException failure) {
            return hold(current, Reason.REBASE_FAILED, "Could not start the rebase: " + failure.getMessage());
        }
        Rebase entry = new Rebase(number, target, moved, Optional.of(rebase.id().toString()), Rebase.State.RUNNING, Optional.empty(), clock.instant(), Optional.empty());
        return store.save(current.withLifecycle(lifecycle -> lifecycle.withRebase(entry).withSquash(Optional.empty()), clock.instant()));
    }

    private CherryPickExecution watchRebase(CherryPickExecution execution)
    {
        Rebase entry = execution.lifecycle().liveRebase().orElseThrow();
        BranchRebase rebase = rebases.get(BranchRebaseId.valueOf(entry.rebaseId().orElseThrow()));
        if (rebase.status() == BranchRebase.Status.RUNNING) {
            return execution;
        }
        if (rebase.status() == BranchRebase.Status.PAUSED) {
            return hold(execution, Reason.REBASE_FAILED, rebase.summary().orElse("The rebase paused"));
        }
        if (rebase.finishReason().filter(BranchRebase.FinishReason.COMPLETED::equals).isEmpty() || rebase.resultWorkTip().isEmpty()) {
            return failRebase(execution, entry, rebase.summary().orElse("The rebase was cancelled"));
        }
        Path worktree = execution.workspace().worktree();
        String result = rebase.resultWorkTip().orElseThrow();
        CherryPickBranch.Inspection inspection = branch.inspect(worktree, entry.baseSha(), result, CherryPickBranch.appliedSources(execution),
                execution.lifecycle().commits(), Origin.CI);
        if (inspection.problem().isPresent()) {
            return failRebase(execution, entry, "The rebased branch broke its layout: " + inspection.problem().orElseThrow());
        }
        try {
            publication.replaceBranch(execution, result, execution.currentTargetSha());
        }
        catch (RuntimeException failure) {
            return failRebase(execution, entry, "Could not push the rebased branch: " + failure.getMessage());
        }
        String summary = "Replayed " + inspection.commits().size() + " commits " + (rebase.conflictCommitSha().isPresent() ? "and resolved conflicts" : "cleanly");
        CherryPickExecution rebased = store.save(execution.withTip(result, clock.instant()).withLifecycle(lifecycle -> lifecycle
                .withBranch(entry.baseSha(), inspection.commits())
                .withRebase(entry.ended(Rebase.State.DONE, summary, clock.instant()))
                .inStage(Stage.CI_FIX)
                .withSquash(Optional.empty()), clock.instant()));
        return startCiRound(rebased);
    }

    private CherryPickExecution failRebase(CherryPickExecution execution, Rebase entry, String summary)
    {
        Rebase ended = entry.ended(Rebase.State.FAILED, bounded(summary), clock.instant());
        CherryPickExecution failed = store.save(execution.withLifecycle(lifecycle -> lifecycle.withRebase(ended), clock.instant()));
        return hold(failed, Reason.REBASE_FAILED, summary);
    }

    /** Folds the fixups the user approved, checks the result, and replaces the published branch. */
    private CherryPickExecution squash(CherryPickExecution execution)
    {
        Squash approval = execution.lifecycle().squash().orElseThrow();
        String tip = execution.currentTargetSha();
        if (!approval.approvedHead().equals(tip) || approval.resultHead().isPresent()) {
            return store.save(execution.withLifecycle(lifecycle -> lifecycle.withSquash(Optional.empty()), clock.instant()));
        }
        Path worktree = execution.workspace().worktree();
        String base = execution.lifecycle().baseSha();
        Optional<String> problem = branch.squash(worktree, base, tip);
        if (problem.isPresent()) {
            return holdSquash(execution, problem.orElseThrow());
        }
        String folded = branch.revision(worktree, "HEAD").orElseThrow();
        CherryPickBranch.Inspection inspection = branch.inspect(worktree, base, folded, CherryPickBranch.appliedSources(execution), ImmutableList.of(), Origin.CI);
        if (inspection.problem().isPresent() || inspection.commits().stream().anyMatch(commit -> commit.fixupOf().isPresent())) {
            branch.moveTo(worktree, folded, tip);
            return holdSquash(execution, "The folded branch is not one commit per source: " + inspection.problem().orElse("a fixup remained"));
        }
        try {
            publication.replaceBranch(execution, folded, tip);
        }
        catch (RuntimeException failure) {
            branch.moveTo(worktree, folded, tip);
            return holdSquash(execution, "Could not push the folded branch: " + failure.getMessage());
        }
        List<BranchCommit> fixups = execution.lifecycle().fixups();
        CherryPickExecution squashed = store.save(execution.withTip(folded, clock.instant()).withLifecycle(lifecycle -> lifecycle
                .withBranch(base, inspection.commits())
                .withSquash(Optional.of(new Squash(approval.approvedHead(), approval.approvedAt(), Optional.of(folded), fixups)))
                .inStage(Stage.MERGE), clock.instant()));
        return startCiRound(squashed);
    }

    private CherryPickExecution holdSquash(CherryPickExecution execution, String summary)
    {
        CherryPickExecution cleared = store.save(execution.withLifecycle(lifecycle -> lifecycle.withSquash(Optional.empty()), clock.instant()));
        return hold(cleared, Reason.SQUASH_FAILED, summary);
    }

    private Optional<CiAutofixRun> currentRun(CherryPickExecution execution)
    {
        return execution.lifecycle().currentCiRunId().map(id -> ci.get(CiAutofixRunId.valueOf(id)));
    }

    private static boolean green(CiAutofixRun run)
    {
        return run.status() == CiAutofixRun.Status.FINISHED && run.finishReason().filter(CiAutofixRun.FinishReason.GREEN::equals).isPresent();
    }

    private CherryPickExecution hold(CherryPickExecution execution, Reason reason, String summary)
    {
        log.info("Cherry-pick execution {} held: {}", execution.id(), summary);
        return store.save(execution.withLifecycle(lifecycle -> lifecycle.held(reason, bounded(summary), clock.instant()), clock.instant()));
    }

    private boolean due(CherryPickExecution execution, String check, Duration interval)
    {
        Instant now = clock.instant();
        Instant last = remoteChecks.get(execution.id() + ":" + check);
        if (last != null && last.plus(interval).isAfter(now)) {
            return false;
        }
        remoteChecks.put(execution.id() + ":" + check, now);
        return true;
    }

    private static String bounded(String summary)
    {
        String text = summary.isBlank() ? "Stopped" : summary;
        return text.length() <= MAX_SUMMARY_CHARACTERS ? text : text.substring(0, MAX_SUMMARY_CHARACTERS);
    }
}
