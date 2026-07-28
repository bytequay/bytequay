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

import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.service.harness.HarnessBootstrapper.BootstrapResult;
import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import com.bytequay.app.service.harness.HarnessModels.BudgetDto;
import com.bytequay.app.service.harness.HarnessModels.Cycle;
import com.bytequay.app.service.harness.HarnessModels.CycleDetail;
import com.bytequay.app.service.harness.HarnessModels.CycleDto;
import com.bytequay.app.service.harness.HarnessModels.CycleStatus;
import com.bytequay.app.service.harness.HarnessModels.Diagnosis;
import com.bytequay.app.service.harness.HarnessModels.Event;
import com.bytequay.app.service.harness.HarnessModels.EventDto;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureDto;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.harness.HarnessModels.FixResult;
import com.bytequay.app.service.harness.HarnessModels.GitSafetyProof;
import com.bytequay.app.service.harness.HarnessModels.HandoffDto;
import com.bytequay.app.service.harness.HarnessModels.HarnessDashboard;
import com.bytequay.app.service.harness.HarnessModels.Phase;
import com.bytequay.app.service.harness.HarnessModels.PhaseStateDto;
import com.bytequay.app.service.harness.HarnessModels.Rule;
import com.bytequay.app.service.harness.HarnessModels.RuleDto;
import com.bytequay.app.service.harness.HarnessModels.RuleStatus;
import com.bytequay.app.service.harness.HarnessModels.StatsDto;
import com.bytequay.app.service.harness.HarnessModels.VerificationResult;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import com.bytequay.app.service.harness.HarnessModels.WatchStatus;
import com.bytequay.app.service.harness.HarnessModels.WatchSummary;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.workspaces.HarnessWatchHandoff;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static java.util.Objects.requireNonNull;

/** CRUD and renderer projection for the durable harness aggregate. */
@Service
public class HarnessService
        implements HarnessWatchHandoff
{
    public static final long DEFAULT_BUDGET_MILLI_USD = 10_000;
    private static final long MAX_BUDGET_MILLI_USD = 100_000;
    private static final int MAX_NOTE = 4_000;
    private static final int MAX_ASK_CONTEXT = 24_000;

    private final HarnessStore store;
    private final HarnessBootstrapper bootstrapper;
    private final HarnessDiagnosisService diagnosis;
    private final WorkspaceRepositoryResolver workspaceRepos;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final Set<String> bootstrapWorkers = ConcurrentHashMap.newKeySet();

    public HarnessService(
            HarnessStore store,
            HarnessBootstrapper bootstrapper,
            HarnessDiagnosisService diagnosis,
            WorkspaceRepositoryResolver workspaceRepos,
            NotificationService notifications,
            ObjectMapper mapper,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.store = requireNonNull(store, "store is null");
        this.bootstrapper = requireNonNull(bootstrapper, "bootstrapper is null");
        this.diagnosis = requireNonNull(diagnosis, "diagnosis is null");
        this.workspaceRepos = requireNonNull(workspaceRepos, "workspaceRepos is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    public HarnessDashboard create(String workspaceId, CreateWatchCommand command)
    {
        requireNonNull(command, "command is null");
        WorkspaceRepositoryResolver.RepositoryIdentity identity = workspaceRepos.resolve(workspaceId);
        if (!identity.owner().equals(command.owner()) || !identity.repo().equals(command.repo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "watch repository must match the workspace repository");
        }
        if (command.prNumber() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prNumber must be positive");
        }
        long budget = command.budgetMilliUsd() == null
                ? DEFAULT_BUDGET_MILLI_USD : command.budgetMilliUsd();
        if (budget < 100 || budget > MAX_BUDGET_MILLI_USD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "budgetMilliUsd must be between 100 and " + MAX_BUDGET_MILLI_USD);
        }
        Optional<Watch> existing = store.findLiveWatch(
                workspaceId, command.owner(), command.repo(), command.prNumber());
        if (existing.isPresent()) {
            Watch live = existing.orElseThrow();
            store.backfillLocalPrId(live.id(), blankToNull(command.localPrId()), now());
            HarnessDashboard dashboard = dashboard(store.findWatch(live.id()).orElse(live));
            if (live.status() == WatchStatus.BOOTSTRAP) {
                scheduleBootstrap(live.id());
            }
            return dashboard;
        }

        long now = now();
        Watch watch = new Watch(
                UUID.randomUUID().toString(), workspaceId, command.owner(), command.repo(),
                command.prNumber(), blankToNull(command.localPrId()), blankToNull(command.localPath()),
                blankToNull(command.branch()), blankToNull(command.title()), WatchStatus.BOOTSTRAP,
                null, "pending", "{}", budget, 0, null, now, now, null, null);
        try {
            store.insertWatch(watch);
        }
        catch (DuplicateKeyException race) {
            Watch live = store.findLiveWatch(
                    workspaceId, command.owner(), command.repo(), command.prNumber())
                    .orElseThrow(() -> race);
            store.backfillLocalPrId(live.id(), blankToNull(command.localPrId()), now());
            HarnessDashboard dashboard = dashboard(store.findWatch(live.id()).orElse(live));
            if (live.status() == WatchStatus.BOOTSTRAP) {
                scheduleBootstrap(live.id());
            }
            return dashboard;
        }

        store.appendEvent(watch.id(), null, Phase.PROBE, "bootstrap_started",
                "Discovering Maven modules and GitHub Actions verification steps", "{}", now);
        // Materialize the pending DTO before handing work to the executor so
        // callers always get a visible, pollable bootstrap state.
        HarnessDashboard pending = dashboard(watch);
        scheduleBootstrap(watch.id());
        return pending;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedBootstraps()
    {
        store.watchesInStatus(WatchStatus.BOOTSTRAP)
                .forEach(watch -> scheduleBootstrap(watch.id()));
    }

    private void scheduleBootstrap(String watchId)
    {
        if (!bootstrapWorkers.add(watchId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    runBootstrap(watchId);
                }
                finally {
                    bootstrapWorkers.remove(watchId);
                }
            });
        }
        catch (RuntimeException schedulingFailure) {
            bootstrapWorkers.remove(watchId);
            failBootstrap(watchId, schedulingFailure);
        }
    }

    void runBootstrap(String watchId)
    {
        Watch watch = store.findWatch(watchId).orElse(null);
        if (watch == null || watch.status() != WatchStatus.BOOTSTRAP) {
            return;
        }
        try {
            BootstrapResult result = bootstrapper.bootstrap(
                    watch.owner(), watch.repo(), watch.localPath(), watch.id(), watch.branch());
            store.completeWatchBootstrap(
                    watch.id(), json(result.profile()), result.root().toString(),
                    watch.branch(), now());
        }
        catch (RuntimeException failure) {
            failBootstrap(watch.id(), failure);
        }
    }

    private void failBootstrap(String watchId, RuntimeException failure)
    {
        Watch watch = store.findWatch(watchId).orElse(null);
        if (watch == null || watch.status() != WatchStatus.BOOTSTRAP) {
            return;
        }
        HandoffDto handoff = new HandoffDto(
                "bootstrap_failed", null, null, message(failure));
        if (store.failWatchBootstrap(
                watch.id(), json(handoff), json(Map.of("error", message(failure))), now())) {
            notifyNeedsAttention(watch, "CI harness bootstrap needs attention", message(failure));
        }
    }

    @Override
    public String create(
            String workspaceId,
            String repoFullName,
            int prNumber,
            String localPrId,
            String branchName,
            String worktreePath,
            long budgetMilliUsd)
    {
        int slash = repoFullName == null ? -1 : repoFullName.indexOf('/');
        if (slash <= 0 || slash == repoFullName.length() - 1) {
            throw new IllegalArgumentException("repoFullName must be owner/repo");
        }
        HarnessDashboard dashboard = create(workspaceId, new CreateWatchCommand(
                repoFullName.substring(0, slash), repoFullName.substring(slash + 1), prNumber,
                localPrId, worktreePath, branchName, null, budgetMilliUsd));
        return dashboard.watchId();
    }

    public List<WatchSummary> list(String workspaceId)
    {
        workspaceRepos.resolve(workspaceId);
        return store.listWatches(workspaceId).stream().map(this::summary).toList();
    }

    public HarnessDashboard get(String workspaceId, String watchId)
    {
        return dashboard(requireWatch(workspaceId, watchId));
    }

    public HarnessDashboard stop(String workspaceId, String watchId)
    {
        Watch watch = requireWatch(workspaceId, watchId);
        long now = now();
        // Stop the watch first. Guarded cycle transitions observe this durable
        // state and cannot race a cancelled cycle back into RUNNING/HANDOFF.
        store.updateWatchStatus(watch.id(), WatchStatus.STOPPED, null, now);
        store.findLiveCycle(watch.id()).ifPresent(cycle -> store.finishCycle(
                cycle.id(), CycleStatus.CANCELLED, cycle.phase(), cycle.costMilliUsd(),
                cycle.backupRef(), cycle.netNeutralProofJson(), cycle.runStatusTail(),
                "watch stopped by user", now));
        store.appendEvent(watch.id(), null, Phase.DONE, "watch_stopped",
                "Watch stopped; no remote state was changed", "{}", now);
        return get(workspaceId, watchId);
    }

    public CycleDetail cycle(String workspaceId, String watchId, String cycleId)
    {
        requireWatch(workspaceId, watchId);
        Cycle cycle = store.findCycle(cycleId)
                .filter(value -> value.watchId().equals(watchId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no harness cycle"));
        return new CycleDetail(toCycle(cycle),
                store.listEventsForCycle(cycle.id()).stream().map(HarnessService::toEvent).toList(),
                store.listFailuresForCycle(cycle.id()).stream().map(this::toFailure).toList());
    }

    public List<RuleDto> rules(String workspaceId, String watchId)
    {
        Watch watch = requireWatch(workspaceId, watchId);
        return store.listRules(workspaceId, watch.owner(), watch.repo()).stream()
                .map(HarnessService::toRule).toList();
    }

    public RuleDto approveRule(String workspaceId, String watchId, String ruleId)
    {
        Watch watch = requireWatch(workspaceId, watchId);
        Rule rule = requireRule(workspaceId, watch, ruleId);
        Rule approved = store.approveRule(rule.id(), now());
        store.appendEvent(watch.id(), null, Phase.CLASSIFY, "rule_approved",
                "Knowledge rule approved for routing", json(Map.of("ruleId", rule.id())), now());
        return toRule(approved);
    }

    public RuleDto retireRule(String workspaceId, String watchId, String ruleId)
    {
        Watch watch = requireWatch(workspaceId, watchId);
        Rule rule = requireRule(workspaceId, watch, ruleId);
        Rule retired = store.retireRule(rule.id(), now());
        store.appendEvent(watch.id(), null, Phase.CLASSIFY, "rule_retired",
                "Knowledge rule retired; it no longer routes failures",
                json(Map.of("ruleId", rule.id())), now());
        return toRule(retired);
    }

    /** Closes one escalated failure. The note is durable in the milestone feed;
     * re-running the cycle is the caller's separate, explicit step. */
    public String resolveFailure(String workspaceId, String watchId, String failureId, String note)
    {
        Watch watch = requireWatch(workspaceId, watchId);
        Failure failure = store.findFailure(failureId)
                .filter(value -> store.findCycle(value.cycleId())
                        .filter(cycle -> cycle.watchId().equals(watch.id())).isPresent())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no harness failure"));
        if (failure.status() != FailureStatus.ESCALATED && failure.status() != FailureStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "only an escalated or failed failure can be resolved");
        }
        String bounded = boundedNote(note);
        long now = now();
        store.updateFailureStatus(failure.id(), FailureStatus.RESOLVED, now);
        store.appendEvent(watch.id(), failure.cycleId(), Phase.CLASSIFY, "escalation_resolved",
                "You resolved " + failure.signature(),
                json(Map.of("failureId", failure.id(), "note", bounded == null ? "" : bounded)), now);
        if (watch.status() == WatchStatus.NEEDS_ATTENTION
                && store.listFailuresForWatch(watch.id(), 200).stream()
                        .noneMatch(value -> value.status() == FailureStatus.ESCALATED)) {
            store.updateWatchStatusIfNotStopped(
                    watch.id(), WatchStatus.WATCHING, watch.handoffJson(), now);
        }
        return bounded;
    }

    /** Answers a question about this watch with the read-only diagnosis tools.
     * The agent may look at the repository; it can never edit, commit, or push. */
    public HarnessDashboard ask(String workspaceId, String watchId, String question)
    {
        Watch watch = requireWatch(workspaceId, watchId);
        String bounded = boundedNote(question);
        if (bounded == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        if (watch.localPath() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "the watch has no local checkout to read from yet");
        }
        long remaining = Math.max(0, watch.budgetMilliUsd() - watch.spentMilliUsd());
        if (remaining < 100) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "raise the watch budget before asking another question");
        }
        long asked = now();
        store.appendEvent(watch.id(), null, Phase.CLASSIFY, "question",
                bounded, json(Map.of("author", "user")), asked);
        HarnessDiagnosisService.AskOutcome outcome;
        try {
            outcome = diagnosis.ask(
                    Path.of(watch.localPath()), watch.workspaceId(),
                    bounded, askContext(watch), remaining);
        }
        catch (RuntimeException failure) {
            store.appendEvent(watch.id(), null, Phase.CLASSIFY, "answer_failed",
                    "The question could not be answered: " + message(failure), "{}", now());
            return get(workspaceId, watchId);
        }
        store.addWatchCost(watch.id(), outcome.costMilliUsd(), now());
        store.appendEvent(watch.id(), null, Phase.CLASSIFY, "answer",
                outcome.answer(), json(Map.of("author", "harness")), now());
        return get(workspaceId, watchId);
    }

    /** The run state the answer must be grounded in, so a question about a
     * failure does not become a fresh unguided investigation. */
    private String askContext(Watch watch)
    {
        StringBuilder out = new StringBuilder("Watch status: ").append(watch.status().wire())
                .append("\nPull request: ").append(watch.owner()).append('/').append(watch.repo())
                .append(" #").append(watch.prNumber())
                .append("\nBranch: ").append(watch.branch() == null ? "unknown" : watch.branch());
        for (Failure failure : store.listFailuresForWatch(watch.id(), 40)) {
            out.append("\n\n- [").append(failure.status().wire()).append("] ")
                    .append(failure.bucketLabel()).append(" in ").append(failure.module())
                    .append("\n  signature: ").append(failure.signature());
            if (failure.targetSubject() != null) {
                out.append("\n  proposed owner: ").append(failure.targetSubject());
            }
            Diagnosis parsed = parse(failure.diagnosisJson(), Diagnosis.class);
            if (parsed != null) {
                out.append("\n  root cause: ").append(parsed.rootCause())
                        .append("\n  confidence: ").append(parsed.confidence());
            }
            VerificationResult verified = parse(failure.verificationJson(), VerificationResult.class);
            if (verified != null && !verified.passed()) {
                out.append("\n  verification failed: ").append(verified.reason());
            }
        }
        return out.length() <= MAX_ASK_CONTEXT
                ? out.toString() : out.substring(0, MAX_ASK_CONTEXT);
    }

    private Rule requireRule(String workspaceId, Watch watch, String ruleId)
    {
        return store.findRule(ruleId)
                .filter(value -> value.workspaceId().equals(workspaceId)
                        && value.owner().equals(watch.owner()) && value.repo().equals(watch.repo()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no harness rule"));
    }

    private static String boundedNote(String value)
    {
        String stripped = blankToNull(value);
        return stripped == null || stripped.length() <= MAX_NOTE
                ? stripped : stripped.substring(0, MAX_NOTE);
    }

    public void notifyNeedsAttention(Watch watch, String title, String summary)
    {
        notifications.createCanonical(
                NotificationKind.NEEDS_ATTENTION, watch.workspaceId(), null, null,
                "ci", title, summary, itemPath(watch),
                "ci-harness:" + watch.id() + ":needs-attention:" + watch.headSha(),
                json(Map.of("watchId", watch.id(), "summary", summary)));
    }

    public void notifyComplete(Watch watch, String summary)
    {
        notifications.createCanonical(
                NotificationKind.AUTO_FIX_DONE, watch.workspaceId(), null, null,
                "ci", "CI harness is ready for handoff", summary, itemPath(watch),
                "ci-harness:" + watch.id() + ":handoff:" + watch.headSha(),
                json(Map.of("watchId", watch.id(), "summary", summary)));
    }

    private HarnessDashboard dashboard(Watch watch)
    {
        List<Cycle> cycles = store.listCycles(watch.id(), 50);
        Cycle active = cycles.stream()
                .filter(value -> value.status() == CycleStatus.QUEUED || value.status() == CycleStatus.RUNNING)
                .findFirst().orElse(null);
        Cycle newest = cycles.isEmpty() ? null : cycles.getFirst();
        List<Failure> failures = store.listFailuresForWatch(watch.id(), 100);
        Map<String, Long> failuresByState = failures.stream().collect(Collectors.groupingBy(
                value -> value.status().wire(), LinkedHashMap::new, Collectors.counting()));
        long cycleCost = active != null ? active.costMilliUsd()
                : newest == null ? 0 : newest.costMilliUsd();
        HarnessModels.HandoffDto handoff = parse(watch.handoffJson(), HandoffDto.class);
        GitSafetyProof proof = newest == null ? null
                : parse(newest.netNeutralProofJson(), GitSafetyProof.class);
        String handoffCommand = handoff == null ? null : handoff.command();
        BootstrapProfile bootstrapProfile = "ready".equals(watch.bootstrapStatus())
                ? profile(watch.bootstrapProfileJson()) : null;
        return new HarnessDashboard(
                watch.id(), watch.workspaceId(), watch.status().wire(), watch.owner(), watch.repo(),
                watch.prNumber(), watch.localPrId(), watch.branch(), watch.title(), watch.headSha(),
                watch.bootstrapStatus(), bootstrapProfile,
                new BudgetDto(watch.budgetMilliUsd(), watch.spentMilliUsd(), cycleCost,
                        Math.max(0, watch.budgetMilliUsd() - watch.spentMilliUsd())),
                active == null ? null : toCycle(active), cycles.stream().map(this::toCycle).toList(),
                store.listEventsForWatch(watch.id(), 200).stream().map(HarnessService::toEvent).toList(),
                failures.stream().map(this::toFailure).toList(),
                new StatsDto(Collections.unmodifiableMap(failuresByState),
                        store.countRules(watch.workspaceId(), watch.owner(), watch.repo(), RuleStatus.ACTIVE),
                        store.countRules(watch.workspaceId(), watch.owner(), watch.repo(), RuleStatus.CANDIDATE),
                        cycleCost, watch.spentMilliUsd()),
                newest == null ? null : newest.backupRef(), proof, handoff, handoffCommand,
                newest == null ? null : newest.runStatusTail());
    }

    private WatchSummary summary(Watch watch)
    {
        return new WatchSummary(watch.id(), watch.status().wire(), watch.owner(), watch.repo(),
                watch.prNumber(), watch.localPrId(), watch.branch(), watch.title(), watch.headSha(),
                watch.bootstrapStatus(), watch.budgetMilliUsd(), watch.spentMilliUsd(), watch.updatedAtMs());
    }

    private CycleDto toCycle(Cycle cycle)
    {
        GitSafetyProof proof = parse(cycle.netNeutralProofJson(), GitSafetyProof.class);
        return new CycleDto(cycle.id(), cycle.ordinal(), cycle.triggerKind(), cycle.steeringText(),
                cycle.status().wire(),
                cycle.phase().wire(), cycle.headSha(), cycle.costMilliUsd(), cycle.backupRef(), proof,
                cycle.runStatusTail(), cycle.startedAtMs(), cycle.finishedAtMs(), phaseStates(cycle));
    }

    private static List<PhaseStateDto> phaseStates(Cycle cycle)
    {
        List<PhaseStateDto> states = new ArrayList<>();
        for (Phase phase : Phase.values()) {
            String status;
            if (phase.ordinal() < cycle.phase().ordinal()) {
                status = "done";
            }
            else if (phase == cycle.phase()
                    && (cycle.status() == CycleStatus.RUNNING || cycle.status() == CycleStatus.QUEUED)) {
                status = "running";
            }
            else if (phase == cycle.phase() && cycle.status() == CycleStatus.FAILED) {
                status = "failed";
            }
            else if (phase.ordinal() <= cycle.phase().ordinal()
                    && cycle.finishedAtMs() != null) {
                status = "done";
            }
            else {
                status = "pending";
            }
            states.add(new PhaseStateDto(phase.wire(), status));
        }
        return List.copyOf(states);
    }

    private static EventDto toEvent(Event event)
    {
        return new EventDto(event.id(), event.cycleId(), event.phase().wire(), event.kind(),
                event.message(), event.detailJson(), event.createdAtMs());
    }

    FailureDto toFailure(Failure failure)
    {
        return new FailureDto(failure.id(), failure.cycleId(), failure.status().wire(),
                failure.bucketLabel(), failure.jobName(), failure.module(),
                failure.testClass(), failure.testMethod(), failure.signature(),
                failure.logExcerpt(), failure.targetSubject(), failure.ruleId(),
                parse(failure.diagnosisJson(), Diagnosis.class),
                parse(failure.fixJson(), FixResult.class),
                parse(failure.verificationJson(), VerificationResult.class),
                failure.updatedAtMs());
    }

    static RuleDto toRule(Rule rule)
    {
        return new RuleDto(rule.id(), rule.matcherPattern(), rule.scope(), rule.bucketLabel(),
                rule.binding(), rule.status().wire(), rule.origin(), rule.priority(),
                rule.hits(), rule.approvedAtMs());
    }

    private Watch requireWatch(String workspaceId, String id)
    {
        workspaceRepos.resolve(workspaceId);
        return store.findWatch(id)
                .filter(value -> value.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no harness watch"));
    }

    BootstrapProfile profile(String json)
    {
        BootstrapProfile parsed = parse(json, BootstrapProfile.class);
        if (parsed == null) {
            return BootstrapProfile.empty();
        }
        return new BootstrapProfile(
                parsed.forge() == null ? "github-actions" : parsed.forge(),
                parsed.ecosystems() == null ? Set.of() : parsed.ecosystems(),
                parsed.workflowFiles() == null ? List.of() : parsed.workflowFiles(),
                parsed.verifySteps() == null ? Map.of() : parsed.verifySteps(),
                parsed.aggregatorJobs() == null ? Set.of() : parsed.aggregatorJobs(),
                parsed.infraJobs() == null ? Set.of() : parsed.infraJobs(),
                parsed.modules() == null ? Map.of() : parsed.modules(),
                parsed.runtimeMetadata() == null ? Map.of() : parsed.runtimeMetadata(),
                parsed.verificationEnvironment() == null ? Map.of() : parsed.verificationEnvironment(),
                parsed.warnings() == null ? List.of() : parsed.warnings());
    }

    private <T> T parse(String json, Class<T> type)
    {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        }
        catch (JsonProcessingException ignored) {
            return null;
        }
    }

    String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("unable to encode harness state", e);
        }
    }

    private static String itemPath(Watch watch)
    {
        return "#/workspace/" + watch.workspaceId() + "/ci-harness/" + watch.id();
    }

    private static String blankToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String message(Throwable failure)
    {
        return Optional.ofNullable(failure.getMessage())
                .orElse(failure.getClass().getSimpleName());
    }

    private static long now()
    {
        return Instant.now().toEpochMilli();
    }

    public record CreateWatchCommand(
            String owner,
            String repo,
            int prNumber,
            String localPrId,
            String localPath,
            String branch,
            String title,
            Long budgetMilliUsd) {}
}
