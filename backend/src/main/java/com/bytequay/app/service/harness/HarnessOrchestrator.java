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
import com.bytequay.app.service.harness.HarnessClassifier.Classification;
import com.bytequay.app.service.harness.HarnessDiagnosisService.DiagnosisOutcome;
import com.bytequay.app.service.harness.HarnessGitSafety.FixupBatch;
import com.bytequay.app.service.harness.HarnessGitSafety.SafetyResult;
import com.bytequay.app.service.harness.HarnessLogParser.ParsedFailure;
import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import com.bytequay.app.service.harness.HarnessModels.Bucket;
import com.bytequay.app.service.harness.HarnessModels.Cycle;
import com.bytequay.app.service.harness.HarnessModels.CycleStatus;
import com.bytequay.app.service.harness.HarnessModels.Diagnosis;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.harness.HarnessModels.FixResult;
import com.bytequay.app.service.harness.HarnessModels.HandoffDto;
import com.bytequay.app.service.harness.HarnessModels.Phase;
import com.bytequay.app.service.harness.HarnessModels.Rule;
import com.bytequay.app.service.harness.HarnessModels.RuleStatus;
import com.bytequay.app.service.harness.HarnessModels.VerificationResult;
import com.bytequay.app.service.harness.HarnessModels.VerifiedFix;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import com.bytequay.app.service.harness.HarnessModels.WatchStatus;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PrUpdatedEvent;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
import static java.util.Objects.requireNonNull;

/** Durable watch/cycle phase machine. Model calls run inside this bounded owner. */
@Service
public class HarnessOrchestrator
{
    private static final Logger log = LoggerFactory.getLogger(HarnessOrchestrator.class);
    private static final double CONFIDENCE_THRESHOLD = 0.75;

    private final HarnessStore store;
    private final HarnessService service;
    private final GitHubActionsProbe probe;
    private final HarnessLogParser parser;
    private final HarnessClassifier classifier;
    private final HarnessDiagnosisService diagnosis;
    private final HarnessFixApplier applier;
    private final HarnessVerifier verifier;
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
            HarnessClassifier classifier,
            HarnessDiagnosisService diagnosis,
            HarnessFixApplier applier,
            HarnessVerifier verifier,
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
        this.classifier = requireNonNull(classifier, "classifier is null");
        this.diagnosis = requireNonNull(diagnosis, "diagnosis is null");
        this.applier = requireNonNull(applier, "applier is null");
        this.verifier = requireNonNull(verifier, "verifier is null");
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
            ProbeResult result = probe.probe(watch, profile);
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

            if (result.pending()) {
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
            phase(watch, cycle, Phase.CLASSIFY, "Classifying failures against approved rules");
            List<Failure> actionable = classify(watch, failures);
            if (actionable.isEmpty()) {
                handoff(watch, cycle, null, null, null,
                        "All observed failures were deferred or need human classification");
                return;
            }
            handleActionable(watch, cycle, result, profile, failures, actionable);
        }
        catch (CycleCancelledException cancelled) {
            log.info("CI harness cycle {} observed cancellation", cycle.id());
        }
        catch (RuntimeException failure) {
            failCycle(watch, cycle, failure);
        }
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

    private List<Failure> classify(Watch watch, List<Failure> failures)
    {
        if (failures.isEmpty()) {
            return List.of();
        }
        List<String> actionableIds = new ArrayList<>();
        Set<String> actionableSignatures = new HashSet<>();
        for (Failure failure : failures) {
            if (failure.bucket() == Bucket.INFRA) {
                store.updateFailure(failure.id(), failure.bucketLabel(), null,
                        FailureStatus.DEFERRED, null, null, null, null, now());
                continue;
            }
            Classification result = classifier.classify(
                    watch.workspaceId(), watch.owner(), watch.repo(), failure.module(),
                    failure.signature(), failure.logExcerpt(), now());
            Rule rule = result.rule();
            FailureStatus status = result.bucket() == Bucket.INFRA
                    || result.bucket() == Bucket.FLAKE
                    || (rule != null && "defer".equals(rule.binding()))
                    ? FailureStatus.DEFERRED : FailureStatus.OBSERVED;
            String bucketLabel = rule == null ? result.bucket().wire() : rule.bucketLabel();
            if (status != FailureStatus.DEFERRED
                    && !actionableSignatures.add(signatureKey(failure.signature()))) {
                // Matrix jobs frequently report the same root failure. Keep
                // every observed row, but execute the signature only once.
                status = FailureStatus.DEFERRED;
            }
            store.updateFailure(failure.id(), bucketLabel, rule == null ? null : rule.id(),
                    status, null, null, null, null, now());
            if (status != FailureStatus.DEFERRED) {
                actionableIds.add(failure.id());
            }
        }
        List<Failure> persisted = store.listFailuresForCycle(failures.getFirst().cycleId());
        return failures.stream()
                .filter(failure -> actionableIds.contains(failure.id()))
                .map(failure -> persisted.stream()
                        .filter(value -> value.id().equals(failure.id()))
                        .findFirst()
                        .orElse(failure))
                .toList();
    }

    private void handleActionable(
            Watch watch, Cycle cycle, ProbeResult probeResult, BootstrapProfile profile,
            List<Failure> allFailures, List<Failure> actionable)
    {
        Path root = Path.of(requireNonNull(watch.localPath(), "watch local path is null"));
        phase(watch, cycle, Phase.FIX,
                "Preparing " + actionable.size() + " bounded fix proposal(s)");
        FixupBatch batch = null;
        FixResult uncommitted = null;
        SafetyResult safety = null;
        boolean handoffPersisted = false;
        List<String> candidateIds = new ArrayList<>();
        List<String> escalatedIds = new ArrayList<>();
        List<String> escalationReasons = new ArrayList<>();
        int completed = 0;
        try {
            for (Failure observed : actionable) {
                Failure failure = observed;
                Rule rule = failure.ruleId() == null
                        ? null : store.findRule(failure.ruleId()).orElse(null);
                Diagnosis proposed;
                boolean learned = rule == null || rule.status() != RuleStatus.ACTIVE;
                if (rule != null && rule.binding().startsWith("recipe:")) {
                    try {
                        proposed = validatedRecipe(rule);
                    }
                    catch (RuntimeException invalidRecipe) {
                        String reason = "Approved learned recipe is missing or invalid: "
                                + invalidRecipe.getMessage();
                        recordEscalation(watch, cycle, failure, reason);
                        escalatedIds.add(failure.id());
                        escalationReasons.add(reason);
                        continue;
                    }
                }
                else {
                    long spent = store.findWatch(watch.id())
                            .map(Watch::spentMilliUsd)
                            .orElse(watch.spentMilliUsd());
                    long remaining = Math.max(0, watch.budgetMilliUsd() - spent);
                    if (remaining < 100) {
                        String reason = "Watch budget is too low for diagnosis";
                        recordEscalation(watch, cycle, failure, reason);
                        escalatedIds.add(failure.id());
                        escalationReasons.add(reason);
                        continue;
                    }
                    List<String> unrelated = unrelatedSignatures(allFailures, failure);
                    store.updateFailure(failure.id(), failure.bucketLabel(), failure.ruleId(),
                            FailureStatus.DIAGNOSING, null, null, null, null, now());
                    DiagnosisOutcome outcome = diagnosis.diagnose(
                            failure, root, probeResult.baseSha(), unrelated, remaining,
                            watch.workspaceId(), cycle.steeringText());
                    store.addWatchCost(watch.id(), outcome.costMilliUsd(), now());
                    store.addCycleCost(cycle.id(), outcome.costMilliUsd(), now());
                    proposed = outcome.diagnosis();
                    store.updateFailure(failure.id(), proposed.bucketLabel(), failure.ruleId(),
                            FailureStatus.PROPOSED, proposed.targetSubject(),
                            json(proposed), null, null, now());
                }

                if (proposed.needsHuman() || proposed.confidence() < CONFIDENCE_THRESHOLD) {
                    String reason = "Diagnosis confidence did not meet the 0.75 application gate: "
                            + proposed.rationale();
                    recordEscalation(watch, cycle, failure, reason);
                    escalatedIds.add(failure.id());
                    escalationReasons.add(reason);
                    continue;
                }
                if (!isAppOwnedWorktree(watch.localPath())) {
                    String reason = "Diagnosis is ready, but automatic edits require an app-owned worktree";
                    recordEscalation(watch, cycle, failure, reason);
                    escalatedIds.add(failure.id());
                    escalationReasons.add(reason);
                    continue;
                }
                ensureClean(root);
                ensureActive(watch, cycle);
                uncommitted = proposed.binding().startsWith("recipe:")
                        ? applier.applyRecipe(root, proposed)
                        : applier.apply(root, proposed);
                store.updateFailure(failure.id(), proposed.bucketLabel(), failure.ruleId(),
                        FailureStatus.FIXED, proposed.targetSubject(),
                        json(proposed), json(uncommitted), null, now());
                phase(watch, cycle, Phase.VERIFY,
                        "Running CI-derived verification for " + failure.signature());
                VerifiedFix verified = verifier.verify(
                        root, uncommitted, profile, failure.module());
                uncommitted = verified.fix();
                VerificationResult verification = verified.verification();
                store.updateFailure(failure.id(), proposed.bucketLabel(), failure.ruleId(),
                        verification.passed() ? FailureStatus.VERIFIED : FailureStatus.FAILED,
                        proposed.targetSubject(), json(proposed), json(uncommitted),
                        json(verification), now());
                if (!verification.passed()) {
                    if (!uncommitted.filesChanged().isEmpty()) {
                        gitSafety.discardTrackedProposal(root, uncommitted.filesChanged());
                    }
                    uncommitted = null;
                    recordEscalation(watch, cycle, failure, verification.reason());
                    escalatedIds.add(failure.id());
                    escalationReasons.add(verification.reason());
                    continue;
                }
                timeline(watch, Phase.VERIFY, "verified",
                        "CI-derived local verification passed for " + failure.signature(),
                        probeResult.headSha());

                if (batch == null) {
                    batch = gitSafety.beginFixupBatch(
                            root, probeResult.baseSha(), "origin",
                            requireNonNull(probeResult.branch(), "PR head branch is null"),
                            () -> store.isCycleActive(watch.id(), cycle.id()),
                            (backupRef, originalHead) -> {
                                if (!store.recordCycleBackupIfLive(
                                        cycle.id(), backupRef, originalHead, now())) {
                                    throw new CycleCancelledException();
                                }
                            });
                }
                phase(watch, cycle, Phase.COMMIT,
                        "Creating a path-scoped fixup for " + proposed.targetSubject());
                batch.commitFixup(uncommitted.filesChanged(), proposed.targetSubject());
                uncommitted = null;
                ensureActive(watch, cycle);

                if (learned) {
                    long candidateAt = now();
                    String recipeJson = proposed.binding().startsWith("recipe:")
                            ? json(proposed) : null;
                    Rule candidate = store.upsertCandidate(new Rule(
                            UUID.randomUUID().toString(), watch.workspaceId(), watch.owner(), watch.repo(),
                            proposed.signaturePattern(), failure.module(), proposed.bucketLabel(),
                            proposed.binding(), recipeJson, RuleStatus.CANDIDATE, "agent", 100,
                            json(List.of(failure.id())), 1, candidateAt, candidateAt, null));
                    candidateIds.add(candidate.id());
                }
                completed++;
            }

            if (batch == null) {
                String reason = escalationReasons.isEmpty()
                        ? "No failure produced a verified local fix"
                        : String.join("; ", escalationReasons);
                handoff(watch, cycle,
                        escalatedIds.isEmpty() ? null : escalatedIds.getFirst(),
                        null, null, reason);
                return;
            }
            phase(watch, cycle, Phase.REBASE,
                    "Autosquashing all verified fixups inside the PR-owned range");
            safety = requireNonNull(batch, "verified fixup batch is null").finish();
            ensureActive(watch, cycle);

            String proof = json(safety.proof());
            long cost = store.findCycle(cycle.id()).map(Cycle::costMilliUsd).orElse(0L);
            String command = handoffCommand(root, probeResult.branch());
            HandoffDto handoff = new HandoffDto(
                    escalatedIds.isEmpty()
                            ? "verified_local_fix" : "verified_local_fix_with_escalations",
                    escalatedIds.isEmpty() ? null : escalatedIds.getFirst(), command,
                    completed + " local fixup(s) passed verification and one net-neutral history proof. "
                            + escalatedIds.size() + " failure(s) need human attention. "
                            + "The harness did not push.");
            if (!store.finishHandoff(
                    cycle.id(), watch.id(), cost, safety.backupRef(), proof,
                    probeResult.runStatusTail(), json(handoff), now())) {
                throw new CycleCancelledException();
            }
            handoffPersisted = true;
            store.appendEvent(watch.id(), cycle.id(), Phase.DONE, "handoff_ready",
                    "Verified local fixups are ready for human push", json(Map.of(
                            "backupRef", safety.backupRef(),
                            "candidateRuleIds", candidateIds,
                            "fixedFailures", completed,
                            "escalatedFailures", escalatedIds)), now());
            timeline(watch, Phase.DONE, "handoff",
                    "Verified fixes are ready for human push", safety.proof().afterHead());
            service.notifyComplete(store.findWatch(watch.id()).orElse(watch), handoff.detail());
        }
        catch (RuntimeException failureAfterEdit) {
            if (!handoffPersisted) {
                rollbackBatch(root, uncommitted, batch, failureAfterEdit);
            }
            throw failureAfterEdit;
        }
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

    private Diagnosis validatedRecipe(Rule rule)
    {
        if (rule.recipeJson() == null || rule.recipeJson().isBlank()) {
            throw new IllegalStateException("recipe description is missing");
        }
        Diagnosis recipe = decode(rule.recipeJson(), Diagnosis.class);
        if (!rule.binding().equals(recipe.binding())
                || !rule.matcherPattern().equals(recipe.signaturePattern())
                || !rule.bucketLabel().equals(recipe.bucketLabel())
                || recipe.needsHuman()
                || recipe.confidence() < CONFIDENCE_THRESHOLD
                || recipe.targetSubject() == null || recipe.targetSubject().isBlank()
                || recipe.edits() == null
                || recipe.verifyHint() == null
                || recipe.edits().size() > 20
                || (recipe.edits().isEmpty()
                        && recipe.verifyHint().stream()
                                .map(HarnessModels::verifyVerb)
                                .noneMatch("regen"::equals))) {
            throw new IllegalStateException("recipe description does not match its approved rule");
        }
        return recipe;
    }

    private static void abortBatch(FixupBatch batch)
    {
        if (batch != null) {
            batch.abort();
        }
    }

    private void rollbackBatch(
            Path root, FixResult uncommitted, FixupBatch batch, RuntimeException original)
    {
        try {
            if (uncommitted != null) {
                if (!uncommitted.filesChanged().isEmpty()) {
                    gitSafety.discardTrackedProposal(root, uncommitted.filesChanged());
                }
            }
            abortBatch(batch);
        }
        catch (RuntimeException cleanupFailure) {
            cleanupFailure.addSuppressed(original);
            throw cleanupFailure;
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
