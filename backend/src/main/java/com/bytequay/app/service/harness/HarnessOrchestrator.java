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
package com.bytequay.app.service.harness;

import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.repository.PRStore;
import com.bytequay.app.service.harness.GitHubActionsProbe.FailedJob;
import com.bytequay.app.service.harness.GitHubActionsProbe.ProbeResult;
import com.bytequay.app.service.harness.HarnessLogParser.ParsedFailure;
import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import com.bytequay.app.service.harness.HarnessModels.Bucket;
import com.bytequay.app.service.harness.HarnessModels.Cycle;
import com.bytequay.app.service.harness.HarnessModels.CycleStatus;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.harness.HarnessModels.HandoffDto;
import com.bytequay.app.service.harness.HarnessModels.Phase;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import com.bytequay.app.service.harness.HarnessModels.WatchStatus;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PrUpdatedEvent;
import com.bytequay.app.service.workspaces.SyncRunStream;
import com.bytequay.app.service.workspaces.WorkspaceKnowledgeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.util.Objects.requireNonNull;

/** Durable watch/cycle phase machine. Model calls run inside this bounded owner. */
@Service
public class HarnessOrchestrator
{
    private static final Logger log = LoggerFactory.getLogger(HarnessOrchestrator.class);
    /**
     * The user asked to stop waiting for the board to settle. A suite that runs
     * for an hour should not hold back the fix for six checks that already
     * failed — this round works on what has failed so far and lets the rest run.
     */
    public static final String TRIGGER_FIX_NOW = "fix_now";
    /**
     * How many failed jobs make a board broken enough to act on before the rest
     * of it reports, and how long a board that has not settled is left alone
     * first. Above the count there is no wait at all; at the count, an hour;
     * below it, two — waiting longer than that has never yet turned a failing
     * board green.
     */
    private static final int WIDE_FAILURE_JOBS = 10;
    /** Far more than any range this drives; a bound, not a policy. */
    private static final int MAX_BRANCH_COMMITS = 2_000;
    private static final long WIDE_FAILURE_GRACE_MS = 3_600_000;
    private static final long ANY_FAILURE_GRACE_MS = 7_200_000;
    /** A turn's JSONL is unbounded; the log shows the head of it. */
    private static final int MAX_TRANSCRIPT = 64_000;
    /** Where a round leaves the full log of every job it found red, relative to
     * the worktree the agent runs in. Named in the agent's prompt. */
    static final String LOG_DIRECTORY = "logs";

    private final HarnessStore store;
    private final HarnessService service;
    private final GitHubActionsProbe probe;
    private final HarnessLogParser parser;
    private final HarnessRepairAgent agent;
    private final WorkspaceKnowledgeService knowledge;
    private final SyncRunStream stream;
    private final HarnessGitSafety gitSafety;
    private final GitRunner git;
    private final PRStore prs;
    private final ApplicationEventPublisher applicationEvents;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final Set<String> workers = ConcurrentHashMap.newKeySet();

    public HarnessOrchestrator(
            HarnessStore store,
            HarnessService service,
            GitHubActionsProbe probe,
            HarnessLogParser parser,
            HarnessRepairAgent agent,
            WorkspaceKnowledgeService knowledge,
            SyncRunStream stream,
            HarnessGitSafety gitSafety,
            GitRunner git,
            PRStore prs,
            ApplicationEventPublisher applicationEvents,
            ObjectMapper mapper,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.store = requireNonNull(store, "store is null");
        this.service = requireNonNull(service, "service is null");
        this.probe = requireNonNull(probe, "probe is null");
        this.parser = requireNonNull(parser, "parser is null");
        this.agent = requireNonNull(agent, "agent is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
        this.stream = requireNonNull(stream, "stream is null");
        this.gitSafety = requireNonNull(gitSafety, "gitSafety is null");
        this.git = requireNonNull(git, "git is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.applicationEvents = requireNonNull(applicationEvents, "applicationEvents is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedCycles()
    {
        recoverTerminalWatchMismatches();
        for (Cycle cycle : store.resumableCycles()) {
            if (cycle.status() == CycleStatus.QUEUED) {
                schedule(cycle.id());
                continue;
            }
            Watch watch = store.findWatch(cycle.watchId()).orElse(null);
            if (watch == null) {
                continue;
            }
            String recovery = "backend restarted during an active cycle";
            if (cycle.status() == CycleStatus.RUNNING && cycle.backupRef() != null) {
                if (!isAppOwnedWorktree(watch.localPath()) || cycle.originalHead() == null) {
                    recovery += "; durable backup recovery needs human inspection";
                }
                else {
                    try {
                        gitSafety.recoverInterrupted(
                                Path.of(watch.localPath()), cycle.backupRef(), cycle.originalHead());
                        recovery += "; local worktree restored from " + cycle.backupRef();
                    }
                    catch (RuntimeException failure) {
                        recovery += "; automatic backup recovery failed: "
                                + Optional.ofNullable(failure.getMessage())
                                        .orElse(failure.getClass().getSimpleName());
                    }
                }
            }
            long now = now();
            store.finishCycle(cycle.id(), CycleStatus.FAILED, cycle.phase(), cycle.costMilliUsd(),
                    cycle.backupRef(), cycle.netNeutralProofJson(), cycle.runStatusTail(),
                    recovery, now);
            HandoffDto handoff = new HandoffDto(
                    "restart_during_cycle", null, null,
                    recovery);
            store.updateWatchStatus(watch.id(), WatchStatus.NEEDS_ATTENTION, json(handoff), now);
            store.appendEvent(watch.id(), cycle.id(), cycle.phase(), "restart_recovery",
                    recovery, "{}", now);
            service.notifyNeedsAttention(watch, "CI harness stopped during a cycle", recovery);
        }
    }

    public Cycle requestRun(String watchId, String triggerKind)
    {
        return requestRun(watchId, triggerKind, null);
    }

    public Cycle requestRun(String watchId, String triggerKind, String steeringText)
    {
        Watch watch = store.findWatch(watchId)
                .orElseThrow(() -> new IllegalArgumentException("no harness watch: " + watchId));
        if (watch.status() == WatchStatus.BOOTSTRAP) {
            throw new IllegalStateException("harness watch is still bootstrapping");
        }
        if (watch.status() == WatchStatus.STOPPED) {
            throw new IllegalStateException("harness watch is stopped");
        }
        if (watch.spentMilliUsd() >= watch.budgetMilliUsd()) {
            HandoffDto handoff = new HandoffDto(
                    "budget_exhausted", null, null, "Raise the watch budget before retrying");
            store.updateWatchStatus(watch.id(), WatchStatus.NEEDS_ATTENTION, json(handoff), now());
            throw new IllegalStateException("harness watch budget is exhausted");
        }
        String steering = normalizeSteering(steeringText);
        Cycle cycle = store.startCycle(
                UUID.randomUUID().toString(), watch.id(), triggerKind, steering, now());
        // A round already in flight is joined, not duplicated — so steering that
        // came with this request has to be added to it or it is simply lost.
        if (steering != null && !steering.equals(cycle.steeringText())) {
            store.appendCycleSteering(cycle.id(), steering, now());
        }
        schedule(cycle.id());
        return cycle;
    }

    private void schedule(String cycleId)
    {
        if (!workers.add(cycleId)) {
            return;
        }
        executor.execute(() -> {
            try {
                runCycle(cycleId);
            }
            finally {
                workers.remove(cycleId);
            }
        });
    }

    void runCycle(String cycleId)
    {
        Cycle cycle = store.findCycle(cycleId).orElseThrow();
        Watch watch = store.findWatch(cycle.watchId()).orElseThrow();
        if (watch.status() == WatchStatus.STOPPED) {
            return;
        }
        boolean awaitingPush = watch.status() == WatchStatus.HANDOFF;
        boolean alreadyGreen = watch.status() == WatchStatus.GREEN;
        String previouslyObservedHead = watch.headSha();
        long now = now();
        try {
            ensureActive(watch, cycle);
            if (!store.updateWatchStatusIfNotStopped(
                    watch.id(), WatchStatus.RUNNING, watch.handoffJson(), now)) {
                throw new CycleCancelledException();
            }
            phase(watch, cycle, Phase.PROBE, "Probing the latest GitHub Actions checks");
            BootstrapProfile profile = service.profile(watch.bootstrapProfileJson());
            ProbeResult result = probe.probe(watch, profile, fixupsBySupersededSha(watch));
            if (!store.updateWatchHeadAndPoll(
                    watch.id(), result.headSha(), result.branch(), now())) {
                throw new CycleCancelledException();
            }
            if (!store.updateCycleProgress(cycle.id(), CycleStatus.RUNNING, Phase.PROBE,
                    result.headSha(), result.failedJobs().stream()
                            .map(FailedJob::runId)
                            .filter(value -> value != null && !value.isBlank())
                            .findFirst().orElse(null), result.runStatusTail(), now())) {
                throw new CycleCancelledException();
            }
            watch = store.findWatch(watch.id()).orElseThrow();

            if (alreadyGreen && result.green()
                    && Objects.equals(previouslyObservedHead, result.headSha())) {
                finishStillGreen(watch, cycle, result.runStatusTail());
                return;
            }
            if (awaitingPush && Objects.equals(previouslyObservedHead, result.headSha())) {
                finishAwaitingPush(watch, cycle, result.runStatusTail());
                return;
            }

            // The fork's target branch moves under a run that takes days. Ahead of
            // everything else, including a green board: a branch that will not
            // merge is not done, and rebasing rewrites every sha so any verdict
            // read before it is about a tree that no longer exists.
            if (result.conflicted()) {
                runRebaseRound(watch, cycle, result);
                return;
            }
            // Normally a half-finished board is no board at all: a check still
            // running may yet fail, and fixing early wastes a push. The one
            // exception is a round the user asked for by name, where whatever
            // has already failed is evidence enough to work from.
            boolean fixWhatFailedSoFar = !result.failedJobs().isEmpty()
                    && (TRIGGER_FIX_NOW.equals(cycle.triggerKind())
                            || failingTooWidelyOrForTooLong(watch, result));
            if (result.pending() && !fixWhatFailedSoFar) {
                finishNoChange(watch, cycle, "CI is still running", result.runStatusTail());
                return;
            }
            if (result.green()) {
                finishGreen(watch, cycle, result.runStatusTail());
                return;
            }
            if (result.failedJobs().isEmpty()) {
                handoff(watch, cycle, null, null, null,
                        "No actionable log was available for the failed or cancelled checks");
                return;
            }

            phase(watch, cycle, Phase.PARSE, "Parsing failed job logs into stable fingerprints");
            List<Failure> failures = persistFailures(cycle, result, profile);
            phase(watch, cycle, Phase.CLASSIFY, "Setting aside infrastructure and duplicates");
            List<Failure> actionable = triage(failures);
            if (actionable.isEmpty()) {
                handoff(watch, cycle, null, null, null,
                        "All observed failures were deferred or need human classification");
                return;
            }
            runRound(watch, cycle, result, actionable);
        }
        catch (CycleCancelledException cancelled) {
            log.info("CI harness cycle {} observed cancellation", cycle.id());
        }
        catch (RuntimeException failure) {
            failCycle(watch, cycle, failure);
        }
    }

    /**
     * Each commit on the branch that the very next commit is a {@code fixup!}
     * for, mapped to that fixup. The per-commit CI job judges commits as they
     * stand, but a pick and its fixup become one commit when the branch is
     * autosquashed, so a pick that only builds once its fixup lands is not
     * broken — and the probe uses this to tell the two cases apart.
     *
     * <p>Read from the harness's own checkout, which is where the fixups were
     * committed and pushed from. Anything unreadable answers "no fixups", so
     * the strict reading — every red check is real — is the fallback.
     */
    Map<String, String> fixupsBySupersededSha(Watch watch)
    {
        if (watch.localPath() == null || watch.branch() == null) {
            return Map.of();
        }
        List<GitRunner.CommitEntry> commits;
        try {
            commits = git.listCommits(
                    Path.of(watch.localPath()), watch.branch(), MAX_BRANCH_COMMITS);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
        catch (IOException | RuntimeException e) {
            log.warn("CI harness could not read {} for fixup pairs: {}",
                    watch.branch(), e.getMessage());
            return Map.of();
        }
        // Newest first, so a commit's parent is the next element along.
        Map<String, String> fixups = new HashMap<>();
        for (int index = 0; index + 1 < commits.size(); index++) {
            GitRunner.CommitEntry fixup = commits.get(index);
            GitRunner.CommitEntry target = commits.get(index + 1);
            if (fixup.subject().equals("fixup! " + target.subject())) {
                fixups.put(target.sha(), fixup.sha());
            }
        }
        return Map.copyOf(fixups);
    }

    /**
     * Whether a board that has not settled already carries enough to work from.
     *
     * <p>Waiting is normally right: a check still running may yet fail, and
     * fixing early wastes a push. But a board failing this widely is not going
     * to be redeemed by the rest of it, and a board that has sat on the same
     * head for hours is not telling us anything new either. The ladder is
     * deliberately monotone — the wider the failure, the shorter the wait.
     */
    private boolean failingTooWidelyOrForTooLong(Watch watch, ProbeResult result)
    {
        int failures = result.failedJobs().size();
        if (failures > WIDE_FAILURE_JOBS) {
            return true;
        }
        if (result.headSha() == null) {
            return false;
        }
        long firstSeen = store.headFirstSeenAtMs(watch.id(), result.headSha(), now());
        // An unknown first sighting is this one: a head nobody has recorded yet
        // has been pending for no time at all.
        long pendingMs = firstSeen <= 0 ? 0 : now() - firstSeen;
        return failures >= WIDE_FAILURE_JOBS
                ? pendingMs >= WIDE_FAILURE_GRACE_MS
                : pendingMs >= ANY_FAILURE_GRACE_MS;
    }

    private List<Failure> persistFailures(
            Cycle cycle, ProbeResult result, BootstrapProfile profile)
    {
        List<Failure> failures = new ArrayList<>();
        for (FailedJob job : result.failedJobs()) {
            for (ParsedFailure parsed : parser.parse(
                    job.runId(), job.checkRunId(), job.jobName(), job.log(), profile)) {
                long now = now();
                Bucket initial = job.infra() ? Bucket.INFRA : Bucket.UNKNOWN;
                failures.add(store.insertFailure(new Failure(
                        UUID.randomUUID().toString(), cycle.id(), parsed.runId(), parsed.checkRunId(),
                        parsed.jobName(), parsed.module(), parsed.testClass(), parsed.testMethod(),
                        parsed.signature(), parsed.logExcerpt(), initial.wire(), null,
                        FailureStatus.OBSERVED, null, null, null, null, now, now)));
            }
        }
        return List.copyOf(failures);
    }

    /**
     * Deferral and de-duplication, which is all that is left of triage: an
     * infrastructure job is not the agent's to fix, and matrix jobs report one
     * root failure many times over. Everything else goes to the agent as it is —
     * there is no rule table to match against and no bucket to route on, because
     * reading the log is the agent's job now.
     */
    private List<Failure> triage(List<Failure> failures)
    {
        if (failures.isEmpty()) {
            return List.of();
        }
        List<Failure> actionable = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Failure failure : failures) {
            boolean deferred = failure.bucket() == Bucket.INFRA
                    || !seen.add(signatureKey(failure.signature()));
            store.updateFailure(failure.id(), failure.bucketLabel(), null,
                    deferred ? FailureStatus.DEFERRED : FailureStatus.OBSERVED,
                    null, null, null, null, now());
            if (!deferred) {
                actionable.add(failure);
            }
        }
        return List.copyOf(actionable);
    }

    /**
     * One round: hand the agent everything that is red, let it decide what to
     * take on, then push what it committed and wait for CI to judge it.
     *
     * <p>There is no confidence gate, no verification step and no fixup batch
     * here any more. The agent validates its own work and CI is the authority;
     * the program's remaining job is the budget, the push, and refusing to
     * publish a worktree its author never called finished.
     */
    private void runRound(
            Watch watch, Cycle cycle, ProbeResult probeResult, List<Failure> failures)
    {
        Path root = Path.of(requireNonNull(watch.localPath(), "watch local path is null"));
        if (!isAppOwnedWorktree(watch.localPath())) {
            handoff(watch, cycle, null, null, null,
                    "Automatic fixes require an app-owned worktree");
            return;
        }
        long spent = store.findWatch(watch.id())
                .map(Watch::spentMilliUsd)
                .orElse(watch.spentMilliUsd());
        long remaining = Math.max(0, watch.budgetMilliUsd() - spent);
        if (remaining < 100) {
            // A park, not a failure: the budget is the one hard stop, and it is
            // one the user can raise to carry on.
            handoff(watch, cycle, null, null, null,
                    "The run's budget is spent. Raise it to carry on, or stop here.");
            return;
        }
        ensureClean(root);
        writeJobLogs(root, probeResult);
        phase(watch, cycle, Phase.FIX,
                "Handing " + failures.size() + " failure(s) to the agent");
        HarnessRepairAgent.Outcome outcome = agent.fix(
                root, watch.workspaceId(), failures, remaining,
                watch.agentSessionId(), cycle.steeringText(),
                line -> stream.publish(watch.id(), line));
        store.addWatchCost(watch.id(), outcome.costMilliUsd(), now());
        store.addCycleCost(cycle.id(), outcome.costMilliUsd(), now());
        // One session for the whole run — the next round resumes this one.
        store.updateWatchAgentSession(watch.id(), outcome.sessionId(), now());
        recordTranscript(watch, cycle, outcome);
        store.appendEvent(watch.id(), cycle.id(), Phase.FIX,
                outcome.committed() ? "agent_committed" : "agent_finished",
                outcome.detail(), null, now());
        remember(watch, cycle, outcome);

        if (!outcome.committed()) {
            handoff(watch, cycle, null, null, null, outcome.nothing()
                    ? "The agent judged nothing in this round to be its work: " + outcome.detail()
                    : outcome.detail());
            return;
        }
        try {
            if (git.hasUncommittedChanges(root)) {
                handoff(watch, cycle, null, null, null,
                        "The agent reported it was finished but left uncommitted changes");
                return;
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("unable to inspect harness worktree", e);
        }
        ensureActive(watch, cycle);
        pushRound(watch, cycle, probeResult, root, outcome);
    }

    /**
     * The base moved and the branch no longer merges. The agent rebases onto the
     * updated target and repairs the conflicts, each resolution landing as a
     * {@code fixup!} under the same one-per-pick rule as everything else.
     *
     * <p>Its own round on purpose: the rebase rewrites every sha, so whatever CI
     * last said describes a branch that no longer exists. Push, and let the next
     * run judge the new tree.
     */
    private void runRebaseRound(Watch watch, Cycle cycle, ProbeResult probeResult)
    {
        Path root = Path.of(requireNonNull(watch.localPath(), "watch local path is null"));
        if (!isAppOwnedWorktree(watch.localPath())) {
            handoff(watch, cycle, null, null, null,
                    "The pull request no longer merges into its base, and rebasing "
                            + "requires an app-owned worktree");
            return;
        }
        long spent = store.findWatch(watch.id())
                .map(Watch::spentMilliUsd)
                .orElse(watch.spentMilliUsd());
        long remaining = Math.max(0, watch.budgetMilliUsd() - spent);
        if (remaining < 100) {
            handoff(watch, cycle, null, null, null,
                    "The pull request no longer merges into its base and the run's budget "
                            + "is spent. Raise it to carry on, or stop here.");
            return;
        }
        ensureClean(root);
        phase(watch, cycle, Phase.FIX, "The base moved — rebasing onto it");
        HarnessRepairAgent.Outcome outcome = agent.rebaseOntoBase(
                root, watch.workspaceId(), remaining, watch.agentSessionId(),
                line -> stream.publish(watch.id(), line));
        store.addWatchCost(watch.id(), outcome.costMilliUsd(), now());
        store.addCycleCost(cycle.id(), outcome.costMilliUsd(), now());
        store.updateWatchAgentSession(watch.id(), outcome.sessionId(), now());
        store.appendEvent(watch.id(), cycle.id(), Phase.FIX, "rebased",
                outcome.detail(), null, now());
        if (!outcome.committed()) {
            handoff(watch, cycle, null, null, null, outcome.detail());
            return;
        }
        try {
            if (git.hasUncommittedChanges(root)) {
                handoff(watch, cycle, null, null, null,
                        "The rebase was reported finished but left uncommitted changes");
                return;
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("unable to inspect harness worktree", e);
        }
        ensureActive(watch, cycle);
        pushRound(watch, cycle, probeResult, root, outcome);
    }

    /**
     * Persists what the agent learned from a fix CI has now confirmed. The agent
     * authors it; the program writes it, like every other side effect here.
     *
     * <p>Never in the critical path: the round's commits are already made, and a
     * knowledge write that fails costs a future shortcut, nothing more.
     */
    private void remember(Watch watch, Cycle cycle, HarnessRepairAgent.Outcome outcome)
    {
        for (HarnessRepairAgent.Learned entry : outcome.learned()) {
            try {
                knowledge.saveKnowledge(
                        watch.workspaceId(), null, entry.title(), entry.body(),
                        List.of("ci-fix"),
                        Map.of("harnessWatchId", watch.id(), "harnessCycleId", cycle.id()));
                store.appendEvent(watch.id(), cycle.id(), Phase.FIX, "learned",
                        entry.title(), null, now());
            }
            catch (RuntimeException notLearned) {
                log.warn("CI harness cycle {} could not record what it learned: {}",
                        cycle.id(), notLearned.getMessage());
            }
        }
    }

    /**
     * The one irreversible step in the loop, so the program owns it rather than
     * the agent: an explicit named lease against the head CI just ran on, and
     * only ever the pull request's own branch.
     */
    private void pushRound(
            Watch watch, Cycle cycle, ProbeResult probeResult, Path root,
            HarnessRepairAgent.Outcome outcome)
    {
        String branch = requireNonNull(probeResult.branch(), "PR head branch is null");
        phase(watch, cycle, Phase.COMMIT, "Publishing the round to " + branch);
        GitRunner.GitResult push;
        try {
            push = git.pushRewrittenBranch(root, branch, probeResult.headSha());
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("unable to publish the harness round", e);
        }
        if (push.exitCode() != 0) {
            // A refused lease means the branch moved under us — someone else
            // pushed. Never retry past a lease; that is what it is there for.
            handoff(watch, cycle, null, null, null,
                    "Publishing the round was refused: " + push.stderr().strip());
            return;
        }
        long cost = store.findCycle(cycle.id()).map(Cycle::costMilliUsd).orElse(0L);
        HandoffDto handoff = new HandoffDto(
                "pushed_waiting_for_ci", null, null,
                outcome.detail() + " — pushed to " + branch + "; waiting for CI to judge it.");
        if (!store.finishHandoff(
                cycle.id(), watch.id(), cost, null, null,
                probeResult.runStatusTail(), json(handoff), now())) {
            throw new CycleCancelledException();
        }
        store.appendEvent(watch.id(), cycle.id(), Phase.DONE, "pushed",
                "Pushed the round; waiting for the next CI run", null, now());
        timeline(watch, Phase.DONE, "pushed",
                "Pushed the round; waiting for the next CI run", probeResult.headSha());
    }

    /**
     * Drops each failed job's whole log into {@code logs/} in the worktree, so
     * an agent that finds the excerpt too narrow can read the rest with the
     * tools it already has. It has no other route to them: its CLI seat is
     * wired to no MCP server, and the logs live in this app's database.
     *
     * <p>The directory is excluded rather than cleaned up afterwards. Excluding
     * it is what keeps a {@code git add -A} from sweeping it into a fixup —
     * deleting it after the round would leave that window open — and the
     * worktree is app-owned and thrown away with the run anyway.
     */
    void writeJobLogs(Path root, ProbeResult probeResult)
    {
        Path directory = root.resolve(LOG_DIRECTORY);
        try {
            excludeLogDirectory(root);
            Files.createDirectories(directory);
            for (FailedJob job : probeResult.failedJobs()) {
                if (job.log() == null || job.log().isBlank()) {
                    continue;
                }
                Files.writeString(directory.resolve(logFileName(job.jobName())), job.log());
            }
        }
        // The excerpts are what the round runs on; the full logs are a courtesy,
        // so every way of failing to write them is swallowed — an unwritable
        // path and an unreadable git dir included. Losing them must never cost a
        // cycle that would otherwise have fixed something.
        catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("CI harness could not write job logs to {}", directory, e);
        }
    }

    /**
     * Git reads {@code info/exclude} from the common directory only — a linked
     * worktree's own copy is never consulted — so the entry has to go there,
     * which is what {@link GitRunner#gitInfoExcludePath} resolves. It is
     * anchored, so it hides this one directory at the checkout root and no
     * {@code logs/} the project itself may carry deeper in the tree.
     */
    private void excludeLogDirectory(Path root)
            throws IOException, InterruptedException
    {
        Path exclude = git.gitInfoExcludePath(root);
        String entry = "/" + LOG_DIRECTORY + "/";
        if (Files.exists(exclude) && Files.readAllLines(exclude).contains(entry)) {
            return;
        }
        Files.createDirectories(exclude.getParent());
        Files.writeString(exclude, entry + System.lineSeparator(), CREATE, APPEND);
    }

    /** Predictable enough that the agent can find a job's log by its name. */
    static String logFileName(String jobName)
    {
        String cleaned = (jobName == null ? "job" : jobName)
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return (cleaned.isBlank() ? "job" : cleaned) + ".log";
    }

    private void ensureClean(Path root)
    {
        try {
            if (git.hasUncommittedChanges(root)) {
                throw new IllegalStateException(
                        "Worktree has unrelated changes; refusing to apply the proposal");
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("unable to inspect harness worktree", e);
        }
    }

    private void phase(Watch watch, Cycle cycle, Phase phase, String message)
    {
        long now = now();
        if (!store.updateCycleProgress(cycle.id(), CycleStatus.RUNNING, phase,
                null, null, null, now)) {
            throw new CycleCancelledException();
        }
        store.appendEvent(watch.id(), cycle.id(), phase, "phase_started", message, "{}", now);
    }

    private void finishNoChange(Watch watch, Cycle cycle, String message, String tail)
    {
        Cycle current = current(cycle);
        if (!store.finishCycleIfLive(cycle.id(), CycleStatus.NO_CHANGE, Phase.PROBE,
                current.costMilliUsd(), null, null, tail, null, now())) {
            throw new CycleCancelledException();
        }
        if (!store.updateWatchStatusIfNotStopped(
                watch.id(), WatchStatus.WATCHING, null, now())) {
            return;
        }
        store.appendEvent(watch.id(), cycle.id(), Phase.PROBE, "waiting", message, "{}", now());
    }

    private void finishAwaitingPush(Watch watch, Cycle cycle, String tail)
    {
        Cycle current = current(cycle);
        if (!store.finishCycleIfLive(cycle.id(), CycleStatus.NO_CHANGE, Phase.PROBE,
                current.costMilliUsd(), null, null, tail, null, now())) {
            throw new CycleCancelledException();
        }
        if (!store.updateWatchStatusIfNotStopped(
                watch.id(), WatchStatus.HANDOFF, watch.handoffJson(), now())) {
            return;
        }
        store.appendEvent(watch.id(), cycle.id(), Phase.PROBE, "awaiting_push",
                "Verified local fix has not been pushed yet", "{}", now());
    }

    private void finishStillGreen(Watch watch, Cycle cycle, String tail)
    {
        Cycle current = current(cycle);
        if (!store.finishCycleIfLive(cycle.id(), CycleStatus.NO_CHANGE, Phase.PROBE,
                current.costMilliUsd(), null, null, tail, null, now())) {
            throw new CycleCancelledException();
        }
        store.updateWatchStatusIfNotStopped(watch.id(), WatchStatus.GREEN, null, now());
    }

    private void finishGreen(Watch watch, Cycle cycle, String tail)
    {
        Cycle current = current(cycle);
        if (!store.finishCycleIfLive(cycle.id(), CycleStatus.GREEN, Phase.DONE,
                current.costMilliUsd(), null, null, tail, null, now())) {
            throw new CycleCancelledException();
        }
        if (!store.updateWatchStatusIfNotStopped(
                watch.id(), WatchStatus.GREEN, null, now())) {
            return;
        }
        store.appendEvent(watch.id(), cycle.id(), Phase.DONE, "all_green",
                "All non-aggregator CI checks are green", "{}", now());
        timeline(watch, Phase.DONE, "green", "All non-aggregator CI checks are green",
                watch.headSha());
    }

    private void recordEscalation(Watch watch, Cycle cycle, Failure failure, String reason)
    {
        Failure latest = store.listFailuresForCycle(failure.cycleId()).stream()
                .filter(value -> value.id().equals(failure.id()))
                .findFirst()
                .orElse(failure);
        store.updateFailure(latest.id(), latest.bucketLabel(), latest.ruleId(),
                FailureStatus.ESCALATED, latest.targetSubject(), latest.diagnosisJson(),
                latest.fixJson(), latest.verificationJson(), now());
        timeline(watch, current(cycle).phase(), "escalated", reason, watch.headSha());
    }

    /**
     * The round's turn, as the log can read it back. Without this a round is a
     * black box between "handing over N failures" and a one-line verdict — the
     * picks show their whole conversation and this showed none of it.
     */
    private void recordTranscript(Watch watch, Cycle cycle, HarnessRepairAgent.Outcome outcome)
    {
        String transcript = outcome.transcript();
        if (transcript == null || transcript.isBlank()) {
            return;
        }
        store.appendEvent(watch.id(), cycle.id(), Phase.FIX, "agent_log",
                transcript.length() <= MAX_TRANSCRIPT
                        ? transcript : transcript.substring(0, MAX_TRANSCRIPT),
                "{}", now());
    }

    private void handoff(
            Watch watch, Cycle cycle, String failureId, String backupRef,
            String proofJson, String reason)
    {
        Cycle current = current(cycle);
        HandoffDto handoff = new HandoffDto("needs_attention", failureId, null, reason);
        if (!store.finishCycleIfLive(
                cycle.id(), CycleStatus.HANDOFF, current.phase(), current.costMilliUsd(),
                backupRef, proofJson, current.runStatusTail(), null, now())) {
            throw new CycleCancelledException();
        }
        if (!store.updateWatchStatusIfNotStopped(
                watch.id(), WatchStatus.NEEDS_ATTENTION, json(handoff), now())) {
            return;
        }
        store.appendEvent(watch.id(), cycle.id(), current.phase(), "needs_attention",
                "Harness needs human attention", json(Map.of("reason", reason)), now());
        service.notifyNeedsAttention(store.findWatch(watch.id()).orElse(watch),
                "CI harness needs attention", reason);
    }

    private void failCycle(Watch watch, Cycle cycle, RuntimeException failure)
    {
        log.warn("CI harness cycle {} failed", cycle.id(), failure);
        String reason = Optional.ofNullable(failure.getMessage())
                .orElse(failure.getClass().getSimpleName());
        Cycle current = current(cycle);
        HandoffDto handoff = new HandoffDto("cycle_failed", null, null, reason);
        if (!store.finishCycleIfLive(
                cycle.id(), CycleStatus.FAILED, current.phase(), current.costMilliUsd(),
                current.backupRef(), current.netNeutralProofJson(), current.runStatusTail(), reason, now())) {
            return;
        }
        if (!store.updateWatchStatusIfNotStopped(
                watch.id(), WatchStatus.NEEDS_ATTENTION, json(handoff), now())) {
            return;
        }
        store.appendEvent(watch.id(), cycle.id(), current.phase(), "cycle_failed",
                "Cycle failed closed", json(Map.of("error", reason)), now());
        service.notifyNeedsAttention(store.findWatch(watch.id()).orElse(watch),
                "CI harness cycle failed", reason);
        timeline(watch, current.phase(), "escalated", reason, watch.headSha());
    }

    private Cycle current(Cycle cycle)
    {
        return store.findCycle(cycle.id()).orElse(cycle);
    }

    /** Repairs the old two-write crash boundary and any future unexpected
     * terminal-cycle/running-watch mismatch before polling resumes. */
    private void recoverTerminalWatchMismatches()
    {
        for (Watch watch : store.watchesInStatus(WatchStatus.RUNNING)) {
            if (store.findLiveCycle(watch.id()).isPresent()) {
                continue;
            }
            Cycle terminal = store.listCycles(watch.id(), 1).stream().findFirst().orElse(null);
            if (terminal == null) {
                continue;
            }
            WatchStatus recovered;
            HandoffDto handoff = null;
            if (terminal.status() == CycleStatus.HANDOFF
                    && terminal.backupRef() != null
                    && terminal.netNeutralProofJson() != null
                    && watch.localPath() != null) {
                recovered = WatchStatus.HANDOFF;
                String command = watch.branch() == null ? null
                        : handoffCommand(Path.of(watch.localPath()), watch.branch());
                handoff = new HandoffDto(
                        "recovered_verified_local_fix", null, command,
                        "Recovered a verified local handoff after backend restart. The harness did not push.");
            }
            else if (terminal.status() == CycleStatus.GREEN) {
                recovered = WatchStatus.GREEN;
            }
            else if (terminal.status() == CycleStatus.NO_CHANGE && watch.handoffJson() != null) {
                recovered = WatchStatus.HANDOFF;
            }
            else if (terminal.status() == CycleStatus.NO_CHANGE) {
                recovered = WatchStatus.WATCHING;
            }
            else {
                recovered = WatchStatus.NEEDS_ATTENTION;
                handoff = new HandoffDto(
                        "recovered_terminal_mismatch", null, null,
                        "Recovered a terminal cycle whose watch state was interrupted");
            }
            String handoffJson = switch (recovered) {
                case HANDOFF -> handoff == null ? watch.handoffJson() : json(handoff);
                case NEEDS_ATTENTION -> json(handoff);
                default -> null;
            };
            store.updateWatchStatus(
                    watch.id(), recovered, handoffJson, now());
            store.appendEvent(watch.id(), terminal.id(), terminal.phase(),
                    "terminal_state_recovered", "Recovered watch state after backend restart", "{}", now());
        }
    }

    private void ensureActive(Watch watch, Cycle cycle)
    {
        if (!store.isCycleActive(watch.id(), cycle.id())) {
            throw new CycleCancelledException();
        }
    }

    private static final class CycleCancelledException
            extends RuntimeException
    {
    }

    private void timeline(Watch watch, Phase phase, String status, String message, String sha)
    {
        String prId = watch.localPrId();
        if (prId == null || prs.findById(prId).isEmpty()) {
            return;
        }
        PRTimelineEntry entry = new PRTimelineEntry(
                UUID.randomUUID().toString(), prId, PRTimelineEntry.TYPE_CI, "ci-harness",
                true, null, Instant.now(), json(Map.of(
                        "message", message,
                        "phase", phase.wire(),
                        "status", status,
                        "sha", sha == null ? "" : sha)), null);
        prs.addEvent(entry);
        applicationEvents.publishEvent(new PrUpdatedEvent(prId));
    }

    private <T> T decode(String json, Class<T> type)
    {
        try {
            return mapper.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid persisted harness JSON", e);
        }
    }

    private String json(Object value)
    {
        return service.json(value);
    }

    private static boolean isAppOwnedWorktree(String value)
    {
        if (value == null) {
            return false;
        }
        String normalized = Path.of(value).toAbsolutePath().normalize().toString().replace('\\', '/');
        return normalized.contains(".bytequay-worktrees/cherry-pick/")
                || normalized.contains(".bytequay-worktrees/upstream-cherry-pick/")
                || normalized.contains(".bytequay-worktrees/ci-harness/");
    }

    private static String normalizeSteering(String value)
    {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 4_000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "steering text must be at most 4000 characters");
        }
        if (normalized.chars().anyMatch(character -> character == 0
                || (Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t'))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "steering text contains unsupported control characters");
        }
        return normalized;
    }

    static List<String> unrelatedSignatures(List<Failure> failures, Failure current)
    {
        String currentKey = signatureKey(current.signature());
        return failures.stream()
                .filter(value -> !signatureKey(value.signature()).equals(currentKey))
                .map(Failure::signature)
                .distinct()
                .toList();
    }

    private static String signatureKey(String signature)
    {
        return signature == null ? "" : signature.strip().toLowerCase(Locale.ROOT);
    }

    static String handoffCommand(Path root, String branch)
    {
        if (branch == null || !branch.matches("[A-Za-z0-9._/-]+")
                || branch.startsWith("-") || branch.contains("..") || branch.endsWith("/")) {
            throw new IllegalArgumentException("invalid handoff branch");
        }
        String checkout = root.toAbsolutePath().normalize().toString();
        return "git -C " + shellQuote(checkout)
                + " push --force-with-lease origin " + shellQuote("HEAD:" + branch);
    }

    private static String shellQuote(String value)
    {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static long now()
    {
        return Instant.now().toEpochMilli();
    }
}
