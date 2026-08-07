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
package com.bytequay.app.service.localpr;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.RetiredSagaGate;
import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPushAuthorization;
import com.bytequay.app.domain.TaskPushEffect;
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.TaskPushStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskExternalEffectGate;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Durable create-or-adopt saga for the first local push. Each external call
 * has a committed claim before I/O and a durable completion stamp after it;
 * the task phase does not move until both stamps and remote identity exist.
 */
@Service
public class TaskPushSaga
{
    static final String EFFECT_PUSH_BRANCH = "push_branch";
    static final String EFFECT_ENSURE_PULL_REQUEST = "ensure_pull_request";

    private static final Logger log = LoggerFactory.getLogger(TaskPushSaga.class);
    private static final List<String> EFFECT_ORDER =
            List.of(EFFECT_PUSH_BRANCH, EFFECT_ENSURE_PULL_REQUEST);
    private static final int ATTEMPT_LIMIT = 3;
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(10);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(15);
    private static final int RECOVERY_BATCH = 50;
    private static final String LINKED_STATUS_DRAFT = "draft";
    private static final String RECOVERY_EFFECT_FAILED = "EFFECT_FAILED";
    private static final String RECOVERY_FINGERPRINT_MISMATCH = "FINGERPRINT_MISMATCH";
    public static final int DEFAULT_RECOVERY_ALLOWANCE = 1;
    private static final int MAX_RECOVERY_ALLOWANCE = 3;

    private final PRService prs;
    private final TaskStore tasks;
    private final WatchedRepoStore watchedRepos;
    private final GitRunner git;
    private final PullRequestRepository pullRequests;
    private final PatResolver pats;
    private final ReviewRoundStore rounds;
    private final CodeFingerprints fingerprints;
    private final TaskPushStore pushes;
    private final TaskCommandExecutor commands;
    private final TaskPhaseMachine taskMachine;
    private final NotificationService notifications;
    private final LocalReviewSubmissionStore submissions;
    private final RetiredSagaGate capacity;
    private final ObjectMapper mapper;

    public TaskPushSaga(
            PRService prs,
            TaskStore tasks,
            WatchedRepoStore watchedRepos,
            GitRunner git,
            PullRequestRepository pullRequests,
            PatResolver pats,
            ReviewRoundStore rounds,
            CodeFingerprints fingerprints,
            TaskPushStore pushes,
            TaskCommandExecutor commands,
            TaskPhaseMachine taskMachine,
            NotificationService notifications,
            LocalReviewSubmissionStore submissions,
            RetiredSagaGate capacity,
            ObjectMapper mapper)
    {
        this.prs = requireNonNull(prs, "prs is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pats = requireNonNull(pats, "pats is null");
        this.rounds = requireNonNull(rounds, "rounds is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.pushes = requireNonNull(pushes, "pushes is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.taskMachine = requireNonNull(taskMachine, "taskMachine is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.submissions = requireNonNull(submissions, "submissions is null");
        this.capacity = requireNonNull(capacity, "capacity is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Authorize, drive, and return the now-remote PR. */
    public PR push(String prId, boolean humanOverride)
    {
        rejectLegacyRuntime();
        PR pr = requirePr(prId);
        if (pr.taskId() == null || pr.taskId().isBlank()) {
            throw conflict("local PR " + prId + " has no task");
        }
        if (tasks.isV2Task(pr.taskId())) {
            throw conflict("V2 task publish is owned by the typed publish runtime");
        }
        return TaskExternalEffectGate.withEffectGate(pr.taskId(), () -> {
            TaskPushAuthorization authorization = authorize(prId, humanOverride);
            driveLocked(authorization.token());
            PR result = requirePr(prId);
            if (pushes.findAuthorization(authorization.token())
                    .filter(TaskPushAuthorization::active)
                    .isPresent()) {
                throw conflict("push is still awaiting retry or recovery");
            }
            return result;
        });
    }

    /** Wake-up and sweep share this exact driver. */
    public void drive(String token)
    {
        rejectLegacyRuntime();
        TaskPushAuthorization authorization = pushes.findAuthorization(token).orElse(null);
        if (authorization == null) {
            return;
        }
        if (tasks.isV2Task(authorization.taskId())) {
            return;
        }
        TaskExternalEffectGate.withEffectGate(authorization.taskId(), () -> {
            driveLocked(token);
            return null;
        });
    }

    /** A paused local ship keeps its durable authorization. The resume
     * barrier uses this to select the saga, rather than starting an agent
     * runtime, and drives the token only after the task command commits. */
    public Optional<String> activeToken(String taskId)
    {
        if (tasks.isV2Task(taskId)) {
            return Optional.empty();
        }
        return pushes.findActiveByTask(taskId).map(TaskPushAuthorization::token);
    }

    /** Adopt a matching remote-open fact into an authorization that another
     * publisher raced to complete. Both remote effects are proved before they
     * are stamped, and the normal atomic handoff remains the sole phase edge. */
    public boolean adoptRemotePullRequest(
            String taskId, String repo, int number, String url)
    {
        rejectLegacyRuntime();
        if (tasks.isV2Task(taskId)) {
            return false;
        }
        if (pushes.findActiveByTask(taskId).isEmpty()) {
            return false;
        }
        return TaskExternalEffectGate.withEffectGate(taskId, () -> {
            TaskPushAuthorization authorization = pushes.findActiveByTask(taskId).orElse(null);
            if (authorization == null) {
                return false;
            }
            PushPayload payload = payload(authorization);
            if (!payload.repoOwner().concat("/").concat(payload.repoName())
                    .equalsIgnoreCase(repo)) {
                throw conflict("remote-open event does not match the authorized repository");
            }
            Optional<RetiredSagaGate.Attempt> admitted = capacity.tryAcquire(
                    taskId,
                    authorizationCapacityOperationId(authorization.token(), "adopt"),
                    Set.of(CapacityManager.CapacityLane.GITHUB));
            if (admitted.isEmpty()) {
                return false;
            }
            ObservedCode observed;
            RemoteEvidence remote;
            try (RetiredSagaGate.Attempt attempt = admitted.orElseThrow()) {
                try {
                    Path worktree = Path.of(payload.worktreePath());
                    attempt.requireLive();
                    String remoteHead = remoteHeadSha(
                            worktree, payload.branchName()).orElse(null);
                    if (!authorization.headSha().equals(remoteHead)) {
                        throw conflict("remote-open branch does not match the authorized HEAD");
                    }
                    attempt.requireLive();
                    RepoRef target = new RepoRef(payload.repoOwner(), payload.repoName());
                    PullRequest opened = findExistingOpenPullRequest(
                            pats.resolve(target.fullName()), target, payload)
                            .filter(candidate -> candidate.number() == number)
                            .filter(candidate -> url.equals(candidate.htmlUrl()))
                            .orElseThrow(() -> conflict(
                                    "remote-open pull request does not match the authorization"));
                    remote = new RemoteEvidence(
                            opened.repo(), opened.number(), opened.htmlUrl(), opened.author());
                    attempt.requireLive();
                    observed = observeAuthorizedCode(authorization, worktree);
                    attempt.requireLive();
                    commands.executeVoid(taskId, () -> completeObservedRemoteInCommand(
                            authorization, remote));
                    attempt.requireLive();
                    commands.executeVoid(taskId, () -> finalizeInCommand(
                            authorization, observed.head(), observed.fingerprint(), remote));
                }
                catch (RuntimeException e) {
                    if (attempt.leaseLost()) {
                        return false;
                    }
                    throw e;
                }
            }
            return true;
        });
    }

    private void completeObservedRemoteInCommand(
            TaskPushAuthorization authorization, RemoteEvidence remote)
    {
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        TaskPushAuthorization current = pushes.findAuthorization(authorization.token())
                .filter(TaskPushAuthorization::active)
                .orElseThrow(() -> conflict("push authorization is no longer active"));
        PushPayload payload = payload(current);
        if (!payload.repoOwner().concat("/").concat(payload.repoName())
                        .equalsIgnoreCase(remote.repo())) {
            throw conflict("observed pull request belongs to another repository");
        }
        completeObservedEffect(
                current.token(), EFFECT_PUSH_BRANCH,
                json(new PushEvidence(current.headSha())));
        completeObservedEffect(
                current.token(), EFFECT_ENSURE_PULL_REQUEST, json(remote));
    }

    private void completeObservedEffect(String token, String effectKey, String evidence)
    {
        TaskPushEffect effect = pushes.findEffect(token, effectKey).orElseThrow();
        if (!effect.completed()
                && !pushes.completeObservedEffect(
                        token, effectKey, evidence, Instant.now())) {
            throw conflict("could not stamp observed push effect " + effectKey);
        }
    }

    /** Build the exact durable recovery intent while the caller holds the
     * task's external-effect gate and no DB transaction. This is where the
     * restored worktree fingerprint is proved without putting git I/O inside
     * the later task command. */
    public Optional<RecoveryPlan> prepareRecovery(String taskId, int addedAllowance)
    {
        rejectLegacyRuntime();
        Task task = requireTask(taskId);
        if (task.phase() != TaskPhase.NEEDS_ATTENTION
                || task.status() != TaskStatus.NEEDS_ATTENTION) {
            return Optional.empty();
        }
        TaskPushAuthorization authorization = pushes.findActiveByTask(taskId).orElse(null);
        if (authorization == null) {
            return Optional.empty();
        }
        Optional<TaskRecoveryRequest> existing = tasks.recoveryRequest(taskId);
        if (existing.filter(request ->
                TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(request.kind())).isPresent()) {
            return Optional.of(verifyRecoveryPlan(
                    taskId, parseRecovery(existing.orElseThrow())));
        }
        TaskPushEffect cursor = currentCursor(authorization.token()).orElse(null);
        if (cursor != null && cursor.status() == TaskPushEffect.Status.IN_FLIGHT) {
            throw conflict("push effect " + cursor.effectKey() + " is still being reconciled");
        }
        boolean failed = cursor != null
                && (cursor.status() == TaskPushEffect.Status.PERMANENT_FAILED
                        || cursor.exhausted());
        int allowance = failed ? addedAllowance : 0;
        if (failed && (allowance < 1 || allowance > MAX_RECOVERY_ALLOWANCE)) {
            throw conflict("external-saga recovery allowance must be between 1 and "
                    + MAX_RECOVERY_ALLOWANCE);
        }
        ObservedCode observed = currentCode(authorization);
        if (!authorization.headSha().equals(observed.head())
                || !authorization.codeFingerprint().equals(observed.fingerprint())) {
            throw conflict("restore the push authorization's reviewed worktree before recovery");
        }
        return Optional.of(new RecoveryPlan(
                authorization.token(), cursor == null ? null : cursor.effectKey(),
                failed ? RECOVERY_EFFECT_FAILED : RECOVERY_FINGERPRINT_MISMATCH,
                allowance, observed.head(), observed.fingerprint()));
    }

    /** Re-read and re-prove a durable request immediately before the teardown
     * barrier command consumes it. */
    public Optional<RecoveryPlan> verifyRecoveryRequest(String taskId)
    {
        rejectLegacyRuntime();
        if (pushes.findActiveByTask(taskId).isEmpty()) {
            return Optional.empty();
        }
        return tasks.recoveryRequest(taskId)
                .filter(request -> TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(request.kind()))
                .map(this::parseRecovery)
                .map(plan -> verifyRecoveryPlan(taskId, plan));
    }

    public String recoveryPayload(RecoveryPlan plan)
    {
        return json(plan);
    }

    /** Re-arm only the request's exact failed cursor. Runs inside the same
     * task command that restores AWAITING_PUSH, so a crash cannot expose a
     * runnable task with its durable cursor still permanently failed. */
    public void resumeExternalSagaInCommand(RecoveryPlan plan)
    {
        rejectLegacyRuntime();
        TaskPushAuthorization authorization = pushes.findAuthorization(plan.token())
                .filter(TaskPushAuthorization::active)
                .orElseThrow(() -> conflict("push recovery authorization is no longer active"));
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        Task task = requireTask(authorization.taskId());
        if (task.phase() != TaskPhase.NEEDS_ATTENTION
                || task.status() != TaskStatus.NEEDS_ATTENTION) {
            throw conflict("task " + task.id() + " is not parked for push recovery");
        }
        RecoveryPlan durable = tasks.recoveryRequest(task.id())
                .filter(request -> TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(request.kind()))
                .map(this::parseRecovery)
                .orElseThrow(() -> conflict("task has no external-saga recovery request"));
        if (!durable.equals(plan)
                || !authorization.headSha().equals(plan.headSha())
                || !authorization.codeFingerprint().equals(plan.codeFingerprint())) {
            throw conflict("external-saga recovery request no longer matches its authorization");
        }
        TaskPushEffect cursor = currentCursor(plan.token()).orElse(null);
        if (!Objects.equals(plan.effectKey(), cursor == null ? null : cursor.effectKey())) {
            throw conflict("external-saga recovery cursor changed");
        }
        if (RECOVERY_EFFECT_FAILED.equals(plan.reason())) {
            if (cursor == null
                    || cursor.status() != TaskPushEffect.Status.PERMANENT_FAILED
                            && !cursor.exhausted()
                    || plan.addedAllowance() < 1
                    || plan.addedAllowance() > MAX_RECOVERY_ALLOWANCE
                    || !pushes.rearmEffect(
                            plan.token(), cursor.effectKey(),
                            plan.addedAllowance(), Instant.now())) {
                throw conflict("failed push cursor could not be re-armed");
            }
            return;
        }
        if (!RECOVERY_FINGERPRINT_MISMATCH.equals(plan.reason())
                || plan.addedAllowance() != 0
                || cursor != null && (cursor.status() == TaskPushEffect.Status.PERMANENT_FAILED
                        || cursor.exhausted())) {
            throw conflict("push fingerprint recovery no longer matches its cursor");
        }
    }

    private RecoveryPlan verifyRecoveryPlan(String expectedTaskId, RecoveryPlan plan)
    {
        TaskPushAuthorization authorization = pushes.findAuthorization(plan.token())
                .filter(TaskPushAuthorization::active)
                .orElseThrow(() -> conflict("push recovery authorization is no longer active"));
        if (!authorization.taskId().equals(expectedTaskId)) {
            throw conflict("push recovery authorization belongs to another task");
        }
        Task task = requireTask(authorization.taskId());
        if (task.phase() != TaskPhase.NEEDS_ATTENTION
                || task.status() != TaskStatus.NEEDS_ATTENTION) {
            throw conflict("task " + task.id() + " is not parked for push recovery");
        }
        TaskPushEffect cursor = currentCursor(plan.token()).orElse(null);
        if (!Objects.equals(plan.effectKey(), cursor == null ? null : cursor.effectKey())) {
            throw conflict("external-saga recovery cursor changed");
        }
        ObservedCode observed = currentCode(authorization);
        if (!authorization.headSha().equals(observed.head())
                || !authorization.codeFingerprint().equals(observed.fingerprint())
                || !plan.headSha().equals(observed.head())
                || !plan.codeFingerprint().equals(observed.fingerprint())) {
            throw conflict("restore the push authorization's reviewed worktree before recovery");
        }
        if (RECOVERY_EFFECT_FAILED.equals(plan.reason())) {
            if (cursor == null
                    || cursor.status() != TaskPushEffect.Status.PERMANENT_FAILED
                            && !cursor.exhausted()
                    || plan.addedAllowance() < 1
                    || plan.addedAllowance() > MAX_RECOVERY_ALLOWANCE) {
                throw conflict("external-saga failure recovery no longer matches its cursor");
            }
        }
        else if (!RECOVERY_FINGERPRINT_MISMATCH.equals(plan.reason())
                || plan.addedAllowance() != 0
                || cursor != null && (cursor.status() == TaskPushEffect.Status.PERMANENT_FAILED
                        || cursor.exhausted())) {
            throw conflict("external-saga fingerprint recovery no longer matches its cursor");
        }
        return plan;
    }

    private RecoveryPlan parseRecovery(TaskRecoveryRequest request)
    {
        if (request.payloadJson() == null || request.payloadJson().isBlank()) {
            throw conflict("external-saga recovery request has no payload");
        }
        try {
            return mapper.readValue(request.payloadJson(), RecoveryPlan.class);
        }
        catch (JsonProcessingException e) {
            throw conflict("external-saga recovery request has an invalid payload");
        }
    }

    private Optional<TaskPushEffect> currentCursor(String token)
    {
        return pushes.findEffects(token).stream()
                .filter(effect -> !effect.completed())
                .findFirst();
    }

    private ObservedCode currentCode(TaskPushAuthorization authorization)
    {
        Path worktree = Path.of(payload(authorization).worktreePath());
        return new ObservedCode(headSha(worktree), fingerprints.fingerprint(worktree));
    }

    public void reconcileActive()
    {
        rejectLegacyRuntime();
        for (TaskPushAuthorization authorization : pushes.findRecoverable(
                Instant.now(), RECOVERY_BATCH)) {
            if (tasks.isV2Task(authorization.taskId())) {
                continue;
            }
            try {
                drive(authorization.token());
            }
            catch (RuntimeException e) {
                log.warn("recovering push saga {} for task {} failed: {}",
                        authorization.token(), authorization.taskId(), e.getMessage());
            }
        }
        reconcileOrphanedRemotePullRequests();
        reconcileAutomaticPushes();
    }

    /** The auto-merge toggle is durable intent. If the immediate
     * after-commit wake-up is lost, derive the still-owed first push from the
     * task/PR/review rows and create its normal durable authorization here. */
    private void reconcileAutomaticPushes()
    {
        for (Task task : tasks.listByPhases(List.of(TaskPhase.AWAITING_PUSH), RECOVERY_BATCH)) {
            if (tasks.isV2Task(task.id())
                    || !tasks.isAutoMerge(task.id())
                    || pushes.findActiveByTask(task.id()).isPresent()) {
                continue;
            }
            PR pr = prs.findByTask(task.id()).orElse(null);
            if (pr == null || !PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
                continue;
            }
            try {
                push(pr.id(), false);
            }
            catch (RuntimeException e) {
                log.debug("automatic push remains pending for task {}: {}",
                        task.id(), safeMessage(e));
            }
        }
    }

    /** Recover the pre-saga crash shape where the PR's remote identity was
     * persisted but the task never left AWAITING_PUSH. The remote branch and
     * exact open PR are proved before synthetic completed-effect stamps are
     * written; this path never repeats either external mutation. */
    private void reconcileOrphanedRemotePullRequests()
    {
        for (String taskId : pushes.findOrphanedRemotePullRequestTaskIds(RECOVERY_BATCH)) {
            if (tasks.isV2Task(taskId)) {
                continue;
            }
            Task candidate = tasks.findTaskById(taskId).orElse(null);
            if (candidate == null) {
                continue;
            }
            if (!runnableAtGate(candidate) || pushes.findActiveByTask(candidate.id()).isPresent()) {
                continue;
            }
            PR pr = prs.findByTask(candidate.id()).orElse(null);
            if (!hasOrphanedRemoteIdentity(pr)) {
                continue;
            }
            try {
                TaskExternalEffectGate.withEffectGate(candidate.id(), () -> {
                    adoptOrphanedRemotePullRequest(candidate.id());
                    return null;
                });
            }
            catch (RuntimeException e) {
                log.warn("adopting orphaned remote PR for task {} failed: {}",
                        candidate.id(), e.getMessage());
            }
        }
    }

    private void adoptOrphanedRemotePullRequest(String taskId)
    {
        Task task = requireTask(taskId);
        PR pr = prs.findByTask(taskId).orElse(null);
        if (!runnableAtGate(task) || !hasOrphanedRemoteIdentity(pr)
                || pushes.findActiveByTask(taskId).isPresent()) {
            return;
        }
        Optional<RetiredSagaGate.Attempt> admitted = capacity.tryAcquire(
                taskId,
                "legacy-task-push-pr:" + pr.id() + ":orphan-adoption",
                Set.of(CapacityManager.CapacityLane.GITHUB));
        if (admitted.isEmpty()) {
            return;
        }
        try (RetiredSagaGate.Attempt attempt = admitted.orElseThrow()) {
            try {
                Path worktree = worktree(task);
                attempt.requireLive();
                String head = headSha(worktree);
                attempt.requireLive();
                String remoteHead = remoteHeadSha(worktree, pr.branchName()).orElse(null);
                if (!head.equals(remoteHead)) {
                    throw conflict("remote branch does not match task HEAD for orphan adoption");
                }
                attempt.requireLive();
                PublishTarget target = resolvePublishTarget(task, pr);
                if (!target.repo().fullName().equalsIgnoreCase(pr.repo())) {
                    throw conflict("remote PR repository does not match the task publish target");
                }
                PushPayload payload = new PushPayload(
                        pr.id(), task.id(), worktree.toString(), target.repo().owner(), target.repo().repo(),
                        target.apiHead(), target.headFilter(), target.base(), pr.branchName(),
                        pr.title(), pr.description(), head);
                attempt.requireLive();
                PullRequest observed = findExistingOpenPullRequest(
                        pats.resolve(target.repo().fullName()), target.repo(), payload)
                        .filter(remote -> remote.number() == pr.remotePrNumber())
                        .filter(remote -> pr.remotePrUrl().equals(remote.htmlUrl()))
                        .orElseThrow(() -> conflict(
                                "remote PR identity could not be verified for orphan adoption"));
                String payloadJson = json(payload);
                attempt.requireLive();
                String fingerprint = fingerprints.fingerprint(worktree);
                TaskPushAuthorization authorization = new TaskPushAuthorization(
                        UUID.randomUUID().toString(), task.id(), pr.id(), null, head, fingerprint,
                        Actor.WEBHOOK, TaskPushAuthorization.BASIS_LEGACY_REMOTE, null, null,
                        payloadJson, sha256(payloadJson), json(EFFECT_ORDER),
                        Instant.now(), null, null, null);
                RemoteEvidence remote = new RemoteEvidence(
                        observed.repo(), observed.number(), observed.htmlUrl(), observed.author());
                attempt.requireLive();
                OrphanAdoption adoption = new OrphanAdoption(
                        authorization, json(new PushEvidence(head)), json(remote), remote);
                commands.executeVoid(task.id(), () -> adoptOrphanInCommand(
                        adoption.authorization(), adoption.pushEvidence(),
                        adoption.pullRequestEvidence(), adoption.remote()));
            }
            catch (RuntimeException e) {
                if (!attempt.leaseLost()) {
                    throw e;
                }
                return;
            }
        }
    }

    private void adoptOrphanInCommand(
            TaskPushAuthorization authorization,
            String pushEvidence,
            String pullRequestEvidence,
            RemoteEvidence remote)
    {
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        Task task = requireTask(authorization.taskId());
        PR pr = prs.findByTask(task.id()).orElse(null);
        if (!runnableAtGate(task) || !hasOrphanedRemoteIdentity(pr)
                || !authorization.prId().equals(pr.id())
                || !remote.repo().equalsIgnoreCase(pr.repo())
                || remote.number() != pr.remotePrNumber()
                || !remote.url().equals(pr.remotePrUrl())
                || pushes.findActiveByTask(task.id()).isPresent()) {
            return;
        }
        pushes.insert(authorization, authorizedEffectKeys(authorization), ATTEMPT_LIMIT);
        completeAdoptedEffect(authorization, EFFECT_PUSH_BRANCH, pushEvidence);
        completeAdoptedEffect(
                authorization, EFFECT_ENSURE_PULL_REQUEST, pullRequestEvidence);
        submissions.cancelOpenForTask(task.id(), "legacy_remote_pr_adopted", Instant.now());
        tasks.markPushed(task.id(), pr.pushedAt() == null ? Instant.now() : pr.pushedAt());
        tasks.linkPullRequest(task.id(), remote.number(), LINKED_STATUS_DRAFT);
        tasks.linkTaskToPr(task.id(), remote.repo() + "#" + remote.number());
        if (!pushes.consumeIfComplete(
                authorization.token(), TaskPushAuthorization.OUTCOME_PUSHED, Instant.now())) {
            throw conflict("orphaned push authorization changed before finalization");
        }
        taskMachine.finalizeLocalShipInCommand(
                task.id(), Actor.WEBHOOK, "legacy_remote_pr_adopted");
    }

    private void completeAdoptedEffect(
            TaskPushAuthorization authorization, String effectKey, String evidence)
    {
        Instant now = Instant.now();
        String owner = UUID.randomUUID().toString();
        if (!pushes.claimEffect(
                authorization.token(), effectKey, owner, now, now.plus(CLAIM_LEASE))
                || !pushes.completeEffect(
                        authorization.token(), effectKey, owner, evidence, now)) {
            throw conflict("could not stamp adopted push effect " + effectKey);
        }
    }

    private Optional<String> remoteHeadSha(Path worktree, String branch)
    {
        try {
            return git.remoteHeadSha(worktree, "origin", branch);
        }
        catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "probing remote branch failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "probing remote branch was interrupted");
        }
    }

    private static boolean hasOrphanedRemoteIdentity(PR pr)
    {
        return pr != null
                && PR.ORIGIN_TASK.equals(pr.origin())
                && (PR.STATUS_REMOTE_DRAFTED.equals(pr.status())
                        || PR.STATUS_REMOTE_OPEN.equals(pr.status()))
                && pr.repo() != null
                && !pr.repo().isBlank()
                && pr.remotePrNumber() != null
                && pr.remotePrUrl() != null
                && !pr.remotePrUrl().isBlank();
    }

    /** Replan/submission may revoke only before the first effect claim. */
    public boolean revokeUnclaimedInCommand(String taskId, String reason)
    {
        rejectLegacyRuntime();
        TaskCommandExecutor.requireCurrent(taskId);
        return pushes.findActiveByTask(taskId)
                .map(authorization -> pushes.revokeIfUnclaimed(
                        authorization.token(), reason, Instant.now()))
                .orElse(true);
    }

    public boolean hasClaimedEffect(String taskId)
    {
        return pushes.findActiveByTask(taskId)
                .map(authorization -> pushes.findEffects(authorization.token()).stream()
                        .anyMatch(TaskPushEffect::claimed))
                .orElse(false);
    }

    private TaskPushAuthorization authorize(String prId, boolean humanOverride)
    {
        PR pr = requirePr(prId);
        Task task = requireTask(pr.taskId());
        Optional<TaskPushAuthorization> active = pushes.findActiveByTask(task.id());
        if (active.isPresent()) {
            if (!active.orElseThrow().prId().equals(prId)) {
                throw conflict("task " + task.id() + " already has an active push");
            }
            return active.orElseThrow();
        }
        if (PR.STATUS_REMOTE_DRAFTED.equals(pr.status()) && pr.remotePrNumber() != null) {
            throw conflict("local PR " + prId + " was already pushed and awaits remote reconciliation");
        }
        if (!PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            throw conflict("local PR " + prId + " is not ready to push (status=" + pr.status() + ")");
        }
        if (task.workingDir() == null || task.workingDir().isBlank()) {
            throw conflict("task " + task.id() + " has no working dir");
        }
        if (task.phase() != TaskPhase.AWAITING_PUSH
                || task.status() == TaskStatus.NEEDS_ATTENTION
                || task.status() == TaskStatus.PAUSED
                || task.status() == TaskStatus.ERRORED
                || task.status() == TaskStatus.ARCHIVED
                || task.status().isDone()) {
            throw conflict("task " + task.id() + " is not at the Local Review push gate");
        }
        if (!humanOverride && openCommentCount(prId) > 0) {
            throw conflict("local PR " + prId + " has open comment threads");
        }
        if (!humanOverride && latestLocalCheckFailed(prId)) {
            throw conflict("local PR " + prId + " has a failing local test run");
        }

        Path worktree = worktree(task);
        String headSha = headSha(worktree);
        String fingerprint = fingerprints.fingerprint(worktree);
        ReviewRound basis = requireReviewBasis(task.id(), humanOverride);
        if (!fingerprint.equals(basis.codeFingerprint())) {
            commands.executeVoid(task.id(), () -> taskMachine.invalidateLocalShipInCommand(
                    task.id(), Actor.AGENT, "local_push_fingerprint_changed"));
            throw conflict("the current code differs from the completed Brain review; revalidation started");
        }
        PublishTarget target = resolvePublishTarget(task, pr);
        PushPayload payload = new PushPayload(
                pr.id(), task.id(), worktree.toString(), target.repo().owner(), target.repo().repo(),
                target.apiHead(), target.headFilter(), target.base(), pr.branchName(),
                pr.title(), pr.description(), headSha);
        String payloadJson = json(payload);
        Actor actor = humanOverride ? Actor.HUMAN : Actor.AGENT;
        String overrideReason = hasOpenFindings(basis) ? "explicit_user_override" : null;
        TaskPushAuthorization authorization = new TaskPushAuthorization(
                UUID.randomUUID().toString(), task.id(), pr.id(), basis.runId(), headSha,
                fingerprint, actor, TaskPushAuthorization.BASIS_BRAIN_REVIEW, basis.id(),
                overrideReason, payloadJson, sha256(payloadJson), json(EFFECT_ORDER), Instant.now(),
                null, null, null);

        TaskPushAuthorization accepted = commands.execute(
                task.id(), () -> authorizeInCommand(authorization));
        if (accepted == null) {
            throw conflict("task " + task.id() + " reached its automatic push limit");
        }
        return accepted;
    }

    private TaskPushAuthorization authorizeInCommand(TaskPushAuthorization authorization)
    {
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        Task task = requireTask(authorization.taskId());
        PR pr = requirePr(authorization.prId());
        TaskPushAuthorization existing = pushes.findActiveByTask(task.id()).orElse(null);
        if (existing != null) {
            return existing;
        }
        if (task.phase() != TaskPhase.AWAITING_PUSH
                || task.status() == TaskStatus.PAUSED
                || task.status() == TaskStatus.NEEDS_ATTENTION
                || task.status() == TaskStatus.ERRORED
                || task.status() == TaskStatus.ARCHIVED
                || task.status().isDone()
                || !PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            throw conflict("Local Review changed while Push was starting");
        }
        ReviewRound basis = rounds.findById(authorization.basisId()).orElse(null);
        if (basis == null
                || !basis.taskId().equals(task.id())
                || basis.status() != ReviewRoundState.CLOSED
                || !authorization.codeFingerprint().equals(basis.codeFingerprint())) {
            throw conflict("the completed Brain review is no longer the current push basis");
        }
        if (!taskMachine.spendLocalShipAuthorizationInCommand(
                task.id(), authorization.actor())) {
            return null;
        }
        notifications.supersedeAwaitingReviewForTask(task.threadId(), task.id());
        pushes.insert(
                authorization, authorizedEffectKeys(authorization), ATTEMPT_LIMIT);
        return authorization;
    }

    private void driveLocked(String token)
    {
        TaskPushAuthorization authorization = pushes.findAuthorization(token).orElse(null);
        if (authorization == null || !authorization.active()) {
            return;
        }
        Task task = requireTask(authorization.taskId());
        if (!runnableAtGate(task)) {
            return;
        }

        Path worktree = Path.of(payload(authorization).worktreePath());
        for (String effectKey : authorizedEffectKeys(authorization)) {
            TaskPushEffect effect = pushes.findEffect(token, effectKey).orElseThrow();
            if (effect.completed()) {
                continue;
            }
            if (effect.status() == TaskPushEffect.Status.PERMANENT_FAILED) {
                return;
            }
            if (effect.status() == TaskPushEffect.Status.IN_FLIGHT) {
                if (effect.leaseUntil() == null || effect.leaseUntil().isAfter(Instant.now())) {
                    return;
                }
            }
            if (effect.status() != TaskPushEffect.Status.IN_FLIGHT
                    && effect.exhausted()) {
                commands.executeVoid(authorization.taskId(), () ->
                        taskMachine.parkOperationalInCommand(
                                authorization.taskId(), Actor.AGENT,
                                "local_push_attempts_exhausted"));
                return;
            }

            // Delivery may be synchronous or a recovery wake; either way the
            // durable step is still pending until shared admission succeeds.
            Optional<RetiredSagaGate.Attempt> admitted = capacity.tryAcquire(
                    task.id(), capacityOperationId(effect),
                    Set.of(CapacityManager.CapacityLane.GITHUB));
            if (admitted.isEmpty()) {
                return;
            }
            try (RetiredSagaGate.Attempt attempt = admitted.orElseThrow()) {
                if (effect.status() == TaskPushEffect.Status.IN_FLIGHT) {
                    if (recoverExpiredClaim(authorization, effect, attempt)) {
                        continue;
                    }
                    return;
                }

                String owner = UUID.randomUUID().toString();
                boolean claimed = false;
                try {
                    attempt.requireLive();
                    ObservedCode observed = observeAuthorizedCode(authorization, worktree);
                    attempt.requireLive();
                    claimed = commands.execute(task.id(),
                            () -> claimInCommand(
                                    authorization, effectKey, owner, observed));
                    if (!claimed) {
                        return;
                    }
                    attempt.requireLive();
                    String evidence = performEffect(effectKey, authorization);
                    attempt.requireLive();
                    commands.executeVoid(task.id(), () -> completeEffectInCommand(
                            authorization, effectKey, owner, evidence));
                }
                catch (RuntimeException e) {
                    if (attempt.leaseLost()) {
                        // The remote outcome may be ambiguous. Keep an owned
                        // claim for the existing probe-before-retry path.
                        return;
                    }
                    if (claimed) {
                        failEffect(authorization, effectKey, owner, e);
                    }
                    throw e;
                }
            }
        }

        finalizeUnderCapacity(authorization, task.id(), worktree);
    }

    private void finalizeUnderCapacity(
            TaskPushAuthorization authorization, String taskId, Path worktree)
    {
        Optional<RetiredSagaGate.Attempt> admitted = capacity.tryAcquire(
                taskId,
                authorizationCapacityOperationId(authorization.token(), "finalize"),
                Set.of(CapacityManager.CapacityLane.GITHUB));
        if (admitted.isEmpty()) {
            return;
        }
        ObservedCode finalCode;
        RemoteEvidence remote;
        try (RetiredSagaGate.Attempt attempt = admitted.orElseThrow()) {
            try {
                attempt.requireLive();
                finalCode = observeAuthorizedCode(authorization, worktree);
                attempt.requireLive();
                remote = remoteEvidence(authorization.token());
                attempt.requireLive();
                commands.executeVoid(taskId, () -> finalizeInCommand(
                        authorization, finalCode.head(), finalCode.fingerprint(), remote));
            }
            catch (RuntimeException e) {
                if (!attempt.leaseLost()) {
                    throw e;
                }
                return;
            }
        }
    }

    private ObservedCode observeAuthorizedCode(
            TaskPushAuthorization authorization, Path worktree)
    {
        ObservedCode observed = new ObservedCode(
                headSha(worktree), fingerprints.fingerprint(worktree));
        if (authorization.headSha().equals(observed.head())
                && authorization.codeFingerprint().equals(observed.fingerprint())) {
            return observed;
        }
        commands.executeVoid(authorization.taskId(), () -> invalidateMismatchInCommand(
                authorization, observed.head(), observed.fingerprint()));
        throw conflict("the reviewed code changed before Push completed");
    }

    /** An expired IN_FLIGHT claim is an ambiguity, not permission to replay.
     * Probe the exact remote marker first; only a proven miss releases the
     * cursor for a bounded retry. */
    private boolean recoverExpiredClaim(
            TaskPushAuthorization authorization,
            TaskPushEffect effect,
            RetiredSagaGate.Attempt attempt)
    {
        String evidence;
        try {
            attempt.requireLive();
            evidence = probeExpiredEffect(authorization, effect.effectKey());
            attempt.requireLive();
        }
        catch (RuntimeException e) {
            if (attempt.leaseLost()) {
                return false;
            }
            failEffect(authorization, effect.effectKey(), effect.claimOwner(), e);
            return false;
        }
        if (evidence != null) {
            commands.executeVoid(authorization.taskId(), () ->
                    completeEffectInCommand(
                            authorization, effect.effectKey(),
                            effect.claimOwner(), evidence));
            return true;
        }
        failEffect(
                authorization, effect.effectKey(), effect.claimOwner(),
                new IllegalStateException("expired push effect was absent remotely"));
        return false;
    }

    static String capacityOperationId(TaskPushEffect effect)
    {
        return "legacy-task-push-effect:" + effect.id();
    }

    private static String authorizationCapacityOperationId(String token, String action)
    {
        return "legacy-task-push-authorization:" + token + ":" + action;
    }

    private String probeExpiredEffect(
            TaskPushAuthorization authorization, String effectKey)
    {
        PushPayload payload = payload(authorization);
        if (EFFECT_PUSH_BRANCH.equals(effectKey)) {
            try {
                return git.remoteHeadSha(
                                Path.of(payload.worktreePath()), "origin", payload.branchName())
                        .filter(authorization.headSha()::equals)
                        .map(PushEvidence::new)
                        .map(this::json)
                        .orElse(null);
            }
            catch (IOException e) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "probing pushed branch failed: " + e.getMessage());
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "probing pushed branch was interrupted");
            }
        }
        if (EFFECT_ENSURE_PULL_REQUEST.equals(effectKey)) {
            RepoRef repo = new RepoRef(payload.repoOwner(), payload.repoName());
            return findExistingOpenPullRequest(pats.resolve(repo.fullName()), repo, payload)
                    .map(opened -> new RemoteEvidence(
                            opened.repo(), opened.number(), opened.htmlUrl(), opened.author()))
                    .map(this::json)
                    .orElse(null);
        }
        throw new IllegalArgumentException("unknown push effect: " + effectKey);
    }

    private boolean claimInCommand(
            TaskPushAuthorization authorization,
            String effectKey,
            String owner,
            ObservedCode observed)
    {
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        Task task = requireTask(authorization.taskId());
        TaskPushAuthorization current = pushes.findAuthorization(authorization.token()).orElse(null);
        if (current == null || !current.active() || !runnableAtGate(task)
                || !current.headSha().equals(observed.head())
                || !current.codeFingerprint().equals(observed.fingerprint())) {
            return false;
        }
        Instant now = Instant.now();
        return pushes.claimEffect(
                authorization.token(), effectKey, owner, now, now.plus(CLAIM_LEASE));
    }

    private void completeEffectInCommand(
            TaskPushAuthorization authorization, String effectKey, String owner, String evidence)
    {
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        if (!pushes.completeEffect(
                authorization.token(), effectKey, owner, evidence, Instant.now())) {
            throw conflict("push effect " + effectKey + " lost its claim");
        }
    }

    private void failEffect(
            TaskPushAuthorization authorization, String effectKey, String owner, RuntimeException failure)
    {
        commands.executeVoid(authorization.taskId(), () -> {
            TaskPushEffect claimed = pushes.findEffect(
                    authorization.token(), effectKey).orElseThrow();
            boolean permanent = permanent(failure) || claimed.exhausted();
            TaskPushEffect.Status status = permanent
                    ? TaskPushEffect.Status.PERMANENT_FAILED
                    : TaskPushEffect.Status.RETRYABLE_FAILED;
            Instant retryAt = permanent ? null
                    : Instant.now().plus(retryDelay(claimed.attempts()));
            String reason = safeMessage(failure);
            if (!pushes.failEffect(
                    authorization.token(), effectKey, owner, status,
                    failure.getClass().getName(), reason, retryAt)) {
                return;
            }
            if (permanent) {
                prs.recordPushFailureInCommand(authorization.prId(), effectKey, reason);
                taskMachine.parkOperationalInCommand(
                        authorization.taskId(), Actor.AGENT, "local_push_failed");
            }
        });
    }

    private void invalidateMismatchInCommand(
            TaskPushAuthorization authorization, String head, String fingerprint)
    {
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        if (pushes.revokeIfUnclaimed(
                authorization.token(), "code_changed", Instant.now())) {
            taskMachine.invalidateLocalShipInCommand(
                    authorization.taskId(), Actor.AGENT, "local_push_fingerprint_changed");
            return;
        }
        taskMachine.parkOperationalInCommand(
                authorization.taskId(), Actor.AGENT, "local_push_fingerprint_changed");
        log.warn("push {} parked after claimed-effect fingerprint mismatch: expected {}/{} observed {}/{}",
                authorization.token(), authorization.headSha(), authorization.codeFingerprint(),
                head, fingerprint);
    }

    private void finalizeInCommand(
            TaskPushAuthorization authorization,
            String observedHead,
            String observedFingerprint,
            RemoteEvidence remote)
    {
        TaskCommandExecutor.requireCurrent(authorization.taskId());
        TaskPushAuthorization current = pushes.findAuthorization(authorization.token()).orElse(null);
        if (current == null || !current.active()) {
            return;
        }
        if (!current.headSha().equals(observedHead)
                || !current.codeFingerprint().equals(observedFingerprint)) {
            throw conflict("push authorization fingerprint changed before finalization");
        }
        if (pushes.findEffects(current.token()).stream().anyMatch(effect -> !effect.completed())) {
            return;
        }
        Task task = requireTask(current.taskId());
        PR pr = requirePr(current.prId());
        if (!runnableAtGate(task) || !PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            return;
        }
        submissions.cancelOpenForTask(task.id(), "local_pr_pushed", Instant.now());
        tasks.markPushed(task.id(), Instant.now());
        tasks.linkPullRequest(task.id(), remote.number(), LINKED_STATUS_DRAFT);
        tasks.linkTaskToPr(task.id(), remote.repo() + "#" + remote.number());
        PR pushed = prs.recordPushInCommand(
                pr.id(), remote.repo(), remote.number(), remote.url());
        prs.updateAuthor(pushed.id(), actorLabel(remote.author()));
        if (!pushes.consumeIfComplete(
                current.token(), TaskPushAuthorization.OUTCOME_PUSHED, Instant.now())) {
            throw conflict("push authorization changed before finalization");
        }
        taskMachine.finalizeLocalShipInCommand(
                task.id(), current.actor(), "local_pr_pushed");
    }

    private String performEffect(String effectKey, TaskPushAuthorization authorization)
    {
        PushPayload payload = payload(authorization);
        if (EFFECT_PUSH_BRANCH.equals(effectKey)) {
            pushBranch(Path.of(payload.worktreePath()));
            return json(new PushEvidence(payload.headSha()));
        }
        if (EFFECT_ENSURE_PULL_REQUEST.equals(effectKey)) {
            PullRequest opened = ensurePullRequest(payload);
            return json(new RemoteEvidence(
                    opened.repo(), opened.number(), opened.htmlUrl(), opened.author()));
        }
        throw new IllegalArgumentException("unknown push effect: " + effectKey);
    }

    private PullRequest ensurePullRequest(PushPayload payload)
    {
        RepoRef repo = new RepoRef(payload.repoOwner(), payload.repoName());
        String pat = pats.resolve(repo.fullName());
        Optional<PullRequest> existing = findExistingOpenPullRequest(pat, repo, payload);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        try {
            PullRequest opened = pullRequests.createPullRequest(
                    pat, repo, CreatePullRequestCommand.draft(
                            payload.apiHead(), payload.base(), payload.title(), payload.description()));
            if (opened == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "GitHub did not return the opened PR");
            }
            return opened;
        }
        catch (RuntimeException createFailure) {
            return findExistingOpenPullRequest(pat, repo, payload).orElseThrow(() -> createFailure);
        }
    }

    private Optional<PullRequest> findExistingOpenPullRequest(
            String pat, RepoRef repo, PushPayload payload)
    {
        ListPullRequestsQuery query = new ListPullRequestsQuery(
                "open", Optional.of(payload.headFilter()), Optional.of(payload.base()),
                "created", "desc", 10, 1);
        return pullRequests.listPullRequests(pat, repo, query).stream()
                .filter(candidate -> repo.fullName().equalsIgnoreCase(candidate.repo()))
                .filter(candidate -> "open".equalsIgnoreCase(candidate.state()))
                .filter(candidate -> payload.branchName().equals(candidate.headRef()))
                .findFirst();
    }

    private RemoteEvidence remoteEvidence(String token)
    {
        TaskPushEffect effect = pushes.findEffect(token, EFFECT_ENSURE_PULL_REQUEST)
                .filter(TaskPushEffect::completed)
                .orElseThrow(() -> conflict("push has no durable remote PR evidence"));
        try {
            return mapper.readValue(effect.evidenceJson(), RemoteEvidence.class);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid remote PR evidence for push " + token, e);
        }
    }

    private ReviewRound requireReviewBasis(String taskId, boolean humanOverride)
    {
        ReviewRound basis = rounds.findByTask(taskId).stream()
                .filter(round -> ReviewRound.ORIGIN_BRAIN.equals(round.origin()))
                .filter(round -> round.status() == ReviewRoundState.CLOSED)
                .findFirst()
                .orElseThrow(() -> conflict("task " + taskId + " has no completed Brain review"));
        if (!humanOverride && (hasOpenFindings(basis)
                || !ReviewRound.VERDICT_APPROVED.equals(basis.brainVerdict()))) {
            throw conflict("automatic Push requires a clean Brain approval");
        }
        return basis;
    }

    private static boolean hasOpenFindings(ReviewRound round)
    {
        return round.stats() != null && round.stats().open() > 0
                || !ReviewRound.VERDICT_APPROVED.equals(round.brainVerdict());
    }

    private long openCommentCount(String prId)
    {
        return prs.comments(prId).stream()
                .filter(comment -> comment.parentCommentId() == null)
                .filter(comment -> comment.resolvedAt() == null && comment.dismissedAt() == null)
                .filter(comment -> comment.strippedOnPushAt() == null)
                .count();
    }

    private boolean latestLocalCheckFailed(String prId)
    {
        List<PRCheck> checks = prs.checks(prId);
        return checks.stream()
                .filter(check -> PRCheck.KIND_LOCAL.equals(check.kind()))
                .max(Comparator.comparing(PRCheck::startedAt))
                .map(check -> PRCheck.STATUS_FAILED.equals(check.status()))
                .orElse(false);
    }

    private PublishTarget resolvePublishTarget(Task task, PR pr)
    {
        Path repoRoot = Path.of(task.workingDir());
        RepoRef origin = originSlug(task);
        WatchedRepo watched = watchedRepos.findAll().stream()
                .filter(candidate -> candidate.localClonePath() != null
                        && !candidate.localClonePath().isBlank()
                        && Path.of(candidate.localClonePath()).equals(repoRoot))
                .findFirst()
                .orElse(null);
        boolean fork = watched != null
                && watched.upstreamRemoteName() != null
                && !watched.upstreamRemoteName().isBlank();
        if (!fork) {
            return new PublishTarget(
                    origin, pr.branchName(), origin.owner() + ":" + pr.branchName(), pr.baseBranch());
        }
        RepoRef upstream = new RepoRef(watched.owner(), watched.repo());
        String base = upstreamDefaultBranch(repoRoot, watched.upstreamRemoteName())
                .orElse(pr.baseBranch());
        String head = origin.owner() + ":" + pr.branchName();
        return new PublishTarget(upstream, head, head, base);
    }

    private Optional<String> upstreamDefaultBranch(Path repoRoot, String remote)
    {
        try {
            return git.defaultBranch(repoRoot, remote).filter(branch -> !branch.isBlank());
        }
        catch (IOException | RuntimeException e) {
            log.warn("resolving default branch for {} in {} failed: {}",
                    remote, repoRoot, e.getMessage());
            return Optional.empty();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private RepoRef originSlug(Task task)
    {
        try {
            return git.remoteSlug(Path.of(task.workingDir()), "origin")
                    .orElseThrow(() -> conflict(
                            "could not resolve origin repo for task " + task.id()));
        }
        catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "reading origin remote failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "interrupted resolving origin remote");
        }
    }

    private void pushBranch(Path worktree)
    {
        try {
            git.push(worktree);
        }
        catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "git push failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "git push interrupted");
        }
    }

    private String headSha(Path worktree)
    {
        try {
            return git.headSha(worktree);
        }
        catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "reading task HEAD failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "reading task HEAD interrupted");
        }
    }

    private PushPayload payload(TaskPushAuthorization authorization)
    {
        try {
            PushPayload payload = mapper.readValue(authorization.payloadJson(), PushPayload.class);
            if (!authorization.payloadDigest().equals(sha256(authorization.payloadJson()))) {
                throw new IllegalStateException("push payload digest mismatch for " + authorization.token());
            }
            return payload;
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid push payload for " + authorization.token(), e);
        }
    }

    private List<String> authorizedEffectKeys(TaskPushAuthorization authorization)
    {
        try {
            List<?> values = mapper.readValue(authorization.effectKeysJson(), List.class);
            List<String> keys = values.stream().map(String::valueOf).toList();
            if (keys.isEmpty() || !authorization.effectKeysJson().equals(json(keys))) {
                throw new IllegalStateException(
                        "push effect-key payload is not canonical: " + authorization.token());
            }
            return keys;
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "invalid effect keys for push " + authorization.token(), e);
        }
    }

    private String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("serializing push saga payload failed", e);
        }
    }

    private PR requirePr(String prId)
    {
        return prs.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no local PR " + prId));
    }

    private Task requireTask(String taskId)
    {
        return tasks.findTaskById(taskId)
                .orElseThrow(() -> conflict("no task " + taskId));
    }

    private static Path worktree(Task task)
    {
        String path = task.worktreePath() == null || task.worktreePath().isBlank()
                ? task.workingDir() : task.worktreePath();
        return Path.of(path);
    }

    private static boolean runnableAtGate(Task task)
    {
        if (task.phase() != TaskPhase.AWAITING_PUSH) {
            return false;
        }
        return switch (task.status()) {
            case IDLE, AWAITING_REVIEW -> true;
            default -> false;
        };
    }

    private static boolean permanent(RuntimeException failure)
    {
        if (!(failure instanceof ResponseStatusException response)) {
            return false;
        }
        int status = response.getStatusCode().value();
        return status >= 400 && status < 500 && status != 408 && status != 409 && status != 429;
    }

    private static Duration retryDelay(int attempts)
    {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 5);
        Duration delay = RETRY_DELAY.multipliedBy(multiplier);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private static String safeMessage(Throwable failure)
    {
        String message = failure.getMessage();
        if (message == null) {
            return failure.getClass().getSimpleName();
        }
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private static String actorLabel(String githubLogin)
    {
        return githubLogin == null || githubLogin.isBlank() ? null : "@" + githubLogin;
    }

    private static String sha256(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static ResponseStatusException conflict(String message)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void rejectLegacyRuntime()
    {
        throw conflict("TaskPushSaga is retired; V2 publish is owned by the typed remote runtime");
    }

    private record PublishTarget(RepoRef repo, String apiHead, String headFilter, String base) {}

    private record PushPayload(
            String prId,
            String taskId,
            String worktreePath,
            String repoOwner,
            String repoName,
            String apiHead,
            String headFilter,
            String base,
            String branchName,
            String title,
            String description,
            String headSha) {}

    private record PushEvidence(String headSha) {}

    private record ObservedCode(String head, String fingerprint) {}

    private record RemoteEvidence(String repo, int number, String url, String author) {}

    private record OrphanAdoption(
            TaskPushAuthorization authorization,
            String pushEvidence,
            String pullRequestEvidence,
            RemoteEvidence remote) {}

    /** Immutable payload persisted on an EXTERNAL_SAGA recovery request. */
    public record RecoveryPlan(
            String token,
            String effectKey,
            String reason,
            int addedAllowance,
            String headSha,
            String codeFingerprint) {}
}
