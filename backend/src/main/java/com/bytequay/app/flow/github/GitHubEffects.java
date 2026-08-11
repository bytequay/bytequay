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
package com.bytequay.app.flow.github;

import com.bytequay.app.flow.github.GitHubEffectRecords.EffectKind;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectAttempt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectPlan;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectProbe;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectReceipt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectStep;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProviderObservation;
import com.bytequay.app.flow.github.GitHubEffectRecords.StepKind;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.PublishExecutionHandle;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Concrete owner of immutable, ordered GitHub effect plans. */
public final class GitHubEffects
{
    public static final int MAX_MUTATION_ATTEMPTS = 2;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FlowRuntime runtime;

    public GitHubEffects(DataSource dataSource, FlowRuntime runtime)
    {
        requireNonNull(dataSource, "dataSource is null");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    public static final class PreparedCiUpdatePlan
    {
        private final ExternalEffectPlan plan;
        private final ExternalEffectStep step;

        private PreparedCiUpdatePlan(
                ExternalEffectPlan plan, ExternalEffectStep step)
        {
            this.plan = plan;
            this.step = step;
        }

        public ExternalEffectPlan plan()
        {
            return plan;
        }

        public ExternalEffectStep step()
        {
            return step;
        }
    }

    public static final class ActivatedAttempt
    {
        private final ExternalEffectAttempt attempt;
        private final PublishExecutionHandle executionHandle;

        private ActivatedAttempt(
                ExternalEffectAttempt attempt,
                PublishExecutionHandle executionHandle)
        {
            this.attempt = requireNonNull(attempt, "attempt is null");
            this.executionHandle = requireNonNull(
                    executionHandle, "executionHandle is null");
        }

        public ExternalEffectAttempt attempt()
        {
            return attempt;
        }

        public PublishExecutionHandle executionHandle()
        {
            return executionHandle;
        }
    }

    public PreparedCiUpdatePlan prepareCiUpdatePlan(
            String authorizationId,
            String operationId,
            String prId,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String expectedRemoteHead,
            String proposedHead,
            String actionRef,
            String actionDigest,
            String requiredCiPolicyRevisionId,
            Instant createdAt)
    {
        requireText(authorizationId, "authorizationId");
        requireText(operationId, "operationId");
        requireText(prId, "prId");
        requireText(headRepositoryExternalId, "headRepositoryExternalId");
        requireText(headRepositoryOwner, "headRepositoryOwner");
        requireText(headRepositoryName, "headRepositoryName");
        requireText(branchRef, "branchRef");
        requireText(expectedRemoteHead, "expectedRemoteHead");
        requireText(proposedHead, "proposedHead");
        requireText(actionRef, "actionRef");
        requireText(actionDigest, "actionDigest");
        requireText(requiredCiPolicyRevisionId,
                "requiredCiPolicyRevisionId");
        requireNonNull(createdAt, "createdAt is null");
        return inTransaction(() -> {
            runtime.lockPublishedPullRequest(prId);
            Long sequence = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(pr_sequence), 0) + 1 "
                            + "FROM flow_github_external_effect_plan "
                            + "WHERE pr_id = ?",
                    Long.class,
                    prId);
            long prSequence = requireNonNull(
                    sequence, "prSequence is null");
            String planId = stableId(
                    "github-effect-plan:v1", authorizationId);
            String stepId = stableId(
                    "github-effect-step:v1", planId, "1");
            String preconditionDigest = stableId(
                    "github-push-precondition:v1",
                    branchRef,
                    expectedRemoteHead,
                    proposedHead,
                    "force:false");
            ExternalEffectStep step = new ExternalEffectStep(
                    stepId,
                    planId,
                    1,
                    StepKind.PUSH_EXACT,
                    headRepositoryExternalId,
                    headRepositoryOwner,
                    headRepositoryName,
                    branchRef,
                    expectedRemoteHead,
                    proposedHead,
                    false,
                    actionRef,
                    actionDigest,
                    preconditionDigest);
            String planDigest = stableId(
                    "github-effect-plan-digest:v1",
                    planId,
                    operationId,
                    authorizationId,
                    prId,
                    Long.toString(prSequence),
                    EffectKind.CI_UPDATE.name(),
                    headRepositoryExternalId,
                    headRepositoryOwner,
                    headRepositoryName,
                    expectedRemoteHead,
                    actionRef,
                    actionDigest,
                    requiredCiPolicyRevisionId,
                    "step-count:1",
                    step.stepId(),
                    step.kind().name(),
                    step.preconditionDigest());
            return new PreparedCiUpdatePlan(
                    new ExternalEffectPlan(
                            planId,
                            operationId,
                            authorizationId,
                            prId,
                            prSequence,
                            EffectKind.CI_UPDATE,
                            headRepositoryExternalId,
                            headRepositoryOwner,
                            headRepositoryName,
                            expectedRemoteHead,
                            actionRef,
                            actionDigest,
                            requiredCiPolicyRevisionId,
                            planDigest,
                            createdAt),
                    step);
        });
    }

    public ExternalEffectPlan insert(PreparedCiUpdatePlan prepared)
    {
        requireNonNull(prepared, "prepared is null");
        return inTransaction(() -> {
            ExternalEffectPlan plan = prepared.plan;
            ExternalEffectStep step = prepared.step;
            jdbc.update(
                    """
                    INSERT INTO flow_github_external_effect_plan (
                        plan_id, operation_id, authorization_id, pr_id,
                        pr_sequence, kind, head_repository_external_id,
                        head_repository_owner, head_repository_name,
                        expected_remote_head, action_ref,
                        action_digest, required_ci_policy_revision_id,
                        plan_digest, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'CI_UPDATE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    plan.planId(),
                    plan.operationId(),
                    plan.authorizationId(),
                    plan.prId(),
                    plan.prSequence(),
                    plan.headRepositoryExternalId(),
                    plan.headRepositoryOwner(),
                    plan.headRepositoryName(),
                    plan.expectedRemoteHead(),
                    plan.actionRef(),
                    plan.actionDigest(),
                    plan.requiredCiPolicyRevisionId(),
                    plan.planDigest(),
                    plan.createdAt().toEpochMilli());
            jdbc.update(
                    """
                    INSERT INTO flow_github_external_effect_step (
                        step_id, plan_id, ordinal, kind,
                        head_repository_external_id, head_repository_owner,
                        head_repository_name, branch_ref,
                        expected_remote_head, proposed_head, force_push,
                        action_ref, action_digest, precondition_digest
                    ) VALUES (?, ?, 1, 'PUSH_EXACT', ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                    """,
                    step.stepId(),
                    step.planId(),
                    step.headRepositoryExternalId(),
                    step.headRepositoryOwner(),
                    step.headRepositoryName(),
                    step.branchRef(),
                    step.expectedRemoteHead(),
                    step.proposedHead(),
                    step.actionRef(),
                    step.actionDigest(),
                    step.preconditionDigest());
            return requirePlan(plan.planId());
        });
    }

    public Optional<ExternalEffectPlan> plan(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query(
                "SELECT * FROM flow_github_external_effect_plan WHERE plan_id = ?",
                (result, row) -> readPlan(result),
                planId).stream().findFirst();
    }

    public Optional<ExternalEffectPlan> planForAuthorization(
            String authorizationId)
    {
        requireText(authorizationId, "authorizationId");
        return jdbc.query(
                "SELECT * FROM flow_github_external_effect_plan "
                        + "WHERE authorization_id = ?",
                (result, row) -> readPlan(result),
                authorizationId).stream().findFirst();
    }

    public List<ExternalEffectStep> steps(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query(
                "SELECT * FROM flow_github_external_effect_step "
                        + "WHERE plan_id = ? ORDER BY ordinal",
                (result, row) -> readStep(result),
                planId);
    }

    public List<ExternalEffectAttempt> attempts(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query(
                "SELECT * FROM flow_github_external_effect_attempt "
                        + "WHERE plan_id = ? ORDER BY attempt_number",
                (result, row) -> readAttempt(result),
                planId);
    }

    public Optional<ExternalEffectAttempt> attempt(String attemptId)
    {
        requireText(attemptId, "attemptId");
        return jdbc.query(
                "SELECT * FROM flow_github_external_effect_attempt "
                        + "WHERE attempt_id = ?",
                (result, row) -> readAttempt(result),
                attemptId).stream().findFirst();
    }

    public List<ExternalEffectProbe> probes(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query(
                "SELECT * FROM flow_github_external_effect_probe "
                        + "WHERE plan_id = ? "
                        + "ORDER BY claim_generation, probe_number",
                (result, row) -> readProbe(result),
                planId);
    }

    public Optional<ExternalEffectReceipt> receipt(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query(
                "SELECT * FROM flow_github_external_effect_receipt "
                        + "WHERE plan_id = ?",
                (result, row) -> readReceipt(result),
                planId).stream().findFirst();
    }

    public Optional<ExternalEffectReceipt> exactReceipt(String planId)
    {
        Optional<ExternalEffectReceipt> stored = receipt(planId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ExternalEffectPlan plan = requirePlan(planId);
        List<ExternalEffectStep> exactSteps = steps(planId);
        assertExactPlan(plan, exactSteps);
        ExternalEffectReceipt value = stored.orElseThrow();
        ExternalEffectProbe probe = probes(planId).stream()
                .filter(item -> item.probeId().equals(value.probeId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "GitHub effect receipt probe is missing"));
        if (probe.outcome() != ProbeOutcome.APPLIED
                || !Objects.equals(probe.attemptId(), value.attemptId())
                || !Objects.equals(probe.observedHead(),
                        value.proposedHead())) {
            throw new IllegalStateException(
                    "GitHub effect receipt proof is invalid");
        }
        if (probe.attemptId() != null) {
            ExternalEffectAttempt storedAttempt = attempt(
                    probe.attemptId()).orElseThrow(() ->
                            new IllegalStateException(
                                    "GitHub effect receipt attempt is missing"));
            assertExactAttemptGraph(
                    storedAttempt, plan, exactSteps.getFirst());
        }
        assertExactReceipt(
                value, plan, exactSteps.getFirst(), probe);
        return Optional.of(value);
    }

    public Optional<ExternalEffectReceipt> exactReceiptById(String receiptId)
    {
        requireText(receiptId, "receiptId");
        Optional<String> planId = jdbc.queryForList(
                        "SELECT plan_id FROM "
                                + "flow_github_external_effect_receipt "
                                + "WHERE receipt_id = ?",
                        String.class, receiptId).stream().findFirst();
        if (planId.isEmpty()) {
            return Optional.empty();
        }
        ExternalEffectReceipt exact = exactReceipt(
                planId.orElseThrow()).orElseThrow();
        if (!exact.receiptId().equals(receiptId)) {
            throw new IllegalStateException(
                    "GitHub receipt lookup changed identity");
        }
        return Optional.of(exact);
    }

    /** Persists one exact remote observation; no call result is accepted. */
    public ExternalEffectProbe recordObservation(
            Claim claim,
            ProviderObservation observation,
            Instant observedAt)
    {
        requireNonNull(observation, "observation is null");
        return recordProbe(
                claim, null, null, observation, observedAt);
    }

    public ExternalEffectProbe recordAttemptObservation(
            Claim claim,
            PublishExecutionHandle executionHandle,
            ExternalEffectAttempt attempt,
            ProviderObservation observation,
            Instant observedAt)
    {
        requireNonNull(executionHandle, "executionHandle is null");
        requireNonNull(attempt, "attempt is null");
        requireNonNull(observation, "observation is null");
        return recordProbe(
                claim, executionHandle, attempt, observation, observedAt);
    }

    private ExternalEffectProbe recordProbe(
            Claim claim,
            PublishExecutionHandle executionHandle,
            ExternalEffectAttempt attempt,
            ProviderObservation observation,
            Instant observedAt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(observation, "observation is null");
        requireNonNull(observedAt, "observedAt is null");
        return inTransaction(() -> {
            ExternalEffectAttempt stored = null;
            if (attempt != null) {
                stored = attempt(attempt.attemptId()).orElseThrow(() ->
                        new IllegalStateException(
                                "effect attempt is missing"));
                if (!stored.equals(attempt)) {
                    throw new IllegalStateException(
                            "effect attempt input differs from durable evidence");
                }
            }
            else if (observation.attemptId() != null) {
                stored = attempt(observation.attemptId()).orElseThrow(() ->
                        new IllegalStateException(
                                "provider observation attempt is missing"));
            }
            if (executionHandle == null) {
                runtime.assertPublishClaim(claim);
            }
            else {
                runtime.assertPublishAttemptResult(
                        executionHandle,
                        claim,
                        attempt.attemptId(),
                        attempt.executionTokenDigest());
            }
            ExternalEffectPlan plan = requirePlan(observation.planId());
            List<ExternalEffectStep> exactSteps = steps(plan.planId());
            assertExactPlan(plan, exactSteps);
            ExternalEffectStep step = exactSteps.getFirst();
            List<ExternalEffectAttempt> exactAttempts = attempts(plan.planId());
            String latestAttemptId = exactAttempts.isEmpty()
                    ? null : exactAttempts.getLast().attemptId();
            if (!Objects.equals(
                    observation.attemptId(), latestAttemptId)) {
                throw new IllegalStateException(
                        "provider observation does not bind the latest attempt");
            }
            assertObservation(
                    observation, claim, plan, step,
                    stored == null ? null : stored.attemptId());
            if (attempt != null) {
                assertExactAttempt(stored, claim, plan, step);
            }
            else if (stored != null) {
                assertExactAttemptGraph(stored, plan, step);
            }
            if (!plan.operationId().equals(claim.operationId())) {
                throw new IllegalStateException(
                        "probe claim does not own the plan");
            }
            Optional<ExternalEffectProbe> latest = probes(plan.planId()).stream()
                    .reduce((left, right) -> right);
            if (latest.isPresent()
                    && latest.orElseThrow().claimGeneration()
                            > claim.generation()) {
                throw new IllegalStateException(
                        "probe claim generation regressed");
            }
            ProbeOutcome outcome = observation.outcome();
            String observedHead = observation.observedHead();
            if (outcome == ProbeOutcome.UNKNOWN) {
                if (observedHead != null) {
                    throw new IllegalArgumentException(
                            "UNKNOWN probe cannot claim a head");
                }
            }
            else {
                if (outcome != ProbeOutcome.DIVERGED) {
                    requireText(observedHead, "observedHead");
                }
                if (outcome == ProbeOutcome.APPLIED
                        && !observedHead.equals(step.proposedHead())
                        || outcome == ProbeOutcome.ABSENT
                        && !observedHead.equals(step.expectedRemoteHead())
                        || outcome == ProbeOutcome.DIVERGED
                        && observedHead != null
                        && (observedHead.equals(step.expectedRemoteHead())
                                || observedHead.equals(step.proposedHead()))) {
                    throw new IllegalArgumentException(
                            "probe outcome does not match observed head");
                }
            }
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flow_github_external_effect_probe "
                            + "WHERE operation_id = ? AND claim_generation = ?",
                    Integer.class,
                    claim.operationId(),
                    claim.generation());
            int probeNumber = requireNonNull(
                    count, "probe count is null") + 1;
            String attemptId = observation.attemptId();
            String probeDigest = stableId(
                    "github-effect-probe:v1",
                    plan.planId(),
                    step.stepId(),
                    Long.toString(claim.generation()),
                    Integer.toString(probeNumber),
                    outcome.name(),
                    observedHead == null ? "" : observedHead);
            ExternalEffectProbe probe = new ExternalEffectProbe(
                    stableId("github-effect-probe-id:v1", probeDigest),
                    claim.operationId(),
                    plan.planId(),
                    step.stepId(),
                    attemptId,
                    claim.generation(),
                    probeNumber,
                    step.headRepositoryExternalId(),
                    step.headRepositoryOwner(),
                    step.headRepositoryName(),
                    step.branchRef(),
                    step.expectedRemoteHead(),
                    step.proposedHead(),
                    outcome,
                    observedHead,
                    probeDigest,
                    observedAt);
            jdbc.update(
                    """
                    INSERT INTO flow_github_external_effect_probe (
                        probe_id, operation_id, plan_id, step_id, attempt_id,
                        claim_generation, probe_number,
                        head_repository_external_id, head_repository_owner,
                        head_repository_name, branch_ref,
                        expected_remote_head, proposed_head, outcome,
                        observed_head, probe_digest, observed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    probe.probeId(),
                    probe.operationId(),
                    probe.planId(),
                    probe.stepId(),
                    probe.attemptId(),
                    probe.claimGeneration(),
                    probe.probeNumber(),
                    probe.headRepositoryExternalId(),
                    probe.headRepositoryOwner(),
                    probe.headRepositoryName(),
                    probe.branchRef(),
                    probe.expectedRemoteHead(),
                    probe.proposedHead(),
                    probe.outcome().name(),
                    probe.observedHead(),
                    probe.probeDigest(),
                    probe.observedAt().toEpochMilli());
            return probe;
        });
    }

    private static void assertObservation(
            ProviderObservation observation,
            Claim claim,
            ExternalEffectPlan plan,
            ExternalEffectStep step,
            String expectedAttemptId)
    {
        if (!observation.operationId().equals(claim.operationId())
                || !observation.matchesClaim(claim)
                || !observation.operationId().equals(plan.operationId())
                || !observation.planId().equals(plan.planId())
                || !Objects.equals(
                        observation.attemptId(), expectedAttemptId)
                || !observation.headRepositoryExternalId().equals(
                        step.headRepositoryExternalId())
                || !observation.headRepositoryOwner().equals(
                        step.headRepositoryOwner())
                || !observation.headRepositoryName().equals(
                        step.headRepositoryName())
                || !observation.branchRef().equals(step.branchRef())
                || !observation.expectedRemoteHead().equals(
                        step.expectedRemoteHead())
                || !observation.proposedHead().equals(
                        step.proposedHead())) {
            throw new IllegalStateException(
                    "provider observation does not match its exact plan");
        }
    }

    /** Reserves durable provider authority after an exact-expected probe. */
    public ActivatedAttempt activateAttempt(
            Claim claim, String planId, Instant activatedAt)
    {
        requireNonNull(claim, "claim is null");
        requireText(planId, "planId");
        requireNonNull(activatedAt, "activatedAt is null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "effect attempt activation requires its own transaction");
        }
        return inTransaction(() -> {
            runtime.assertPublishClaim(claim);
            ExternalEffectPlan plan = requirePlan(planId);
            List<ExternalEffectStep> exactSteps = steps(planId);
            assertExactPlan(plan, exactSteps);
            ExternalEffectStep step = exactSteps.getFirst();
            if (!plan.operationId().equals(claim.operationId())) {
                throw new IllegalStateException(
                        "attempt claim does not own the plan");
            }
            List<ExternalEffectAttempt> prior = attempts(planId);
            if (prior.size() >= MAX_MUTATION_ATTEMPTS) {
                throw new IllegalStateException(
                        "GitHub mutation attempt limit reached");
            }
            ExternalEffectProbe expectedProbe = probes(planId).stream()
                    .reduce((left, right) -> right)
                    .orElseThrow(() -> new IllegalStateException(
                            "exact-expected probe is missing"));
            if (expectedProbe.claimGeneration() != claim.generation()
                    || expectedProbe.outcome() != ProbeOutcome.ABSENT
                    || !step.expectedRemoteHead().equals(
                            expectedProbe.observedHead())) {
                throw new IllegalStateException(
                        "attempt requires the latest exact-expected probe");
            }
            int number = prior.size() + 1;
            String requestDigest = stableId(
                    "github-push-request:v1",
                    plan.planId(),
                    step.stepId(),
                    Integer.toString(number),
                    step.headRepositoryExternalId(),
                    step.headRepositoryOwner(),
                    step.headRepositoryName(),
                    step.branchRef(),
                    step.expectedRemoteHead(),
                    step.proposedHead(),
                    "force:false");
            String attemptId = stableId(
                    "github-effect-attempt:v1",
                    plan.planId(), Integer.toString(number));
            PublishExecutionHandle handle =
                    runtime.mintPublishExecutionHandle(claim, attemptId);
            ExternalEffectAttempt attempt = new ExternalEffectAttempt(
                    attemptId,
                    claim.operationId(),
                    plan.planId(),
                    step.stepId(),
                    number,
                    claim.generation(),
                    stableId("publish-claim-token:v1", claim.claimToken()),
                    step.headRepositoryExternalId(),
                    step.headRepositoryOwner(),
                    step.headRepositoryName(),
                    step.branchRef(),
                    step.expectedRemoteHead(),
                    step.proposedHead(),
                    requestDigest,
                    handle.tokenDigest(),
                    activatedAt);
            jdbc.update(
                    """
                    INSERT INTO flow_github_external_effect_attempt (
                        attempt_id, operation_id, plan_id, step_id,
                        attempt_number, claim_generation, claim_token_digest,
                        head_repository_external_id, head_repository_owner,
                        head_repository_name, branch_ref,
                        expected_remote_head, proposed_head, request_digest,
                        execution_token_digest, activated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    attempt.attemptId(),
                    attempt.operationId(),
                    attempt.planId(),
                    attempt.stepId(),
                    attempt.attemptNumber(),
                    attempt.claimGeneration(),
                    attempt.claimTokenDigest(),
                    attempt.headRepositoryExternalId(),
                    attempt.headRepositoryOwner(),
                    attempt.headRepositoryName(),
                    attempt.branchRef(),
                    attempt.expectedRemoteHead(),
                    attempt.proposedHead(),
                    attempt.requestDigest(),
                    attempt.executionTokenDigest(),
                    attempt.activatedAt().toEpochMilli());
            ExternalEffectAttempt stored = this.attempt(attemptId)
                    .orElseThrow();
            assertExactAttempt(stored, claim, plan, step);
            return new ActivatedAttempt(stored, handle);
        });
    }

    private static void assertExactAttempt(
            ExternalEffectAttempt attempt,
            Claim claim,
            ExternalEffectPlan plan,
            ExternalEffectStep step)
    {
        String requestDigest = stableId(
                "github-push-request:v1",
                plan.planId(),
                step.stepId(),
                Integer.toString(attempt.attemptNumber()),
                step.headRepositoryExternalId(),
                step.headRepositoryOwner(),
                step.headRepositoryName(),
                step.branchRef(),
                step.expectedRemoteHead(),
                step.proposedHead(),
                "force:false");
        if (!attempt.attemptId().equals(stableId(
                    "github-effect-attempt:v1",
                    plan.planId(), Integer.toString(attempt.attemptNumber())))
                || !attempt.operationId().equals(plan.operationId())
                || !attempt.operationId().equals(claim.operationId())
                || !attempt.planId().equals(plan.planId())
                || !attempt.stepId().equals(step.stepId())
                || attempt.claimGeneration() != claim.generation()
                || !attempt.claimTokenDigest().equals(stableId(
                        "publish-claim-token:v1", claim.claimToken()))
                || !attempt.headRepositoryExternalId().equals(
                        step.headRepositoryExternalId())
                || !attempt.headRepositoryOwner().equals(
                        step.headRepositoryOwner())
                || !attempt.headRepositoryName().equals(
                        step.headRepositoryName())
                || !attempt.branchRef().equals(step.branchRef())
                || !attempt.expectedRemoteHead().equals(
                        step.expectedRemoteHead())
                || !attempt.proposedHead().equals(step.proposedHead())
                || !attempt.requestDigest().equals(requestDigest)) {
            throw new IllegalStateException(
                    "GitHub effect attempt graph is invalid");
        }
    }

    private static void assertExactAttemptGraph(
            ExternalEffectAttempt attempt,
            ExternalEffectPlan plan,
            ExternalEffectStep step)
    {
        String requestDigest = stableId(
                "github-push-request:v1",
                plan.planId(),
                step.stepId(),
                Integer.toString(attempt.attemptNumber()),
                step.headRepositoryExternalId(),
                step.headRepositoryOwner(),
                step.headRepositoryName(),
                step.branchRef(),
                step.expectedRemoteHead(),
                step.proposedHead(),
                "force:false");
        if (!attempt.attemptId().equals(stableId(
                    "github-effect-attempt:v1",
                    plan.planId(), Integer.toString(attempt.attemptNumber())))
                || !attempt.operationId().equals(plan.operationId())
                || !attempt.planId().equals(plan.planId())
                || !attempt.stepId().equals(step.stepId())
                || !attempt.headRepositoryExternalId().equals(
                        step.headRepositoryExternalId())
                || !attempt.headRepositoryOwner().equals(
                        step.headRepositoryOwner())
                || !attempt.headRepositoryName().equals(
                        step.headRepositoryName())
                || !attempt.branchRef().equals(step.branchRef())
                || !attempt.expectedRemoteHead().equals(
                        step.expectedRemoteHead())
                || !attempt.proposedHead().equals(step.proposedHead())
                || !attempt.requestDigest().equals(requestDigest)) {
            throw new IllegalStateException(
                    "GitHub effect attempt graph is invalid");
        }
    }

    public ExternalEffectReceipt insertReceipt(
            ExternalEffectPlan plan,
            ExternalEffectStep step,
            ExternalEffectProbe probe,
            Instant recordedAt)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(step, "step is null");
        requireNonNull(probe, "probe is null");
        requireNonNull(recordedAt, "recordedAt is null");
        return inTransaction(() -> {
            Optional<ExternalEffectReceipt> existing = receipt(plan.planId());
            if (existing.isPresent()) {
                ExternalEffectReceipt replay = existing.orElseThrow();
                assertExactReceipt(replay, plan, step, probe);
                return replay;
            }
            if (probe.outcome() != ProbeOutcome.APPLIED
                    || !probe.operationId().equals(plan.operationId())
                    || !probe.planId().equals(plan.planId())
                    || !probe.stepId().equals(step.stepId())
                    || !step.proposedHead().equals(probe.observedHead())) {
                throw new IllegalStateException(
                        "receipt requires exact APPLIED proof");
            }
            String digest = stableId(
                    "github-effect-receipt:v1",
                    plan.operationId(),
                    plan.planId(),
                    step.stepId(),
                    probe.attemptId() == null ? "" : probe.attemptId(),
                    probe.probeId(),
                    step.headRepositoryExternalId(),
                    step.headRepositoryOwner(),
                    step.headRepositoryName(),
                    step.branchRef(),
                    step.expectedRemoteHead(),
                    step.proposedHead());
            ExternalEffectReceipt receipt = new ExternalEffectReceipt(
                    stableId("github-effect-receipt-id:v1", digest),
                    plan.operationId(),
                    plan.planId(),
                    step.stepId(),
                    probe.attemptId(),
                    probe.probeId(),
                    step.headRepositoryExternalId(),
                    step.headRepositoryOwner(),
                    step.headRepositoryName(),
                    step.branchRef(),
                    step.expectedRemoteHead(),
                    step.proposedHead(),
                    digest,
                    recordedAt);
            jdbc.update(
                    """
                    INSERT INTO flow_github_external_effect_receipt (
                        receipt_id, operation_id, plan_id, step_id, attempt_id,
                        probe_id, probe_outcome, observed_head,
                        head_repository_external_id,
                        head_repository_owner, head_repository_name,
                        branch_ref, expected_remote_head, proposed_head,
                        receipt_digest, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'APPLIED', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    receipt.receiptId(),
                    receipt.operationId(),
                    receipt.planId(),
                    receipt.stepId(),
                    receipt.attemptId(),
                    receipt.probeId(),
                    receipt.proposedHead(),
                    receipt.headRepositoryExternalId(),
                    receipt.headRepositoryOwner(),
                    receipt.headRepositoryName(),
                    receipt.branchRef(),
                    receipt.expectedRemoteHead(),
                    receipt.proposedHead(),
                    receipt.receiptDigest(),
                    receipt.recordedAt().toEpochMilli());
            return receipt;
        });
    }

    private static void assertExactReceipt(
            ExternalEffectReceipt receipt,
            ExternalEffectPlan plan,
            ExternalEffectStep step,
            ExternalEffectProbe probe)
    {
        String digest = stableId(
                "github-effect-receipt:v1",
                plan.operationId(),
                plan.planId(),
                step.stepId(),
                probe.attemptId() == null ? "" : probe.attemptId(),
                probe.probeId(),
                step.headRepositoryExternalId(),
                step.headRepositoryOwner(),
                step.headRepositoryName(),
                step.branchRef(),
                step.expectedRemoteHead(),
                step.proposedHead());
        if (!receipt.operationId().equals(plan.operationId())
                || !receipt.planId().equals(plan.planId())
                || !receipt.stepId().equals(step.stepId())
                || !Objects.equals(
                        receipt.attemptId(), probe.attemptId())
                || !receipt.probeId().equals(probe.probeId())
                || !receipt.headRepositoryExternalId().equals(
                        step.headRepositoryExternalId())
                || !receipt.headRepositoryOwner().equals(
                        step.headRepositoryOwner())
                || !receipt.headRepositoryName().equals(
                        step.headRepositoryName())
                || !receipt.branchRef().equals(step.branchRef())
                || !receipt.expectedRemoteHead().equals(
                        step.expectedRemoteHead())
                || !receipt.proposedHead().equals(step.proposedHead())
                || !receipt.receiptDigest().equals(digest)
                || !receipt.receiptId().equals(stableId(
                        "github-effect-receipt-id:v1", digest))) {
            throw new IllegalStateException(
                    "GitHub effect receipt graph is invalid");
        }
    }

    /** Recomputes the immutable one-step plan digests on every graph read. */
    public void assertExactPlan(
            ExternalEffectPlan plan, List<ExternalEffectStep> steps)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(steps, "steps is null");
        if (steps.size() != 1) {
            throw new IllegalStateException(
                    "CI_UPDATE plan must contain exactly one step");
        }
        ExternalEffectStep step = steps.getFirst();
        String planId = stableId(
                "github-effect-plan:v1", plan.authorizationId());
        String stepId = stableId(
                "github-effect-step:v1", plan.planId(), "1");
        String preconditionDigest = stableId(
                "github-push-precondition:v1",
                step.branchRef(),
                step.expectedRemoteHead(),
                step.proposedHead(),
                "force:false");
        String planDigest = stableId(
                "github-effect-plan-digest:v1",
                plan.planId(),
                plan.operationId(),
                plan.authorizationId(),
                plan.prId(),
                Long.toString(plan.prSequence()),
                plan.kind().name(),
                plan.headRepositoryExternalId(),
                plan.headRepositoryOwner(),
                plan.headRepositoryName(),
                plan.expectedRemoteHead(),
                plan.actionRef(),
                plan.actionDigest(),
                plan.requiredCiPolicyRevisionId(),
                "step-count:1",
                step.stepId(),
                step.kind().name(),
                step.preconditionDigest());
        if (!plan.planId().equals(planId)
                || !step.planId().equals(plan.planId())
                || !step.stepId().equals(stepId)
                || step.ordinal() != 1
                || step.kind() != StepKind.PUSH_EXACT
                || step.forcePush()
                || !step.headRepositoryExternalId().equals(
                        plan.headRepositoryExternalId())
                || !step.headRepositoryOwner().equals(
                        plan.headRepositoryOwner())
                || !step.headRepositoryName().equals(
                        plan.headRepositoryName())
                || !step.expectedRemoteHead().equals(
                        plan.expectedRemoteHead())
                || !step.actionRef().equals(plan.actionRef())
                || !step.actionDigest().equals(plan.actionDigest())
                || !step.preconditionDigest().equals(preconditionDigest)
                || !plan.planDigest().equals(planDigest)) {
            throw new IllegalStateException(
                    "GitHub effect plan digest graph is invalid");
        }
    }

    private ExternalEffectPlan requirePlan(String planId)
    {
        return plan(planId).orElseThrow(() ->
                new IllegalStateException("GitHub effect plan is missing"));
    }

    private static ExternalEffectPlan readPlan(ResultSet result)
            throws SQLException
    {
        return new ExternalEffectPlan(
                result.getString("plan_id"),
                result.getString("operation_id"),
                result.getString("authorization_id"),
                result.getString("pr_id"),
                result.getLong("pr_sequence"),
                EffectKind.valueOf(result.getString("kind")),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"),
                result.getString("head_repository_name"),
                result.getString("expected_remote_head"),
                result.getString("action_ref"),
                result.getString("action_digest"),
                result.getString("required_ci_policy_revision_id"),
                result.getString("plan_digest"),
                Instant.ofEpochMilli(result.getLong("created_at")));
    }

    private static ExternalEffectStep readStep(ResultSet result)
            throws SQLException
    {
        return new ExternalEffectStep(
                result.getString("step_id"),
                result.getString("plan_id"),
                result.getInt("ordinal"),
                StepKind.valueOf(result.getString("kind")),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"),
                result.getString("head_repository_name"),
                result.getString("branch_ref"),
                result.getString("expected_remote_head"),
                result.getString("proposed_head"),
                result.getInt("force_push") != 0,
                result.getString("action_ref"),
                result.getString("action_digest"),
                result.getString("precondition_digest"));
    }

    private static ExternalEffectAttempt readAttempt(ResultSet result)
            throws SQLException
    {
        return new ExternalEffectAttempt(
                result.getString("attempt_id"),
                result.getString("operation_id"),
                result.getString("plan_id"),
                result.getString("step_id"),
                result.getInt("attempt_number"),
                result.getLong("claim_generation"),
                result.getString("claim_token_digest"),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"),
                result.getString("head_repository_name"),
                result.getString("branch_ref"),
                result.getString("expected_remote_head"),
                result.getString("proposed_head"),
                result.getString("request_digest"),
                result.getString("execution_token_digest"),
                Instant.ofEpochMilli(result.getLong("activated_at")));
    }

    private static ExternalEffectProbe readProbe(ResultSet result)
            throws SQLException
    {
        return new ExternalEffectProbe(
                result.getString("probe_id"),
                result.getString("operation_id"),
                result.getString("plan_id"),
                result.getString("step_id"),
                result.getString("attempt_id"),
                result.getLong("claim_generation"),
                result.getInt("probe_number"),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"),
                result.getString("head_repository_name"),
                result.getString("branch_ref"),
                result.getString("expected_remote_head"),
                result.getString("proposed_head"),
                ProbeOutcome.valueOf(result.getString("outcome")),
                result.getString("observed_head"),
                result.getString("probe_digest"),
                Instant.ofEpochMilli(result.getLong("observed_at")));
    }

    private static ExternalEffectReceipt readReceipt(ResultSet result)
            throws SQLException
    {
        return new ExternalEffectReceipt(
                result.getString("receipt_id"),
                result.getString("operation_id"),
                result.getString("plan_id"),
                result.getString("step_id"),
                result.getString("attempt_id"),
                result.getString("probe_id"),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"),
                result.getString("head_repository_name"),
                result.getString("branch_ref"),
                result.getString("expected_remote_head"),
                result.getString("proposed_head"),
                result.getString("receipt_digest"),
                Instant.ofEpochMilli(result.getLong("recorded_at")));
    }

    private static String stableId(String... fields)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length)
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private <T> T inTransaction(Supplier<T> work)
    {
        return requireNonNull(
                transactions.execute(ignored -> work.get()),
                "transaction returned null");
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
