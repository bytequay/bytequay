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

import com.bytequay.app.flow.gate.UserGates.InitialBranchStaleDisposition;
import com.bytequay.app.flow.github.GitHubEffectRecords.EffectKind;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectAttempt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectPlan;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectProbe;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectReceipt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectStep;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProviderObservation;
import com.bytequay.app.flow.github.GitHubEffectRecords.StepKind;
import com.bytequay.app.flow.github.GitHubInitialPublishExecutor.SettlementRequired;
import com.bytequay.app.flow.github.InitialPublishRecords.Attempt;
import com.bytequay.app.flow.github.InitialPublishRecords.FinalReceipt;
import com.bytequay.app.flow.github.InitialPublishRecords.Outcome;
import com.bytequay.app.flow.github.InitialPublishRecords.Plan;
import com.bytequay.app.flow.github.InitialPublishRecords.PrIdentity;
import com.bytequay.app.flow.github.InitialPublishRecords.Probe;
import com.bytequay.app.flow.github.InitialPublishRecords.ProviderProof;
import com.bytequay.app.flow.github.InitialPublishRecords.Settlement;
import com.bytequay.app.flow.github.InitialPublishRecords.StepReceipt;
import com.bytequay.app.flow.github.InitialPublishRecords.TargetSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.PublishExecutionHandle;
import com.bytequay.app.flow.runtime.FlowRuntime.PublishExecutionReservation;
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
import java.util.ArrayList;
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

    public enum InitialRecoveryKind
    {
        NEVER_STARTED,
        PROBE_ONLY,
        COMPLETE
    }
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

    public static final class StoredInitialFinalReceipt
            implements Settlement
    {
        private final FinalReceipt receipt;

        private StoredInitialFinalReceipt(FinalReceipt receipt)
        {
            this.receipt = requireNonNull(receipt, "receipt is null");
        }

        public FinalReceipt receipt() { return receipt; }
        @Override public String resultId() { return receipt.receiptId(); }
        @Override public String operationId() { return receipt.operationId(); }
        @Override public String planId() { return receipt.planId(); }
        @Override public String proposedHead() { return receipt.proposedHead(); }
        @Override public boolean succeeded() { return true; }
        @Override public boolean bindsIdentity() { return true; }
        @Override public String attentionReason() { return null; }
    }

    public static final class StoredInitialPartialReceipt
            implements Settlement
    {
        private final String partialReceiptId;
        private final String operationId;
        private final String planId;
        private final String proposedHead;
        private final boolean bindsIdentity;
        private final String attentionReason;

        private StoredInitialPartialReceipt(
                String partialReceiptId, String operationId, String planId,
                String proposedHead, boolean bindsIdentity,
                String attentionReason)
        {
            this.partialReceiptId = requireNonNull(
                    partialReceiptId, "partialReceiptId is null");
            this.operationId = requireNonNull(operationId, "operationId is null");
            this.planId = requireNonNull(planId, "planId is null");
            this.proposedHead = requireNonNull(proposedHead, "proposedHead is null");
            this.bindsIdentity = bindsIdentity;
            this.attentionReason = requireNonNull(
                    attentionReason, "attentionReason is null");
        }

        @Override public String resultId() { return partialReceiptId; }
        @Override public String operationId() { return operationId; }
        @Override public String planId() { return planId; }
        @Override public String proposedHead() { return proposedHead; }
        @Override public boolean succeeded() { return false; }
        @Override public boolean bindsIdentity() { return bindsIdentity; }
        @Override public String attentionReason() { return attentionReason; }
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

    public static final class PreparedInitialPublishPlan
    {
        private final Plan plan;
        private final List<InitialPublishRecords.Step> steps;

        private PreparedInitialPublishPlan(
                Plan plan, List<InitialPublishRecords.Step> steps)
        {
            this.plan = requireNonNull(plan, "plan is null");
            this.steps = List.copyOf(steps);
        }

        public Plan plan()
        {
            return plan;
        }

        public List<InitialPublishRecords.Step> steps()
        {
            return steps;
        }
    }

    /** Opaque provider input for a read-only probe of the owner-derived step. */
    public static final class InitialProbeTarget
    {
        private final Claim claim;
        private final Plan plan;
        private final InitialPublishRecords.Step step;
        private final String latestAttemptId;

        private InitialProbeTarget(Claim claim, Plan plan,
                InitialPublishRecords.Step step, String latestAttemptId)
        {
            this.claim = requireNonNull(claim, "claim is null");
            this.plan = requireNonNull(plan, "plan is null");
            this.step = requireNonNull(step, "step is null");
            this.latestAttemptId = latestAttemptId;
        }

        String operationId() { return plan.operationId(); }
        String planId() { return plan.planId(); }
        String stepId() { return step.stepId(); }
        String attemptId() { return latestAttemptId; }
        int stepOrdinal() { return step.ordinal(); }
        InitialPublishRecords.StepKind stepKind() { return step.kind(); }
        Plan plan() { return plan; }
        InitialPublishRecords.Step step() { return step; }
        boolean matchesClaim(Claim candidate) { return claim.equals(candidate); }
    }

    /** Opaque one-call mutation authority retained with its durable attempt. */
    public static final class ActivatedInitialAttempt
    {
        private final InitialProbeTarget target;
        private final Attempt attempt;
        private final PublishExecutionHandle executionHandle;

        private ActivatedInitialAttempt(InitialProbeTarget target,
                Attempt attempt, PublishExecutionHandle executionHandle)
        {
            this.target = requireNonNull(target, "target is null");
            this.attempt = requireNonNull(attempt, "attempt is null");
            this.executionHandle = requireNonNull(
                    executionHandle, "executionHandle is null");
        }

        public Attempt attempt() { return attempt; }
        InitialProbeTarget target() { return target; }
        public PublishExecutionHandle executionHandle() { return executionHandle; }
    }

    public TargetSnapshot storeInitialTargetSnapshot(TargetSnapshot snapshot)
    {
        requireNonNull(snapshot, "snapshot is null");
        String digest = stableId(
                "github-initial-target:v1", snapshot.taskId(),
                snapshot.prId(), snapshot.repositoryId(),
                snapshot.launchDigest(), snapshot.baseRepositoryExternalId(),
                snapshot.baseRepositoryOwner(), snapshot.baseRepositoryName(),
                snapshot.headRepositoryExternalId(),
                snapshot.headRepositoryOwner(), snapshot.headRepositoryName(),
                snapshot.headBranchName(), snapshot.branchRef(),
                snapshot.targetBaseRef(), snapshot.expectedBaseSha(),
                snapshot.proposedHead(),
                snapshot.requiredCiPolicyRevisionId());
        if (!snapshot.targetSnapshotDigest().equals(digest)
                || !snapshot.targetSnapshotId().equals(stableId(
                        "github-initial-target-id:v1", digest))) {
            throw new IllegalArgumentException(
                    "initial target snapshot digest is invalid");
        }
        return inTransaction(() -> {
            jdbc.update(
                    """
                    INSERT OR IGNORE INTO flow_github_initial_publish_target_snapshot (
                        target_snapshot_id, task_id, pr_id, repository_id,
                        launch_digest, base_repository_external_id,
                        base_repository_owner, base_repository_name,
                        head_repository_external_id, head_repository_owner,
                        head_repository_name, head_branch_name, branch_ref,
                        target_base_ref, expected_base_sha, proposed_head,
                        required_ci_policy_revision_id,
                        target_snapshot_digest, observed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    snapshot.targetSnapshotId(), snapshot.taskId(),
                    snapshot.prId(), snapshot.repositoryId(),
                    snapshot.launchDigest(),
                    snapshot.baseRepositoryExternalId(),
                    snapshot.baseRepositoryOwner(),
                    snapshot.baseRepositoryName(),
                    snapshot.headRepositoryExternalId(),
                    snapshot.headRepositoryOwner(),
                    snapshot.headRepositoryName(), snapshot.headBranchName(),
                    snapshot.branchRef(), snapshot.targetBaseRef(),
                    snapshot.expectedBaseSha(), snapshot.proposedHead(),
                    snapshot.requiredCiPolicyRevisionId(),
                    snapshot.targetSnapshotDigest(),
                    snapshot.observedAt().toEpochMilli());
            TargetSnapshot stored = initialTargetSnapshot(
                    snapshot.targetSnapshotId()).orElseThrow();
            if (!stored.equals(snapshot)
                    && !stored.equals(new TargetSnapshot(
                            snapshot.targetSnapshotId(), snapshot.taskId(),
                            snapshot.prId(), snapshot.repositoryId(),
                            snapshot.launchDigest(),
                            snapshot.baseRepositoryExternalId(),
                            snapshot.baseRepositoryOwner(),
                            snapshot.baseRepositoryName(),
                            snapshot.headRepositoryExternalId(),
                            snapshot.headRepositoryOwner(),
                            snapshot.headRepositoryName(),
                            snapshot.headBranchName(), snapshot.branchRef(),
                            snapshot.targetBaseRef(), snapshot.expectedBaseSha(),
                            snapshot.proposedHead(),
                            snapshot.requiredCiPolicyRevisionId(),
                            snapshot.targetSnapshotDigest(), stored.observedAt()))) {
                throw new IllegalStateException(
                        "initial target snapshot replay conflicts");
            }
            return stored;
        });
    }

    public static String initialTargetSnapshotDigest(
            String taskId, String prId, String repositoryId, String launchDigest,
            String baseExternalId, String baseOwner, String baseName,
            String headExternalId, String headOwner, String headName,
            String headBranch, String branchRef, String targetBaseRef,
            String expectedBaseSha, String proposedHead, String policyRevisionId)
    {
        return stableId("github-initial-target:v1", taskId, prId, repositoryId,
                launchDigest, baseExternalId, baseOwner, baseName, headExternalId,
                headOwner, headName, headBranch, branchRef, targetBaseRef,
                expectedBaseSha, proposedHead, policyRevisionId);
    }

    public static String initialTargetSnapshotId(String digest)
    {
        return stableId("github-initial-target-id:v1", digest);
    }

    public Optional<TargetSnapshot> initialTargetSnapshot(String snapshotId)
    {
        requireText(snapshotId, "snapshotId");
        return jdbc.query(
                "SELECT * FROM flow_github_initial_publish_target_snapshot "
                        + "WHERE target_snapshot_id = ?",
                (result, row) -> new TargetSnapshot(
                        result.getString("target_snapshot_id"),
                        result.getString("task_id"),
                        result.getString("pr_id"),
                        result.getString("repository_id"),
                        result.getString("launch_digest"),
                        result.getString("base_repository_external_id"),
                        result.getString("base_repository_owner"),
                        result.getString("base_repository_name"),
                        result.getString("head_repository_external_id"),
                        result.getString("head_repository_owner"),
                        result.getString("head_repository_name"),
                        result.getString("head_branch_name"),
                        result.getString("branch_ref"),
                        result.getString("target_base_ref"),
                        result.getString("expected_base_sha"),
                        result.getString("proposed_head"),
                        result.getString("required_ci_policy_revision_id"),
                        result.getString("target_snapshot_digest"),
                        Instant.ofEpochMilli(result.getLong("observed_at"))),
                snapshotId).stream().findFirst();
    }

    public void assertExactInitialTargetSnapshot(TargetSnapshot snapshot)
    {
        requireNonNull(snapshot, "snapshot is null");
        String digest = stableId("github-initial-target:v1", snapshot.taskId(),
                snapshot.prId(), snapshot.repositoryId(), snapshot.launchDigest(),
                snapshot.baseRepositoryExternalId(), snapshot.baseRepositoryOwner(),
                snapshot.baseRepositoryName(), snapshot.headRepositoryExternalId(),
                snapshot.headRepositoryOwner(), snapshot.headRepositoryName(),
                snapshot.headBranchName(), snapshot.branchRef(), snapshot.targetBaseRef(),
                snapshot.expectedBaseSha(), snapshot.proposedHead(),
                snapshot.requiredCiPolicyRevisionId());
        if (!snapshot.targetSnapshotDigest().equals(digest)
                || !snapshot.targetSnapshotId().equals(stableId(
                        "github-initial-target-id:v1", digest))) {
            throw new IllegalStateException("initial target snapshot is corrupt");
        }
    }

    public PreparedInitialPublishPlan prepareInitialPublishPlan(
            String authorizationId,
            String operationId,
            String prId,
            String changeSetRevisionId,
            String draftRevisionId,
            String draftDigest,
            String readyPolicy,
            String actionRef,
            String actionDigest,
            String targetSnapshotId,
            Instant createdAt)
    {
        requireText(authorizationId, "authorizationId");
        requireText(operationId, "operationId");
        requireText(prId, "prId");
        requireText(changeSetRevisionId, "changeSetRevisionId");
        requireText(draftRevisionId, "draftRevisionId");
        requireText(draftDigest, "draftDigest");
        requireText(readyPolicy, "readyPolicy");
        requireText(actionRef, "actionRef");
        requireText(actionDigest, "actionDigest");
        requireText(targetSnapshotId, "targetSnapshotId");
        requireNonNull(createdAt, "createdAt is null");
        TargetSnapshot target = initialTargetSnapshot(targetSnapshotId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "initial target snapshot is missing"));
        if (!target.prId().equals(prId)) {
            throw new IllegalArgumentException("target owns another PR");
        }
        return inTransaction(() -> {
            var localPr = runtime.pullRequest(prId).filter(pr -> !pr.published())
                    .orElseThrow(() -> new IllegalStateException(
                            "initial publication PR is unavailable"));
            if (!localPr.prId().equals(target.prId())) {
                throw new IllegalStateException("initial target PR changed");
            }
            Long next = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(pr_sequence), 0) + 1 "
                            + "FROM flow_github_effect_plan_envelope "
                            + "WHERE pr_id = ?",
                    Long.class, prId);
            long sequence = requireNonNull(next, "prSequence is null");
            String planId = stableId("github-initial-plan:v1", authorizationId);
            var branch = initialStep(planId, 1,
                    InitialPublishRecords.StepKind.CREATE_REF_EXACT);
            var draft = initialStep(planId, 2,
                    InitialPublishRecords.StepKind.CREATE_DRAFT_PR);
            String digest = stableId(
                    "github-initial-plan-digest:v1", planId, operationId,
                    authorizationId, prId, Long.toString(sequence),
                    target.baseRepositoryExternalId(),
                    target.baseRepositoryOwner(), target.baseRepositoryName(),
                    target.headRepositoryExternalId(),
                    target.headRepositoryOwner(), target.headRepositoryName(),
                    target.branchRef(), target.targetBaseRef(),
                    target.expectedBaseSha(), target.proposedHead(),
                    changeSetRevisionId, draftRevisionId, draftDigest,
                    target.requiredCiPolicyRevisionId(), readyPolicy,
                    target.targetSnapshotId(), target.targetSnapshotDigest(),
                    actionRef, actionDigest,
                    branch.stepDigest(), draft.stepDigest());
            Plan plan = new Plan(
                    planId, operationId, authorizationId, prId, sequence,
                    target.baseRepositoryExternalId(),
                    target.baseRepositoryOwner(), target.baseRepositoryName(),
                    target.headRepositoryExternalId(),
                    target.headRepositoryOwner(), target.headRepositoryName(),
                    target.branchRef(), target.targetBaseRef(),
                    target.expectedBaseSha(), target.proposedHead(),
                    changeSetRevisionId, draftRevisionId, draftDigest,
                    target.requiredCiPolicyRevisionId(), readyPolicy,
                    target.targetSnapshotId(), target.targetSnapshotDigest(),
                    actionRef, actionDigest, digest, createdAt);
            return new PreparedInitialPublishPlan(
                    plan, List.of(branch, draft));
        });
    }

    public Plan insertInitialPublishPlan(PreparedInitialPublishPlan prepared)
    {
        requireNonNull(prepared, "prepared is null");
        return inTransaction(() -> {
            Plan plan = prepared.plan();
            jdbc.update(
                    """
                    INSERT INTO flow_github_effect_plan_envelope (
                        plan_id, operation_id, authorization_id, pr_id,
                        pr_sequence, kind, action_ref, action_digest,
                        plan_digest, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'INITIAL_PUBLISH', ?, ?, ?, ?)
                    """,
                    plan.planId(), plan.operationId(), plan.authorizationId(),
                    plan.prId(), plan.prSequence(), plan.actionRef(),
                    plan.actionDigest(), plan.planDigest(),
                    plan.createdAt().toEpochMilli());
            jdbc.update(
                    """
                    INSERT INTO flow_github_initial_publish_plan (
                        plan_id, operation_id, kind, authorization_id, pr_id,
                        base_repository_external_id, base_repository_owner,
                        base_repository_name, head_repository_external_id,
                        head_repository_owner, head_repository_name,
                        branch_ref, target_base_ref, expected_base_sha,
                        proposed_head, change_set_revision_id,
                        draft_revision_id, draft_digest,
                        required_ci_policy_revision_id, ready_policy,
                        target_snapshot_id, target_snapshot_digest,
                        action_ref, action_digest, plan_digest, created_at
                    ) VALUES (?, ?, 'INITIAL_PUBLISH', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    plan.planId(), plan.operationId(), plan.authorizationId(),
                    plan.prId(), plan.baseRepositoryExternalId(),
                    plan.baseRepositoryOwner(), plan.baseRepositoryName(),
                    plan.headRepositoryExternalId(),
                    plan.headRepositoryOwner(), plan.headRepositoryName(),
                    plan.branchRef(), plan.targetBaseRef(),
                    plan.expectedBaseSha(), plan.proposedHead(),
                    plan.changeSetRevisionId(), plan.draftRevisionId(),
                    plan.draftDigest(), plan.requiredCiPolicyRevisionId(),
                    plan.readyPolicy(), plan.targetSnapshotId(),
                    plan.targetSnapshotDigest(), plan.actionRef(),
                    plan.actionDigest(), plan.planDigest(),
                    plan.createdAt().toEpochMilli());
            for (var step : prepared.steps()) {
                jdbc.update(
                        "INSERT INTO flow_github_initial_publish_step "
                                + "(step_id, plan_id, ordinal, kind, step_digest) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        step.stepId(), step.planId(), step.ordinal(),
                        step.kind().name(), step.stepDigest());
            }
            return plan;
        });
    }

    private static InitialPublishRecords.Step initialStep(
            String planId, int ordinal,
            InitialPublishRecords.StepKind kind)
    {
        String digest = stableId(
                "github-initial-step:v1", planId,
                Integer.toString(ordinal), kind.name());
        return new InitialPublishRecords.Step(
                stableId("github-initial-step-id:v1", digest),
                planId, ordinal, kind, digest);
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
                            + "FROM flow_github_effect_plan_envelope "
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
                    INSERT INTO flow_github_effect_plan_envelope (
                        plan_id, operation_id, authorization_id, pr_id,
                        pr_sequence, kind, action_ref, action_digest,
                        plan_digest, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'CI_UPDATE', ?, ?, ?, ?)
                    """,
                    plan.planId(), plan.operationId(), plan.authorizationId(),
                    plan.prId(), plan.prSequence(), plan.actionRef(),
                    plan.actionDigest(), plan.planDigest(),
                    plan.createdAt().toEpochMilli());
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

    public Optional<Plan> initialPublishPlan(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query("SELECT p.*, e.pr_sequence FROM flow_github_initial_publish_plan p "
                        + "JOIN flow_github_effect_plan_envelope e ON e.plan_id = p.plan_id "
                        + "WHERE p.plan_id = ?", (result, row) -> new Plan(
                        result.getString("plan_id"), result.getString("operation_id"),
                        result.getString("authorization_id"), result.getString("pr_id"),
                        result.getLong("pr_sequence"),
                        result.getString("base_repository_external_id"),
                        result.getString("base_repository_owner"), result.getString("base_repository_name"),
                        result.getString("head_repository_external_id"),
                        result.getString("head_repository_owner"), result.getString("head_repository_name"),
                        result.getString("branch_ref"), result.getString("target_base_ref"),
                        result.getString("expected_base_sha"), result.getString("proposed_head"),
                        result.getString("change_set_revision_id"), result.getString("draft_revision_id"),
                        result.getString("draft_digest"), result.getString("required_ci_policy_revision_id"),
                        result.getString("ready_policy"), result.getString("target_snapshot_id"),
                        result.getString("target_snapshot_digest"), result.getString("action_ref"),
                        result.getString("action_digest"), result.getString("plan_digest"),
                        Instant.ofEpochMilli(result.getLong("created_at"))), planId).stream().findFirst();
    }

    public List<InitialPublishRecords.Step> initialPublishSteps(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query("SELECT * FROM flow_github_initial_publish_step "
                        + "WHERE plan_id = ? ORDER BY ordinal", (result, row) ->
                        new InitialPublishRecords.Step(result.getString("step_id"),
                                result.getString("plan_id"), result.getInt("ordinal"),
                                InitialPublishRecords.StepKind.valueOf(result.getString("kind")),
                                result.getString("step_digest")), planId);
    }

    public void assertExactInitialPublishPlan(Plan plan,
            List<InitialPublishRecords.Step> steps)
    {
        requireNonNull(plan, "plan is null");
        if (steps.size() != 2) {
            throw new IllegalStateException("initial plan has wrong step count");
        }
        InitialPublishRecords.Step branch = steps.get(0);
        InitialPublishRecords.Step draft = steps.get(1);
        String branchDigest = stableId("github-initial-step:v1", plan.planId(), "1",
                InitialPublishRecords.StepKind.CREATE_REF_EXACT.name());
        String draftDigest = stableId("github-initial-step:v1", plan.planId(), "2",
                InitialPublishRecords.StepKind.CREATE_DRAFT_PR.name());
        String expectedPlanId = stableId("github-initial-plan:v1", plan.authorizationId());
        String digest = stableId("github-initial-plan-digest:v1", plan.planId(),
                plan.operationId(), plan.authorizationId(), plan.prId(),
                Long.toString(plan.prSequence()), plan.baseRepositoryExternalId(),
                plan.baseRepositoryOwner(), plan.baseRepositoryName(),
                plan.headRepositoryExternalId(), plan.headRepositoryOwner(),
                plan.headRepositoryName(), plan.branchRef(), plan.targetBaseRef(),
                plan.expectedBaseSha(), plan.proposedHead(), plan.changeSetRevisionId(),
                plan.draftRevisionId(), plan.draftDigest(),
                plan.requiredCiPolicyRevisionId(), plan.readyPolicy(),
                plan.targetSnapshotId(), plan.targetSnapshotDigest(), plan.actionRef(),
                plan.actionDigest(), branchDigest, draftDigest);
        if (!plan.planId().equals(expectedPlanId) || !plan.planDigest().equals(digest)
                || !branch.planId().equals(plan.planId()) || branch.ordinal() != 1
                || branch.kind() != InitialPublishRecords.StepKind.CREATE_REF_EXACT
                || !branch.stepDigest().equals(branchDigest)
                || !branch.stepId().equals(stableId("github-initial-step-id:v1", branchDigest))
                || !draft.planId().equals(plan.planId()) || draft.ordinal() != 2
                || draft.kind() != InitialPublishRecords.StepKind.CREATE_DRAFT_PR
                || !draft.stepDigest().equals(draftDigest)
                || !draft.stepId().equals(stableId("github-initial-step-id:v1", draftDigest))) {
            throw new IllegalStateException("initial plan is corrupt");
        }
        TargetSnapshot target = initialTargetSnapshot(plan.targetSnapshotId()).orElseThrow(
                () -> new IllegalStateException("initial target snapshot is missing"));
        assertExactInitialTargetSnapshot(target);
        if (!target.prId().equals(plan.prId())
                || !target.targetSnapshotDigest().equals(plan.targetSnapshotDigest())
                || !target.baseRepositoryExternalId().equals(
                        plan.baseRepositoryExternalId())
                || !target.baseRepositoryOwner().equals(
                        plan.baseRepositoryOwner())
                || !target.baseRepositoryName().equals(
                        plan.baseRepositoryName())
                || !target.headRepositoryExternalId().equals(
                        plan.headRepositoryExternalId())
                || !target.headRepositoryOwner().equals(
                        plan.headRepositoryOwner())
                || !target.headRepositoryName().equals(
                        plan.headRepositoryName())
                || !target.branchRef().equals(plan.branchRef())
                || !target.targetBaseRef().equals(plan.targetBaseRef())
                || !target.expectedBaseSha().equals(plan.expectedBaseSha())
                || !target.proposedHead().equals(plan.proposedHead())
                || !target.requiredCiPolicyRevisionId().equals(
                        plan.requiredCiPolicyRevisionId())) {
            throw new IllegalStateException("initial plan target is inconsistent");
        }
    }

    public List<Attempt> initialPublishAttempts(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query(
                """
                SELECT a.*, p.plan_digest, s.step_digest,
                       p.base_repository_external_id,
                       p.base_repository_owner, p.base_repository_name,
                       p.head_repository_external_id,
                       p.head_repository_owner, p.head_repository_name,
                       p.branch_ref, p.target_base_ref, p.expected_base_sha,
                       p.proposed_head, p.change_set_revision_id,
                       p.draft_revision_id, p.draft_digest,
                       p.target_snapshot_id, p.target_snapshot_digest,
                       p.action_ref, p.action_digest
                FROM flow_github_initial_publish_attempt a
                JOIN flow_github_initial_publish_plan p
                  ON p.plan_id = a.plan_id AND p.operation_id = a.operation_id
                JOIN flow_github_initial_publish_step s
                  ON s.step_id = a.step_id AND s.plan_id = a.plan_id
                 AND s.ordinal = a.step_ordinal AND s.kind = a.step_kind
                WHERE a.plan_id = ?
                ORDER BY a.step_ordinal, a.attempt_number
                """,
                (result, row) -> readInitialAttempt(result), planId);
    }

    public Optional<Attempt> initialPublishAttempt(String attemptId)
    {
        requireText(attemptId, "attemptId");
        return jdbc.query(
                """
                SELECT a.*, p.plan_digest, s.step_digest,
                       p.base_repository_external_id,
                       p.base_repository_owner, p.base_repository_name,
                       p.head_repository_external_id,
                       p.head_repository_owner, p.head_repository_name,
                       p.branch_ref, p.target_base_ref, p.expected_base_sha,
                       p.proposed_head, p.change_set_revision_id,
                       p.draft_revision_id, p.draft_digest,
                       p.target_snapshot_id, p.target_snapshot_digest,
                       p.action_ref, p.action_digest
                FROM flow_github_initial_publish_attempt a
                JOIN flow_github_initial_publish_plan p
                  ON p.plan_id = a.plan_id AND p.operation_id = a.operation_id
                JOIN flow_github_initial_publish_step s
                  ON s.step_id = a.step_id AND s.plan_id = a.plan_id
                 AND s.ordinal = a.step_ordinal AND s.kind = a.step_kind
                WHERE a.attempt_id = ?
                """,
                (result, row) -> readInitialAttempt(result), attemptId)
                .stream().findFirst();
    }

    public List<Probe> initialPublishProbes(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query(
                """
                SELECT q.*, i.probe_id AS identity_probe_id,
                       i.state, i.draft,
                       i.base_repository_external_id,
                       i.base_repository_owner, i.base_repository_name,
                       i.head_repository_external_id,
                       i.head_repository_owner, i.head_repository_name,
                       i.head_branch_ref, i.target_base_ref, i.pr_number,
                       i.pr_node_id, i.html_url, i.observed_base_sha,
                       i.observed_title_digest, i.observed_body_digest,
                       i.first_pass_digest, i.second_pass_digest
                FROM flow_github_initial_publish_probe q
                LEFT JOIN flow_github_initial_pr_observation i
                  ON i.probe_id = q.probe_id
                WHERE q.plan_id = ?
                ORDER BY q.claim_generation, q.probe_number
                """,
                (result, row) -> readInitialProbe(result), planId);
    }

    public List<StepReceipt> initialPublishStepReceipts(String planId)
    {
        requireText(planId, "planId");
        return jdbc.query(
                """
                SELECT r.*, p.plan_digest, p.branch_ref, p.target_base_ref,
                       p.expected_base_sha, p.draft_revision_id,
                       p.draft_digest, s.step_digest,
                       d.receipt_id AS detail_receipt_id,
                       d.state, d.draft,
                       d.base_repository_external_id,
                       d.base_repository_owner, d.base_repository_name,
                       d.head_repository_external_id,
                       d.head_repository_owner, d.head_repository_name,
                       d.head_branch_ref, d.target_base_ref AS detail_base_ref,
                       d.pr_number, d.pr_node_id, d.html_url,
                       d.observed_base_sha, d.observed_title_digest,
                       d.observed_body_digest, d.first_pass_digest,
                       d.second_pass_digest
                FROM flow_github_initial_publish_step_receipt r
                JOIN flow_github_initial_publish_plan p
                  ON p.plan_id = r.plan_id AND p.operation_id = r.operation_id
                JOIN flow_github_initial_publish_step s
                  ON s.step_id = r.step_id AND s.plan_id = r.plan_id
                 AND s.ordinal = r.step_ordinal AND s.kind = r.step_kind
                LEFT JOIN flow_github_initial_pr_receipt_detail d
                  ON d.receipt_id = r.receipt_id
                WHERE r.plan_id = ?
                ORDER BY r.step_ordinal
                """,
                (result, row) -> readInitialReceipt(result), planId);
    }

    /** Reloads and validates the exact complete two-step handoff graph. */
    public StepReceipt requireCompleteInitialPublishReceipt(
            Claim claim, String planId)
    {
        requireNonNull(claim, "claim is null");
        requireText(planId, "planId");
        return inTransaction(() -> {
            var operation = runtime.assertPublishClaim(claim);
            Plan plan = requireInitialPlan(planId);
            if (!plan.operationId().equals(claim.operationId())
                    || !operation.inputRef().equals(plan.planId())
                    || !operation.subjectDigest().equals(plan.planDigest())
                    || currentInitialStep(plan).isPresent()) {
                throw new IllegalStateException(
                        "initial publication is not exactly complete");
            }
            List<StepReceipt> receipts = initialPublishStepReceipts(planId);
            if (receipts.size() != 2
                    || receipts.getLast().stepKind()
                        != InitialPublishRecords.StepKind.CREATE_DRAFT_PR
                    || receipts.getLast().prIdentity() == null) {
                throw new IllegalStateException(
                        "initial publication final receipt is invalid");
            }
            return receipts.getLast();
        });
    }

    /** Stores the exact terminal owner fact from the executor's sealed handoff. */
    public Settlement storeInitialSettlement(
            Claim claim,
            SettlementRequired handoff,
            Instant recordedAt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(handoff, "handoff is null");
        requireNonNull(recordedAt, "recordedAt is null");
        return inTransaction(() -> {
            Plan suppliedPlan = requireInitialPlan(
                    handoff.receipt().planId());
            List<StepReceipt> suppliedReceipts = initialPublishStepReceipts(
                    suppliedPlan.planId());
            currentInitialStep(suppliedPlan);
            if (suppliedReceipts.size() != 2
                    || !suppliedReceipts.getLast().equals(handoff.receipt())
                    || !suppliedPlan.operationId().equals(claim.operationId())) {
                throw new IllegalStateException(
                        "initial settlement handoff was substituted");
            }
            Optional<Settlement> replay = initialSettlement(claim.operationId());
            if (replay.isPresent()) {
                runtime.assertInitialSettlementReplay(
                        claim, replay.orElseThrow());
                return replay.orElseThrow();
            }
            StepReceipt prReceipt = requireCompleteInitialPublishReceipt(
                    claim, handoff.receipt().planId());
            if (!prReceipt.equals(handoff.receipt())) {
                throw new IllegalStateException(
                        "initial settlement handoff was substituted");
            }
            Plan plan = requireInitialPlan(prReceipt.planId());
            List<StepReceipt> receipts = initialPublishStepReceipts(plan.planId());
            StepReceipt branchReceipt = receipts.getFirst();
            PrIdentity identity = requireNonNull(
                    prReceipt.prIdentity(), "PR identity is missing");
            String blocker = identity.observedBaseSha().equals(
                    plan.expectedBaseSha())
                    ? currentInitialSettlementBlocker(plan)
                    : "REMOTE_BASE_DRIFT";
            if (blocker == null) {
                String digest = stableId("github-initial-final-receipt:v1",
                        plan.operationId(), plan.planId(), plan.planDigest(),
                        branchReceipt.receiptId(), branchReceipt.receiptDigest(),
                        prReceipt.receiptId(), prReceipt.receiptDigest(),
                        plan.proposedHead(), Long.toString(identity.prNumber()),
                        identity.prNodeId(), identity.htmlUrl(),
                        identity.observedBaseSha());
                String receiptId = stableId(
                        "github-initial-final-receipt-id:v1", digest);
                jdbc.update(
                        """
                        INSERT INTO flow_github_effect_receipt_envelope (
                            receipt_id, operation_id, plan_id, kind,
                            proposed_head, receipt_digest, recorded_at
                        ) VALUES (?, ?, ?, 'INITIAL_PUBLISH', ?, ?, ?)
                        """,
                        receiptId, plan.operationId(), plan.planId(),
                        plan.proposedHead(), digest, recordedAt.toEpochMilli());
                jdbc.update(
                        """
                        INSERT INTO flow_github_initial_publish_receipt (
                            receipt_id, operation_id, plan_id,
                            branch_receipt_id, branch_step_ordinal,
                            branch_step_kind, pr_step_receipt_id,
                            pr_step_ordinal, pr_step_kind, proposed_head,
                            pr_number, pr_node_id, html_url, observed_base_sha,
                            receipt_digest, recorded_at
                        ) VALUES (?, ?, ?, ?, 1, 'CREATE_REF_EXACT', ?, 2,
                                  'CREATE_DRAFT_PR', ?, ?, ?, ?, ?, ?, ?)
                        """,
                        receiptId, plan.operationId(), plan.planId(),
                        branchReceipt.receiptId(), prReceipt.receiptId(),
                        plan.proposedHead(), identity.prNumber(),
                        identity.prNodeId(), identity.htmlUrl(),
                        identity.observedBaseSha(), digest,
                        recordedAt.toEpochMilli());
            }
            else {
                insertInitialPartial(plan, branchReceipt, prReceipt,
                        "CREATED_PR_STALE", identity.observedBaseSha(),
                        null, null, null, null, blocker, blocker, recordedAt);
            }
            return initialSettlement(plan.operationId()).orElseThrow();
        });
    }

    private String currentInitialSettlementBlocker(Plan plan)
    {
        List<String[]> facts = jdbc.query(
                """
                SELECT t.status, t.current_head_sha,
                       t.current_change_set_revision_id, t.current_base_sha,
                       pr.remote_identity_id, pr.current_draft_revision_id,
                       cp.policy_revision_id
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket dt
                  ON dt.operation_id = o.operation_id
                JOIN flow_github_initial_publish_plan p
                  ON p.plan_id = ? AND p.operation_id = o.operation_id
                JOIN flow_user_gate_authorization a
                  ON a.authorization_id = ?
                 AND a.effect_plan_ref = p.plan_id
                 AND a.operation_id = p.operation_id
                 AND a.pr_id = p.pr_id
                 AND a.action_digest = p.action_digest
                JOIN flow_user_gate g
                  ON g.gate_id = a.gate_id AND g.current_revision = a.gate_revision
                 AND g.kind = 'INITIAL_PUBLISH'
                JOIN flow_user_gate_transition gt
                  ON gt.gate_id = g.gate_id AND gt.gate_revision = g.current_revision
                 AND gt.sequence = (
                     SELECT MAX(x.sequence) FROM flow_user_gate_transition x
                     WHERE x.gate_id = g.gate_id AND x.gate_revision = g.current_revision
                 ) AND gt.to_state IN ('EXECUTING', 'NEEDS_ATTENTION')
                JOIN flow_runtime_pr pr ON pr.pr_id = p.pr_id
                JOIN flow_runtime_task t
                  ON t.task_id = pr.task_id
                LEFT JOIN flow_ci_policy_current cp
                  ON cp.repository_id = pr.repository_id
                 AND cp.scope_key = pr.scope_key
                WHERE o.operation_id = p.operation_id
                  AND o.kind = 'PUBLISH' AND o.state = 'CLAIMED'
                  AND dt.delivery_state = 'CLAIMED'
                """,
                (result, row) -> new String[] {
                        result.getString("status"),
                        result.getString("current_head_sha"),
                        result.getString("current_change_set_revision_id"),
                        result.getString("current_base_sha"),
                        result.getString("remote_identity_id"),
                        result.getString("current_draft_revision_id"),
                        result.getString("policy_revision_id")},
                plan.planId(), plan.authorizationId());
        if (facts.size() != 1) {
            throw new IllegalStateException(
                    "initial settlement owner graph is inconsistent");
        }
        String[] value = facts.getFirst();
        if (!"ACTIVE".equals(value[0])) {
            throw new IllegalStateException(
                    "initial settlement crossed the publication barrier");
        }
        if (!plan.proposedHead().equals(value[1])
                || !plan.changeSetRevisionId().equals(value[2])) {
            return "TASK_HEAD_DRIFT";
        }
        if (!plan.expectedBaseSha().equals(value[3])) {
            return "TASK_BASE_DRIFT";
        }
        if (value[4] != null
                || !plan.draftRevisionId().equals(value[5])) {
            return "PR_OWNER_DRIFT";
        }
        if (!plan.requiredCiPolicyRevisionId().equals(value[6])) {
            return "REQUIRED_CI_POLICY_DRIFT";
        }
        return null;
    }

    /** Stores branch-only base drift proven before any PR attempt. */
    public Settlement storeInitialBranchOnlyBaseDrift(
            Claim claim,
            InitialPublishRecords.ProviderFailure failure,
            Instant recordedAt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(failure, "failure is null");
        requireNonNull(recordedAt, "recordedAt is null");
        return inTransaction(() -> {
            var operation = runtime.operation(claim.operationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "initial operation is missing"));
            Plan plan = requireInitialPlan(operation.inputRef());
            List<InitialPublishRecords.Step> steps =
                    initialPublishSteps(plan.planId());
            List<StepReceipt> receipts = initialPublishStepReceipts(plan.planId());
            if (receipts.size() != 1 || steps.size() != 2
                    || receipts.getFirst().stepKind()
                        != InitialPublishRecords.StepKind.CREATE_REF_EXACT
                    || !failure.baseDrift()
                    || !failure.matches(claim, plan.planId(),
                            steps.getLast().stepId(), null)
                    || failure.observedBaseSha() == null
                    || failure.observedBaseSha().equals(
                            plan.expectedBaseSha())) {
                throw new IllegalStateException(
                        "branch-only base drift proof is invalid");
            }
            Optional<Settlement> replay = initialSettlement(claim.operationId());
            if (replay.isPresent()) {
                runtime.assertInitialSettlementReplay(
                        claim, replay.orElseThrow());
                return replay.orElseThrow();
            }
            runtime.assertPublishClaim(claim);
            String preflightDigest = stableId(
                    "github-initial-base-preflight:v1",
                    plan.operationId(), plan.planId(), steps.getLast().stepId(),
                    Long.toString(claim.generation()),
                    stableId("publish-claim-token:v1", claim.claimToken()),
                    plan.expectedBaseSha(), failure.observedBaseSha());
            String preflightId = stableId(
                    "github-initial-base-preflight-id:v1", preflightDigest);
            jdbc.update(
                    """
                    INSERT INTO flow_github_initial_base_preflight (
                        preflight_id, operation_id, plan_id, step_id,
                        claim_generation, claim_token_digest,
                        expected_base_sha, observed_base_sha,
                        preflight_digest, observed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    preflightId, plan.operationId(), plan.planId(),
                    steps.getLast().stepId(), claim.generation(),
                    stableId("publish-claim-token:v1", claim.claimToken()),
                    plan.expectedBaseSha(), failure.observedBaseSha(),
                    preflightDigest, recordedAt.toEpochMilli());
            insertInitialPartial(plan, receipts.getFirst(), null,
                    "BRANCH_ONLY_BASE_DRIFT", failure.observedBaseSha(),
                    preflightId, claim.generation(),
                    stableId("publish-claim-token:v1", claim.claimToken()),
                    preflightDigest, "REMOTE_BASE_DRIFT",
                    "REMOTE_BASE_DRIFT", recordedAt);
            return initialSettlement(plan.operationId()).orElseThrow();
        });
    }

    /** Stores a stable post-branch stop derived by the program gate owner. */
    public Settlement storeInitialBranchOnlyStale(
            InitialBranchStaleDisposition disposition,
            Instant recordedAt)
    {
        requireNonNull(disposition, "disposition is null");
        requireNonNull(recordedAt, "recordedAt is null");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "branch-only stale settlement requires outer transaction");
        }
        Claim claim = disposition.claim();
        var operation = runtime.assertPublishClaim(claim);
        Plan plan = requireInitialPlan(disposition.planId());
        List<StepReceipt> receipts = initialPublishStepReceipts(plan.planId());
        List<InitialPublishRecords.Step> steps = initialPublishSteps(
                plan.planId());
        if (!operation.inputRef().equals(plan.planId())
                || !plan.operationId().equals(claim.operationId())
                || receipts.size() != 1 || steps.size() != 2
                || receipts.getFirst().stepKind()
                    != InitialPublishRecords.StepKind.CREATE_REF_EXACT
                || !receipts.getFirst().receiptId().equals(
                        disposition.branchReceiptId())
                || initialPublishAttempts(plan.planId()).stream()
                    .anyMatch(value -> value.stepId().equals(
                            steps.getLast().stepId()))) {
            throw new IllegalStateException(
                    "branch-only stale graph is invalid");
        }
        Optional<Settlement> replay = initialSettlement(claim.operationId());
        if (replay.isPresent()) {
            runtime.assertInitialSettlementReplay(claim, replay.orElseThrow());
            return replay.orElseThrow();
        }
        insertInitialPartial(plan, receipts.getFirst(), null,
                "BRANCH_ONLY_STALE", plan.expectedBaseSha(),
                null, null, null, null, disposition.reasonCode(),
                disposition.detail(), recordedAt);
        return initialSettlement(plan.operationId()).orElseThrow();
    }

    public Optional<Settlement> initialSettlement(String operationId)
    {
        requireText(operationId, "operationId");
        List<Settlement> rows = new ArrayList<>();
        rows.addAll(jdbc.query(
                "SELECT * FROM flow_github_initial_publish_receipt "
                        + "WHERE operation_id = ?",
                (result, row) -> new StoredInitialFinalReceipt(
                        readInitialFinalReceipt(result)), operationId));
        rows.addAll(jdbc.query(
                "SELECT * FROM flow_github_initial_publish_partial_receipt "
                        + "WHERE operation_id = ?",
                (result, row) -> new StoredInitialPartialReceipt(
                        result.getString("partial_receipt_id"),
                        result.getString("operation_id"),
                        result.getString("plan_id"),
                        result.getString("proposed_head"),
                        result.getString("pr_step_receipt_id") != null,
                        result.getString("attention_detail")),
                operationId));
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "initial publication has conflicting terminal evidence");
        }
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Settlement settlement = rows.getFirst();
        assertInitialSettlementGraph(settlement);
        return Optional.of(settlement);
    }

    private void insertInitialPartial(
            Plan plan, StepReceipt branchReceipt, StepReceipt prReceipt,
            String kind, String observedBaseSha, String basePreflightId,
            Long basePreflightGeneration, String basePreflightTokenDigest,
            String basePreflightDigest, String reasonCode,
            String attentionDetail, Instant recordedAt)
    {
        PrIdentity identity = prReceipt == null ? null
                : requireNonNull(prReceipt.prIdentity(), "PR identity is missing");
        String digest = stableId("github-initial-partial-receipt:v1",
                plan.operationId(), plan.planId(), plan.planDigest(), kind,
                reasonCode, attentionDetail,
                branchReceipt.receiptId(), branchReceipt.receiptDigest(),
                prReceipt == null ? "" : prReceipt.receiptId(),
                prReceipt == null ? "" : prReceipt.receiptDigest(),
                basePreflightId == null ? "" : basePreflightId,
                basePreflightGeneration == null ? ""
                        : Long.toString(basePreflightGeneration),
                basePreflightTokenDigest == null ? ""
                        : basePreflightTokenDigest,
                basePreflightDigest == null ? "" : basePreflightDigest,
                plan.proposedHead(), plan.expectedBaseSha(), observedBaseSha);
        jdbc.update(
                """
                INSERT INTO flow_github_initial_publish_partial_receipt (
                    partial_receipt_id, operation_id, plan_id, kind,
                    reason_code, attention_detail,
                    branch_receipt_id, branch_step_ordinal, branch_step_kind,
                    pr_step_receipt_id, pr_step_ordinal, pr_step_kind,
                    base_preflight_id, base_preflight_step_id,
                    base_preflight_claim_generation,
                    base_preflight_claim_token_digest, base_preflight_digest,
                    proposed_head, expected_base_sha, observed_base_sha,
                    pr_number, pr_node_id, html_url,
                    partial_digest, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'CREATE_REF_EXACT', ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                stableId("github-initial-partial-receipt-id:v1", digest),
                plan.operationId(), plan.planId(), kind, reasonCode,
                attentionDetail,
                branchReceipt.receiptId(),
                prReceipt == null ? null : prReceipt.receiptId(),
                prReceipt == null ? null : 2,
                prReceipt == null ? null : "CREATE_DRAFT_PR",
                basePreflightId,
                basePreflightId == null ? null
                        : initialPublishSteps(plan.planId()).getLast().stepId(),
                basePreflightGeneration, basePreflightTokenDigest,
                basePreflightDigest,
                plan.proposedHead(), plan.expectedBaseSha(), observedBaseSha,
                identity == null ? null : identity.prNumber(),
                identity == null ? null : identity.prNodeId(),
                identity == null ? null : identity.htmlUrl(),
                digest, recordedAt.toEpochMilli());
    }

    private void assertInitialSettlementGraph(Settlement settlement)
    {
        Plan plan = requireInitialPlan(settlement.planId());
        List<StepReceipt> receipts = initialPublishStepReceipts(plan.planId());
        if (!settlement.operationId().equals(plan.operationId())
                || !settlement.proposedHead().equals(plan.proposedHead())
                || receipts.isEmpty()) {
            throw new IllegalStateException(
                    "initial settlement graph is invalid");
        }
        if (settlement instanceof StoredInitialFinalReceipt stored) {
            if (receipts.size() != 2) {
                throw new IllegalStateException(
                        "initial final receipt graph is incomplete");
            }
            FinalReceipt receipt = stored.receipt();
            StepReceipt branch = receipts.getFirst();
            StepReceipt pr = receipts.getLast();
            PrIdentity identity = requireNonNull(pr.prIdentity());
            String digest = stableId("github-initial-final-receipt:v1",
                    plan.operationId(), plan.planId(), plan.planDigest(),
                    branch.receiptId(), branch.receiptDigest(), pr.receiptId(),
                    pr.receiptDigest(), plan.proposedHead(),
                    Long.toString(identity.prNumber()), identity.prNodeId(),
                    identity.htmlUrl(), identity.observedBaseSha());
            if (!receipt.branchReceiptId().equals(branch.receiptId())
                    || !receipt.prStepReceiptId().equals(pr.receiptId())
                    || receipt.prNumber() != identity.prNumber()
                    || !receipt.prNodeId().equals(identity.prNodeId())
                    || !receipt.htmlUrl().equals(identity.htmlUrl())
                    || !receipt.observedBaseSha().equals(
                            plan.expectedBaseSha())
                    || !receipt.receiptDigest().equals(digest)
                    || !receipt.receiptId().equals(stableId(
                            "github-initial-final-receipt-id:v1", digest))) {
                throw new IllegalStateException(
                        "initial final receipt graph is invalid");
            }
            return;
        }
        StoredInitialPartialReceipt partial =
                (StoredInitialPartialReceipt) settlement;
        List<String[]> row = jdbc.query(
                "SELECT * FROM flow_github_initial_publish_partial_receipt "
                        + "WHERE partial_receipt_id = ?",
                (result, ignored) -> new String[] {
                        result.getString("kind"),
                        result.getString("reason_code"),
                        result.getString("attention_detail"),
                        result.getString("branch_receipt_id"),
                        result.getString("pr_step_receipt_id"),
                        result.getString("expected_base_sha"),
                        result.getString("observed_base_sha"),
                        result.getString("partial_digest"),
                        result.getString("base_preflight_id"),
                        result.getString("base_preflight_step_id"),
                        result.getString("pr_node_id"),
                        result.getString("html_url"),
                        result.getString("base_preflight_claim_generation"),
                        result.getString("base_preflight_claim_token_digest"),
                        result.getString("base_preflight_digest")},
                partial.resultId());
        if (row.size() != 1) {
            throw new IllegalStateException("initial partial receipt is missing");
        }
        String[] value = row.getFirst();
        boolean withPr = value[4] != null;
        if (receipts.size() != (withPr ? 2 : 1)
                || !value[3].equals(receipts.getFirst().receiptId())
                || withPr && !value[4].equals(receipts.getLast().receiptId())
                || !value[5].equals(plan.expectedBaseSha())
                || !value[2].equals(partial.attentionReason())) {
            throw new IllegalStateException(
                    "initial partial receipt graph is invalid");
        }
        if (withPr) {
            PrIdentity identity = requireNonNull(
                    receipts.getLast().prIdentity(), "PR identity is missing");
            if (!value[6].equals(identity.observedBaseSha())
                    || !value[10].equals(identity.prNodeId())
                    || !value[11].equals(identity.htmlUrl())
                    || value[8] != null || value[9] != null) {
                throw new IllegalStateException(
                        "initial PR partial evidence is inconsistent");
            }
        }
        else {
            List<String[]> preflight = value[8] == null ? List.of() : jdbc.query(
                    "SELECT step_id, claim_generation, claim_token_digest, "
                            + "expected_base_sha, observed_base_sha, "
                            + "preflight_digest FROM "
                            + "flow_github_initial_base_preflight "
                            + "WHERE preflight_id = ?",
                    (result, ignored) -> new String[] {
                            result.getString("step_id"),
                            result.getString("claim_generation"),
                            result.getString("claim_token_digest"),
                            result.getString("expected_base_sha"),
                            result.getString("observed_base_sha"),
                            result.getString("preflight_digest")}, value[8]);
            if (value[8] == null) {
                if (!value[0].equals("BRANCH_ONLY_STALE")
                        || !value[6].equals(value[5])
                        || value[2] == null || value[2].isBlank()) {
                    throw new IllegalStateException(
                            "initial branch partial evidence is inconsistent");
                }
            }
            else if (preflight.size() != 1
                    || !value[9].equals(preflight.getFirst()[0])
                    || !value[12].equals(preflight.getFirst()[1])
                    || !value[13].equals(preflight.getFirst()[2])
                    || !value[5].equals(preflight.getFirst()[3])
                    || !value[6].equals(preflight.getFirst()[4])
                    || !value[14].equals(preflight.getFirst()[5])) {
                throw new IllegalStateException(
                        "initial base preflight evidence is inconsistent");
            }
            if (value[8] != null) {
                String preflightDigest = stableId(
                    "github-initial-base-preflight:v1",
                    plan.operationId(), plan.planId(), value[9], value[12],
                    value[13], value[5], value[6]);
                if (!value[14].equals(preflightDigest)
                    || !value[8].equals(stableId(
                            "github-initial-base-preflight-id:v1",
                            preflightDigest))) {
                    throw new IllegalStateException(
                            "initial base preflight digest is invalid");
                }
            }
        }
        String digest = stableId("github-initial-partial-receipt:v1",
                plan.operationId(), plan.planId(), plan.planDigest(), value[0],
                value[1], value[2],
                receipts.getFirst().receiptId(),
                receipts.getFirst().receiptDigest(),
                withPr ? receipts.getLast().receiptId() : "",
                withPr ? receipts.getLast().receiptDigest() : "",
                value[8] == null ? "" : value[8],
                value[12] == null ? "" : value[12],
                value[13] == null ? "" : value[13],
                value[14] == null ? "" : value[14],
                plan.proposedHead(), plan.expectedBaseSha(), value[6]);
        if (!value[7].equals(digest)
                || !partial.resultId().equals(stableId(
                        "github-initial-partial-receipt-id:v1", digest))) {
            throw new IllegalStateException(
                    "initial partial receipt graph is invalid");
        }
    }

    private static FinalReceipt readInitialFinalReceipt(ResultSet result)
            throws SQLException
    {
        return new FinalReceipt(
                result.getString("receipt_id"),
                result.getString("operation_id"),
                result.getString("plan_id"),
                result.getString("branch_receipt_id"),
                result.getString("pr_step_receipt_id"),
                result.getString("proposed_head"),
                result.getLong("pr_number"), result.getString("pr_node_id"),
                result.getString("html_url"),
                result.getString("observed_base_sha"),
                result.getString("receipt_digest"),
                Instant.ofEpochMilli(result.getLong("recorded_at")));
    }

    /** Derives the only probe target from immutable plan and receipt evidence. */
    public InitialProbeTarget prepareInitialPublishProbe(
            Claim claim, String planId)
    {
        requireNonNull(claim, "claim is null");
        requireText(planId, "planId");
        return inTransaction(() -> currentInitialTarget(claim, planId));
    }

    /** Reserves one mutation only after an exact exhaustive ABSENT probe. */
    public ActivatedInitialAttempt activateInitialPublishAttempt(
            Claim claim, String planId, Instant activatedAt)
    {
        requireNonNull(claim, "claim is null");
        requireText(planId, "planId");
        requireNonNull(activatedAt, "activatedAt is null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "initial attempt activation requires its own transaction");
        }
        return inTransaction(() -> {
            InitialProbeTarget target = currentInitialTarget(claim, planId);
            Plan plan = target.plan;
            InitialPublishRecords.Step step = target.step;
            List<Attempt> prior = initialPublishAttempts(planId).stream()
                    .filter(value -> value.stepId().equals(step.stepId()))
                    .toList();
            boolean divergencePoison = initialPublishProbes(planId).stream()
                    .anyMatch(value -> value.stepId().equals(step.stepId())
                            && value.outcome() == Outcome.DIVERGED);
            if (divergencePoison) {
                throw new IllegalStateException(
                        "diverged initial publication may only be probed");
            }
            if (prior.size() >= MAX_MUTATION_ATTEMPTS) {
                throw new IllegalStateException(
                        "initial publication attempt limit reached");
            }
            Probe latest = initialPublishProbes(planId).stream()
                    .filter(value -> value.stepId().equals(step.stepId()))
                    .reduce((left, right) -> right)
                    .orElseThrow(() -> new IllegalStateException(
                            "exact exhaustive ABSENT probe is missing"));
            assertExactInitialProbe(latest, claim, plan, step);
            String expectedAttempt = prior.isEmpty()
                    ? null : prior.getLast().attemptId();
            if (latest.claimGeneration() != claim.generation()
                    || latest.outcome() != Outcome.ABSENT
                    || !Objects.equals(latest.attemptId(), expectedAttempt)
                    || !Objects.equals(target.latestAttemptId,
                            expectedAttempt)
                    || !prior.isEmpty()
                            && claim.generation()
                                <= prior.getLast().claimGeneration()) {
                throw new IllegalStateException(
                        "attempt requires the latest exact ABSENT probe");
            }
            int number = prior.size() + 1;
            String requestDigest = initialRequestDigest(plan, step, number);
            String attemptId = stableId("github-initial-attempt:v1",
                    plan.planId(), step.stepId(), Integer.toString(number));
            PublishExecutionReservation reservation =
                    runtime.reservePublishExecutionHandle(claim, attemptId);
            String tokenDigest = reservation.tokenDigest();
            Attempt attempt = new Attempt(
                    attemptId, plan.operationId(), plan.planId(), step.stepId(),
                    step.ordinal(), step.kind(), number, claim.generation(),
                    stableId("publish-claim-token:v1", claim.claimToken()),
                    plan.planDigest(), step.stepDigest(),
                    plan.baseRepositoryExternalId(),
                    plan.baseRepositoryOwner(), plan.baseRepositoryName(),
                    plan.headRepositoryExternalId(),
                    plan.headRepositoryOwner(), plan.headRepositoryName(),
                    plan.branchRef(), plan.targetBaseRef(),
                    plan.expectedBaseSha(), plan.proposedHead(),
                    plan.changeSetRevisionId(), plan.draftRevisionId(),
                    plan.draftDigest(), plan.targetSnapshotId(),
                    plan.targetSnapshotDigest(), plan.actionRef(),
                    plan.actionDigest(), requestDigest, tokenDigest, activatedAt);
            jdbc.update(
                    """
                    INSERT INTO flow_github_initial_publish_attempt (
                        attempt_id, operation_id, plan_id, step_id,
                        step_ordinal, step_kind, attempt_number,
                        claim_generation, claim_token_digest, request_digest,
                        execution_token_digest, activated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    attempt.attemptId(), attempt.operationId(),
                    attempt.planId(), attempt.stepId(), attempt.stepOrdinal(),
                    attempt.stepKind().name(), attempt.attemptNumber(),
                    attempt.claimGeneration(), attempt.claimTokenDigest(),
                    attempt.requestDigest(), attempt.executionTokenDigest(),
                    attempt.activatedAt().toEpochMilli());
            Attempt stored = initialPublishAttempt(attemptId).orElseThrow();
            assertExactInitialAttempt(stored, claim, plan, step);
            PublishExecutionHandle handle = runtime.mintPublishExecutionHandle(
                    claim, attemptId, reservation,
                    stored.executionTokenDigest());
            return new ActivatedInitialAttempt(
                    new InitialProbeTarget(claim, plan, step, attemptId),
                    stored, handle);
        });
    }

    /** Persists a provider proof under the current claim, including old attempts. */
    public Probe recordInitialPublishProbe(
            Claim claim, ProviderProof proof, Instant observedAt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(proof, "proof is null");
        requireNonNull(observedAt, "observedAt is null");
        return inTransaction(() -> {
            InitialProbeTarget target = currentInitialTarget(
                    claim, proof.planId());
            requireExactInitialProof(proof, claim, target);
            runtime.assertPublishClaim(claim);
            return insertInitialProbe(claim, target, proof, observedAt);
        });
    }

    /** Persists the immediate read-after-write proof for the consumed handle. */
    public Probe recordInitialPublishAttemptProbe(
            Claim claim, ActivatedInitialAttempt activated,
            ProviderProof proof, Instant observedAt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(activated, "activated is null");
        requireNonNull(proof, "proof is null");
        requireNonNull(observedAt, "observedAt is null");
        return inTransaction(() -> {
            InitialProbeTarget current = currentInitialTarget(
                    claim, proof.planId());
            Attempt stored = initialPublishAttempt(
                    activated.attempt.attemptId()).orElseThrow();
            if (!stored.equals(activated.attempt)
                    || !Objects.equals(current.latestAttemptId,
                            stored.attemptId())
                    || activated.target != current
                            && (!activated.target.planId().equals(
                                    current.planId())
                                || !activated.target.stepId().equals(
                                    current.stepId())
                                || !activated.target.matchesClaim(claim))) {
                throw new IllegalStateException(
                        "activated initial attempt is stale or substituted");
            }
            requireExactInitialProof(proof, claim, activated.target);
            runtime.assertPublishAttemptResult(activated.executionHandle,
                    claim, stored.attemptId(), stored.executionTokenDigest());
            return insertInitialProbe(claim, current, proof, observedAt);
        });
    }

    /** Creates immutable step evidence; ordinal one atomically rearms ordinal two. */
    public StepReceipt insertInitialPublishStepReceipt(
            Claim claim, Probe suppliedProbe, Instant recordedAt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(suppliedProbe, "suppliedProbe is null");
        requireNonNull(recordedAt, "recordedAt is null");
        return inTransaction(() -> {
            List<StepReceipt> existing = initialPublishStepReceipts(
                    suppliedProbe.planId());
            Optional<StepReceipt> replay = existing.stream()
                    .filter(value -> value.stepId().equals(
                            suppliedProbe.stepId()))
                    .findFirst();
            if (replay.isPresent()) {
                StepReceipt stored = replay.orElseThrow();
                assertExactInitialReceipt(stored, suppliedProbe);
                if (suppliedProbe.claimGeneration() != claim.generation()
                        || !suppliedProbe.claimTokenDigest().equals(stableId(
                                "publish-claim-token:v1",
                                claim.claimToken()))) {
                    throw new FlowRuntime.StaleClaimException(
                            "initial receipt replay claim is invalid");
                }
                runtime.assertInitialStepReceiptReplay(
                        claim, stored.receiptId(), stored.attemptId(),
                        suppliedProbe.claimTokenDigest());
                return stored;
            }
            InitialProbeTarget target = currentInitialTarget(
                    claim, suppliedProbe.planId());
            List<Probe> probes = initialPublishProbes(target.planId());
            Probe probe = probes.stream()
                    .filter(value -> value.probeId().equals(
                            suppliedProbe.probeId()))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "initial publication probe is missing"));
            assertExactInitialProbe(probe, claim, target.plan, target.step);
            if (!probe.equals(suppliedProbe)
                    || !probe.equals(probes.getLast())
                    || probe.outcome() != Outcome.APPLIED
                    || probe.attemptId() == null
                    || !probe.stepId().equals(target.stepId())
                    || probe.claimGeneration() != claim.generation()
                    || !Objects.equals(probe.attemptId(),
                            target.latestAttemptId)) {
                throw new IllegalStateException(
                        "step receipt requires the latest exact APPLIED probe");
            }
            Plan plan = target.plan;
            InitialPublishRecords.Step step = target.step;
            validateAppliedInitialProof(plan, step, probe);
            String digest = initialReceiptDigest(plan, step, probe);
            StepReceipt receipt = new StepReceipt(
                    stableId("github-initial-step-receipt-id:v1", digest),
                    plan.operationId(), plan.planId(), plan.planDigest(),
                    step.stepId(), step.stepDigest(), probe.attemptId(),
                    probe.probeId(), step.ordinal(), step.kind(),
                    plan.branchRef(), plan.targetBaseRef(),
                    plan.expectedBaseSha(), plan.proposedHead(),
                    plan.draftRevisionId(), plan.draftDigest(),
                    probe.observationDigest(), digest, probe.prIdentity(),
                    recordedAt);
            jdbc.update(
                    """
                    INSERT INTO flow_github_initial_publish_step_receipt (
                        receipt_id, operation_id, plan_id, step_id,
                        attempt_id, probe_id, step_ordinal, step_kind,
                        probe_outcome, proposed_head, observation_digest,
                        receipt_digest, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APPLIED', ?, ?, ?, ?)
                    """,
                    receipt.receiptId(), receipt.operationId(),
                    receipt.planId(), receipt.stepId(), receipt.attemptId(),
                    receipt.probeId(), receipt.stepOrdinal(),
                    receipt.stepKind().name(), receipt.proposedHead(),
                    receipt.observationDigest(), receipt.receiptDigest(),
                    receipt.recordedAt().toEpochMilli());
            if (receipt.prIdentity() != null) {
                insertInitialPrReceiptDetail(receipt);
            }
            StepReceipt stored = initialPublishStepReceipts(plan.planId())
                    .stream().filter(value -> value.receiptId().equals(
                            receipt.receiptId())).findFirst().orElseThrow();
            assertExactInitialReceipt(stored, probe);
            if (step.ordinal() == 1) {
                runtime.rearmClaimedPublishStep(
                        claim, stored.receiptId(), recordedAt);
            }
            return stored;
        });
    }

    /** Classifies expiry only from the exact current step, never history. */
    public InitialRecoveryKind initialPublishRecoveryKind(
            String planId, String operationId)
    {
        requireText(planId, "planId");
        requireText(operationId, "operationId");
        return inTransaction(() -> {
            Plan plan = requireInitialPlan(planId);
            if (!plan.operationId().equals(operationId)) {
                throw new IllegalStateException(
                        "initial recovery plan owns another operation");
            }
            Optional<InitialPublishRecords.Step> current =
                    currentInitialStep(plan);
            if (current.isEmpty()) {
                return InitialRecoveryKind.COMPLETE;
            }
            InitialPublishRecords.Step step = current.orElseThrow();
            boolean unresolved = initialPublishAttempts(planId).stream()
                    .anyMatch(value -> value.stepId().equals(step.stepId()));
            return unresolved
                    ? InitialRecoveryKind.PROBE_ONLY
                    : InitialRecoveryKind.NEVER_STARTED;
        });
    }

    private InitialProbeTarget currentInitialTarget(
            Claim claim, String planId)
    {
        var operation = runtime.assertPublishClaim(claim);
        Plan plan = requireInitialPlan(planId);
        List<InitialPublishRecords.Step> steps = initialPublishSteps(planId);
        assertExactInitialPublishPlan(plan, steps);
        if (!plan.operationId().equals(claim.operationId())
                || !operation.inputRef().equals(plan.planId())
                || !operation.subjectDigest().equals(plan.planDigest())) {
            throw new IllegalStateException(
                    "initial publication claim does not own the plan");
        }
        InitialPublishRecords.Step step = currentInitialStep(plan)
                .orElseThrow(() -> new IllegalStateException(
                        "initial publication plan is fully receipted"));
        List<Attempt> attempts = initialPublishAttempts(planId);
        for (Attempt attempt : attempts) {
            InitialPublishRecords.Step attemptStep = steps.stream()
                    .filter(value -> value.stepId().equals(attempt.stepId()))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "initial attempt step is missing"));
            assertExactInitialAttemptGraph(attempt, plan, attemptStep);
        }
        List<Attempt> currentAttempts = attempts.stream()
                .filter(value -> value.stepId().equals(step.stepId())).toList();
        return new InitialProbeTarget(claim, plan, step,
                currentAttempts.isEmpty()
                        ? null : currentAttempts.getLast().attemptId());
    }

    private Optional<InitialPublishRecords.Step> currentInitialStep(Plan plan)
    {
        List<InitialPublishRecords.Step> steps = initialPublishSteps(
                plan.planId());
        assertExactInitialPublishPlan(plan, steps);
        List<StepReceipt> receipts = initialPublishStepReceipts(plan.planId());
        if (receipts.size() > 2
                || !receipts.isEmpty() && receipts.getFirst().stepOrdinal() != 1
                || receipts.size() == 2
                        && receipts.getLast().stepOrdinal() != 2) {
            throw new IllegalStateException(
                    "initial publication receipt order is corrupt");
        }
        for (StepReceipt receipt : receipts) {
            Probe probe = initialPublishProbes(plan.planId()).stream()
                    .filter(value -> value.probeId().equals(receipt.probeId()))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "initial receipt probe is missing"));
            assertExactInitialReceipt(receipt, probe);
        }
        return receipts.size() == 2
                ? Optional.empty()
                : Optional.of(steps.get(receipts.size()));
    }

    private void requireExactInitialProof(
            ProviderProof proof, Claim claim, InitialProbeTarget target)
    {
        if (!(proof instanceof GitHubProvider.ExactInitialPublishProof exact)
                || !exact.matchesTarget(target)
                || !proof.matchesClaim(claim)
                || !proof.operationId().equals(target.operationId())
                || !proof.planId().equals(target.planId())
                || !proof.stepId().equals(target.stepId())
                || !Objects.equals(proof.attemptId(), target.attemptId())
                || proof.stepOrdinal() != target.stepOrdinal()
                || proof.stepKind() != target.stepKind()) {
            throw new IllegalStateException(
                    "provider proof does not own the exact current step");
        }
        validateInitialOutcome(target.plan, target.step, proof.outcome(),
                proof.attemptId(), proof.observedHead(), proof.prIdentity());
    }

    private Probe insertInitialProbe(
            Claim claim, InitialProbeTarget target, ProviderProof proof,
            Instant observedAt)
    {
        observedAt = Instant.ofEpochMilli(observedAt.toEpochMilli());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_github_initial_publish_probe "
                        + "WHERE operation_id = ? AND claim_generation = ?",
                Integer.class, claim.operationId(), claim.generation());
        int probeNumber = requireNonNull(count, "probe count is null") + 1;
        Attempt attempt = proof.attemptId() == null ? null
                : initialPublishAttempt(proof.attemptId()).orElseThrow(() ->
                        new IllegalStateException(
                                "initial probe attempt is missing"));
        if (attempt != null) {
            assertExactInitialAttemptGraph(
                    attempt, target.plan, target.step);
        }
        String digest = initialObservationDigest(
                target.plan, target.step, attempt, claim.generation(),
                stableId("publish-claim-token:v1", claim.claimToken()),
                probeNumber, proof.outcome(), proof.observedHead(),
                proof.prIdentity());
        Probe probe = new Probe(
                stableId("github-initial-probe-id:v1", digest),
                target.operationId(), target.planId(), target.stepId(),
                proof.attemptId(), claim.generation(),
                stableId("publish-claim-token:v1", claim.claimToken()),
                probeNumber,
                target.stepOrdinal(), target.stepKind(), proof.outcome(),
                proof.observedHead(), digest, proof.prIdentity(), observedAt);
        jdbc.update(
                """
                INSERT INTO flow_github_initial_publish_probe (
                    probe_id, operation_id, plan_id, step_id, attempt_id,
                    claim_generation, claim_token_digest, probe_number,
                    step_ordinal, step_kind,
                    outcome, observed_head, observation_digest, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                probe.probeId(), probe.operationId(), probe.planId(),
                probe.stepId(), probe.attemptId(), probe.claimGeneration(),
                probe.claimTokenDigest(), probe.probeNumber(),
                probe.stepOrdinal(),
                probe.stepKind().name(), probe.outcome().name(),
                probe.observedHead(), probe.observationDigest(),
                probe.observedAt().toEpochMilli());
        if (probe.prIdentity() != null) {
            insertInitialPrObservation(probe);
        }
        Probe stored = initialPublishProbes(probe.planId()).stream()
                .filter(value -> value.probeId().equals(probe.probeId()))
                .findFirst().orElseThrow();
        if (!stored.equals(probe)) {
            throw new IllegalStateException(
                    "initial probe durable replay differs");
        }
        assertExactInitialProbe(stored, claim, target.plan, target.step);
        return stored;
    }

    private void validateInitialOutcome(
            Plan plan, InitialPublishRecords.Step step, Outcome outcome,
            String attemptId, String observedHead, PrIdentity identity)
    {
        if ((outcome == Outcome.ABSENT || outcome == Outcome.UNKNOWN)
                && (observedHead != null || identity != null)
                || (outcome == Outcome.APPLIED
                        || outcome == Outcome.DIVERGED)
                        && observedHead == null
                || step.kind()
                        == InitialPublishRecords.StepKind.CREATE_REF_EXACT
                        && identity != null
                || step.kind()
                        == InitialPublishRecords.StepKind.CREATE_DRAFT_PR
                        && (identity != null)
                            != (outcome == Outcome.APPLIED
                                    || outcome == Outcome.DIVERGED)) {
            throw new IllegalArgumentException(
                    "initial provider outcome is inconsistent");
        }
        boolean exact = step.kind()
                == InitialPublishRecords.StepKind.CREATE_REF_EXACT
                ? Objects.equals(observedHead, plan.proposedHead())
                : identity != null
                        && isExactInitialPr(plan, observedHead, identity);
        if (outcome == Outcome.APPLIED
                && (attemptId == null || !exact)
                || outcome == Outcome.DIVERGED
                        && attemptId != null && exact) {
            throw new IllegalArgumentException(
                    "initial provider outcome does not match its evidence");
        }
    }

    private void validateAppliedInitialProof(
            Plan plan, InitialPublishRecords.Step step, Probe probe)
    {
        validateInitialOutcome(plan, step, probe.outcome(),
                probe.attemptId(), probe.observedHead(), probe.prIdentity());
        if (probe.outcome() != Outcome.APPLIED
                || !probe.observedHead().equals(plan.proposedHead())) {
            throw new IllegalStateException(
                    "initial receipt proof is not exact APPLIED evidence");
        }
    }

    private boolean isExactInitialPr(
            Plan plan, String observedHead, PrIdentity identity)
    {
        List<String[]> draft = jdbc.query(
                "SELECT title, body FROM flow_runtime_pr_draft_revision "
                        + "WHERE draft_revision_id = ? AND draft_digest = ?",
                (result, row) -> new String[] {
                        result.getString("title"), result.getString("body")},
                plan.draftRevisionId(), plan.draftDigest());
        if (draft.size() != 1) {
            throw new IllegalStateException(
                    "initial publication draft is missing or ambiguous");
        }
        String titleDigest = stableId(
                "github-initial-pr-title:v1", draft.getFirst()[0]);
        String bodyDigest = stableId(
                "github-initial-pr-body:v1", draft.getFirst()[1]);
        String passDigest = initialPrPassDigest(observedHead, identity);
        return Objects.equals(observedHead, plan.proposedHead())
                && identity.state().equals("OPEN") && identity.draft()
                && identity.baseRepositoryExternalId().equals(
                        plan.baseRepositoryExternalId())
                && identity.baseRepositoryOwner().equals(
                        plan.baseRepositoryOwner())
                && identity.baseRepositoryName().equals(
                        plan.baseRepositoryName())
                && identity.headRepositoryExternalId().equals(
                        plan.headRepositoryExternalId())
                && identity.headRepositoryOwner().equals(
                        plan.headRepositoryOwner())
                && identity.headRepositoryName().equals(
                        plan.headRepositoryName())
                && identity.headBranchRef().equals(plan.branchRef())
                && identity.targetBaseRef().equals(plan.targetBaseRef())
                && identity.titleDigest().equals(titleDigest)
                && identity.bodyDigest().equals(bodyDigest)
                && identity.firstPassDigest().equals(passDigest)
                && identity.secondPassDigest().equals(passDigest);
    }

    static String initialPrPassDigest(String observedHead, PrIdentity identity)
    {
        return stableId("github-initial-pr-pass:v1", observedHead,
                identity.state(), Boolean.toString(identity.draft()),
                identity.baseRepositoryExternalId(),
                identity.baseRepositoryOwner(), identity.baseRepositoryName(),
                identity.headRepositoryExternalId(),
                identity.headRepositoryOwner(), identity.headRepositoryName(),
                identity.headBranchRef(), identity.targetBaseRef(),
                Long.toString(identity.prNumber()), identity.prNodeId(),
                identity.htmlUrl(), identity.observedBaseSha(),
                identity.titleDigest(), identity.bodyDigest());
    }

    static String initialTitleDigest(String title)
    {
        return stableId("github-initial-pr-title:v1", title);
    }

    static String initialBodyDigest(String body)
    {
        return stableId("github-initial-pr-body:v1", body);
    }

    private static String initialRequestDigest(
            Plan plan, InitialPublishRecords.Step step, int attemptNumber)
    {
        return stableId("github-initial-request:v1", plan.operationId(),
                plan.planId(), plan.planDigest(), step.stepId(),
                step.stepDigest(), Integer.toString(step.ordinal()),
                step.kind().name(), Integer.toString(attemptNumber),
                plan.baseRepositoryExternalId(), plan.baseRepositoryOwner(),
                plan.baseRepositoryName(), plan.headRepositoryExternalId(),
                plan.headRepositoryOwner(), plan.headRepositoryName(),
                plan.branchRef(), plan.targetBaseRef(), plan.expectedBaseSha(),
                plan.proposedHead(), plan.changeSetRevisionId(),
                plan.draftRevisionId(), plan.draftDigest(),
                plan.targetSnapshotId(), plan.targetSnapshotDigest(),
                plan.actionRef(), plan.actionDigest());
    }

    private static String initialObservationDigest(
            Plan plan, InitialPublishRecords.Step step, Attempt attempt,
            long generation, String claimTokenDigest, int probeNumber,
            Outcome outcome,
            String observedHead, PrIdentity identity)
    {
        return stableId("github-initial-observation:v1", plan.operationId(),
                plan.planId(), plan.planDigest(), step.stepId(),
                step.stepDigest(), Integer.toString(step.ordinal()),
                step.kind().name(), attempt == null ? "" : attempt.attemptId(),
                attempt == null ? "" : attempt.requestDigest(),
                attempt == null ? "" : attempt.executionTokenDigest(),
                Long.toString(generation), claimTokenDigest,
                Integer.toString(probeNumber),
                outcome.name(), nullToEmpty(observedHead),
                identity == null ? "" : identity.state(),
                identity == null ? "" : Boolean.toString(identity.draft()),
                identity == null ? "" : identity.baseRepositoryExternalId(),
                identity == null ? "" : identity.baseRepositoryOwner(),
                identity == null ? "" : identity.baseRepositoryName(),
                identity == null ? "" : identity.headRepositoryExternalId(),
                identity == null ? "" : identity.headRepositoryOwner(),
                identity == null ? "" : identity.headRepositoryName(),
                identity == null ? "" : identity.headBranchRef(),
                identity == null ? "" : identity.targetBaseRef(),
                identity == null ? "" : Long.toString(identity.prNumber()),
                identity == null ? "" : identity.prNodeId(),
                identity == null ? "" : identity.htmlUrl(),
                identity == null ? "" : identity.observedBaseSha(),
                identity == null ? "" : identity.titleDigest(),
                identity == null ? "" : identity.bodyDigest(),
                identity == null ? "" : identity.firstPassDigest(),
                identity == null ? "" : identity.secondPassDigest());
    }

    private static String initialReceiptDigest(
            Plan plan, InitialPublishRecords.Step step, Probe probe)
    {
        PrIdentity identity = probe.prIdentity();
        return stableId("github-initial-step-receipt:v1",
                plan.operationId(), plan.planId(), plan.planDigest(),
                step.stepId(), step.stepDigest(),
                Integer.toString(step.ordinal()), step.kind().name(),
                probe.attemptId(), probe.probeId(), probe.observationDigest(),
                plan.branchRef(), plan.targetBaseRef(), plan.expectedBaseSha(),
                plan.proposedHead(), plan.draftRevisionId(),
                plan.draftDigest(),
                identity == null ? "" : identity.state(),
                identity == null ? "" : Boolean.toString(identity.draft()),
                identity == null ? "" : identity.baseRepositoryExternalId(),
                identity == null ? "" : identity.baseRepositoryOwner(),
                identity == null ? "" : identity.baseRepositoryName(),
                identity == null ? "" : identity.headRepositoryExternalId(),
                identity == null ? "" : identity.headRepositoryOwner(),
                identity == null ? "" : identity.headRepositoryName(),
                identity == null ? "" : identity.headBranchRef(),
                identity == null ? "" : identity.targetBaseRef(),
                identity == null ? "" : Long.toString(identity.prNumber()),
                identity == null ? "" : identity.prNodeId(),
                identity == null ? "" : identity.htmlUrl(),
                identity == null ? "" : identity.observedBaseSha(),
                identity == null ? "" : identity.titleDigest(),
                identity == null ? "" : identity.bodyDigest(),
                identity == null ? "" : identity.firstPassDigest(),
                identity == null ? "" : identity.secondPassDigest());
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
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
        Instant storedObservedAt = Instant.ofEpochMilli(
                observedAt.toEpochMilli());
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
                    storedObservedAt);
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
        Instant storedRecordedAt = Instant.ofEpochMilli(
                recordedAt.toEpochMilli());
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
                    storedRecordedAt);
            jdbc.update(
                    """
                    INSERT INTO flow_github_effect_receipt_envelope (
                        receipt_id, operation_id, plan_id, kind,
                        proposed_head, receipt_digest, recorded_at
                    ) VALUES (?, ?, ?, 'CI_UPDATE', ?, ?, ?)
                    """,
                    receipt.receiptId(), receipt.operationId(),
                    receipt.planId(), receipt.proposedHead(),
                    receipt.receiptDigest(),
                    receipt.recordedAt().toEpochMilli());
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

    private Plan requireInitialPlan(String planId)
    {
        return initialPublishPlan(planId).orElseThrow(() ->
                new IllegalStateException(
                        "initial publication plan is missing"));
    }

    private static void assertExactInitialAttempt(
            Attempt attempt, Claim claim, Plan plan,
            InitialPublishRecords.Step step)
    {
        assertExactInitialAttemptGraph(attempt, plan, step);
        if (!attempt.operationId().equals(claim.operationId())
                || attempt.claimGeneration() != claim.generation()
                || !attempt.claimTokenDigest().equals(stableId(
                        "publish-claim-token:v1", claim.claimToken()))) {
            throw new IllegalStateException(
                    "initial attempt claim binding is invalid");
        }
    }

    private static void assertExactInitialAttemptGraph(
            Attempt attempt, Plan plan, InitialPublishRecords.Step step)
    {
        String requestDigest = initialRequestDigest(
                plan, step, attempt.attemptNumber());
        if (!attempt.attemptId().equals(stableId(
                    "github-initial-attempt:v1", plan.planId(),
                    step.stepId(), Integer.toString(attempt.attemptNumber())))
                || !attempt.operationId().equals(plan.operationId())
                || !attempt.planId().equals(plan.planId())
                || !attempt.planDigest().equals(plan.planDigest())
                || !attempt.stepId().equals(step.stepId())
                || attempt.stepOrdinal() != step.ordinal()
                || attempt.stepKind() != step.kind()
                || !attempt.stepDigest().equals(step.stepDigest())
                || !attempt.baseRepositoryExternalId().equals(
                        plan.baseRepositoryExternalId())
                || !attempt.baseRepositoryOwner().equals(
                        plan.baseRepositoryOwner())
                || !attempt.baseRepositoryName().equals(
                        plan.baseRepositoryName())
                || !attempt.headRepositoryExternalId().equals(
                        plan.headRepositoryExternalId())
                || !attempt.headRepositoryOwner().equals(
                        plan.headRepositoryOwner())
                || !attempt.headRepositoryName().equals(
                        plan.headRepositoryName())
                || !attempt.branchRef().equals(plan.branchRef())
                || !attempt.targetBaseRef().equals(plan.targetBaseRef())
                || !attempt.expectedBaseSha().equals(plan.expectedBaseSha())
                || !attempt.proposedHead().equals(plan.proposedHead())
                || !attempt.changeSetRevisionId().equals(
                        plan.changeSetRevisionId())
                || !attempt.draftRevisionId().equals(plan.draftRevisionId())
                || !attempt.draftDigest().equals(plan.draftDigest())
                || !attempt.targetSnapshotId().equals(plan.targetSnapshotId())
                || !attempt.targetSnapshotDigest().equals(
                        plan.targetSnapshotDigest())
                || !attempt.actionRef().equals(plan.actionRef())
                || !attempt.actionDigest().equals(plan.actionDigest())
                || !attempt.requestDigest().equals(requestDigest)
                || attempt.executionTokenDigest().isBlank()) {
            throw new IllegalStateException(
                    "initial publication attempt graph is invalid");
        }
    }

    private void assertExactInitialProbe(
            Probe probe, Claim claim, Plan plan,
            InitialPublishRecords.Step step)
    {
        assertExactInitialProbeGraph(probe, plan, step);
        if (!probe.operationId().equals(claim.operationId())
                || probe.claimGeneration() != claim.generation()
                || !probe.claimTokenDigest().equals(stableId(
                        "publish-claim-token:v1", claim.claimToken()))) {
            throw new IllegalStateException(
                    "initial probe claim binding is invalid");
        }
    }

    private void assertExactInitialProbeGraph(
            Probe probe, Plan plan, InitialPublishRecords.Step step)
    {
        Attempt attempt = probe.attemptId() == null ? null
                : initialPublishAttempt(probe.attemptId()).orElseThrow(() ->
                        new IllegalStateException(
                                "initial probe attempt is missing"));
        if (attempt != null) {
            assertExactInitialAttemptGraph(attempt, plan, step);
        }
        validateInitialOutcome(plan, step, probe.outcome(),
                probe.attemptId(), probe.observedHead(), probe.prIdentity());
        String digest = initialObservationDigest(plan, step, attempt,
                probe.claimGeneration(), probe.claimTokenDigest(),
                probe.probeNumber(), probe.outcome(), probe.observedHead(),
                probe.prIdentity());
        if (!probe.operationId().equals(plan.operationId())
                || !probe.planId().equals(plan.planId())
                || !probe.stepId().equals(step.stepId())
                || probe.stepOrdinal() != step.ordinal()
                || probe.stepKind() != step.kind()
                || !probe.observationDigest().equals(digest)
                || !probe.probeId().equals(stableId(
                        "github-initial-probe-id:v1", digest))) {
            throw new IllegalStateException(
                    "initial publication probe graph is invalid");
        }
    }

    private void assertExactInitialReceipt(
            StepReceipt receipt, Probe suppliedProbe)
    {
        Plan plan = requireInitialPlan(receipt.planId());
        List<InitialPublishRecords.Step> steps = initialPublishSteps(
                plan.planId());
        assertExactInitialPublishPlan(plan, steps);
        InitialPublishRecords.Step step = steps.stream()
                .filter(value -> value.stepId().equals(receipt.stepId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "initial receipt step is missing"));
        Probe durableProbe = initialPublishProbes(plan.planId()).stream()
                .filter(value -> value.probeId().equals(receipt.probeId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                    "initial receipt probe is missing"));
        assertExactInitialProbeGraph(durableProbe, plan, step);
        if (!durableProbe.equals(suppliedProbe)
                || durableProbe.outcome() != Outcome.APPLIED
                || durableProbe.attemptId() == null) {
            throw new IllegalStateException(
                    "initial receipt proof is invalid");
        }
        Attempt attempt = initialPublishAttempt(
                durableProbe.attemptId()).orElseThrow(() ->
                        new IllegalStateException(
                                "initial receipt attempt is missing"));
        assertExactInitialAttemptGraph(attempt, plan, step);
        validateAppliedInitialProof(plan, step, durableProbe);
        String digest = initialReceiptDigest(plan, step, durableProbe);
        if (!receipt.receiptId().equals(stableId(
                    "github-initial-step-receipt-id:v1", digest))
                || !receipt.operationId().equals(plan.operationId())
                || !receipt.planDigest().equals(plan.planDigest())
                || !receipt.stepDigest().equals(step.stepDigest())
                || !receipt.attemptId().equals(attempt.attemptId())
                || !receipt.probeId().equals(durableProbe.probeId())
                || receipt.stepOrdinal() != step.ordinal()
                || receipt.stepKind() != step.kind()
                || !receipt.branchRef().equals(plan.branchRef())
                || !receipt.targetBaseRef().equals(plan.targetBaseRef())
                || !receipt.expectedBaseSha().equals(plan.expectedBaseSha())
                || !receipt.proposedHead().equals(plan.proposedHead())
                || !receipt.draftRevisionId().equals(plan.draftRevisionId())
                || !receipt.draftDigest().equals(plan.draftDigest())
                || !receipt.observationDigest().equals(
                        durableProbe.observationDigest())
                || !receipt.receiptDigest().equals(digest)
                || !Objects.equals(receipt.prIdentity(),
                        durableProbe.prIdentity())) {
            throw new IllegalStateException(
                    "initial publication receipt graph is invalid");
        }
    }

    private void insertInitialPrObservation(Probe probe)
    {
        PrIdentity value = requireNonNull(
                probe.prIdentity(), "prIdentity is null");
        jdbc.update(
                """
                INSERT INTO flow_github_initial_pr_observation (
                    probe_id, operation_id, plan_id, step_id, attempt_id,
                    step_ordinal, step_kind, outcome, observed_head,
                    state, draft, base_repository_external_id,
                    base_repository_owner, base_repository_name,
                    head_repository_external_id, head_repository_owner,
                    head_repository_name, head_branch_ref, target_base_ref,
                    pr_number, pr_node_id, html_url, observed_base_sha,
                    observed_title_digest, observed_body_digest,
                    first_pass_digest, second_pass_digest,
                    observation_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                probe.probeId(), probe.operationId(), probe.planId(),
                probe.stepId(), probe.attemptId(), probe.stepOrdinal(),
                probe.stepKind().name(), probe.outcome().name(),
                probe.observedHead(), value.state(), value.draft() ? 1 : 0,
                value.baseRepositoryExternalId(), value.baseRepositoryOwner(),
                value.baseRepositoryName(), value.headRepositoryExternalId(),
                value.headRepositoryOwner(), value.headRepositoryName(),
                value.headBranchRef(), value.targetBaseRef(), value.prNumber(),
                value.prNodeId(), value.htmlUrl(), value.observedBaseSha(),
                value.titleDigest(), value.bodyDigest(),
                value.firstPassDigest(), value.secondPassDigest(),
                probe.observationDigest());
    }

    private void insertInitialPrReceiptDetail(StepReceipt receipt)
    {
        PrIdentity value = requireNonNull(
                receipt.prIdentity(), "prIdentity is null");
        jdbc.update(
                """
                INSERT INTO flow_github_initial_pr_receipt_detail (
                    receipt_id, operation_id, plan_id, step_id, attempt_id,
                    probe_id, step_ordinal, step_kind, outcome,
                    proposed_head, state, draft,
                    base_repository_external_id, base_repository_owner,
                    base_repository_name, head_repository_external_id,
                    head_repository_owner, head_repository_name,
                    head_branch_ref, target_base_ref, pr_number, pr_node_id,
                    html_url, observed_base_sha, observed_title_digest,
                    observed_body_digest, first_pass_digest,
                    second_pass_digest, observation_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'APPLIED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                receipt.receiptId(), receipt.operationId(), receipt.planId(),
                receipt.stepId(), receipt.attemptId(), receipt.probeId(),
                receipt.stepOrdinal(), receipt.stepKind().name(),
                receipt.proposedHead(), value.state(), value.draft() ? 1 : 0,
                value.baseRepositoryExternalId(), value.baseRepositoryOwner(),
                value.baseRepositoryName(), value.headRepositoryExternalId(),
                value.headRepositoryOwner(), value.headRepositoryName(),
                value.headBranchRef(), value.targetBaseRef(), value.prNumber(),
                value.prNodeId(), value.htmlUrl(), value.observedBaseSha(),
                value.titleDigest(), value.bodyDigest(),
                value.firstPassDigest(), value.secondPassDigest(),
                receipt.observationDigest());
    }

    private static Attempt readInitialAttempt(ResultSet result)
            throws SQLException
    {
        return new Attempt(result.getString("attempt_id"),
                result.getString("operation_id"), result.getString("plan_id"),
                result.getString("step_id"), result.getInt("step_ordinal"),
                InitialPublishRecords.StepKind.valueOf(
                        result.getString("step_kind")),
                result.getInt("attempt_number"),
                result.getLong("claim_generation"),
                result.getString("claim_token_digest"),
                result.getString("plan_digest"),
                result.getString("step_digest"),
                result.getString("base_repository_external_id"),
                result.getString("base_repository_owner"),
                result.getString("base_repository_name"),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"),
                result.getString("head_repository_name"),
                result.getString("branch_ref"),
                result.getString("target_base_ref"),
                result.getString("expected_base_sha"),
                result.getString("proposed_head"),
                result.getString("change_set_revision_id"),
                result.getString("draft_revision_id"),
                result.getString("draft_digest"),
                result.getString("target_snapshot_id"),
                result.getString("target_snapshot_digest"),
                result.getString("action_ref"),
                result.getString("action_digest"),
                result.getString("request_digest"),
                result.getString("execution_token_digest"),
                Instant.ofEpochMilli(result.getLong("activated_at")));
    }

    private static Probe readInitialProbe(ResultSet result)
            throws SQLException
    {
        PrIdentity identity = result.getString("identity_probe_id") == null
                ? null : new PrIdentity(result.getString("state"),
                        result.getInt("draft") != 0,
                        result.getString("base_repository_external_id"),
                        result.getString("base_repository_owner"),
                        result.getString("base_repository_name"),
                        result.getString("head_repository_external_id"),
                        result.getString("head_repository_owner"),
                        result.getString("head_repository_name"),
                        result.getString("head_branch_ref"),
                        result.getString("target_base_ref"),
                        result.getLong("pr_number"),
                        result.getString("pr_node_id"),
                        result.getString("html_url"),
                        result.getString("observed_base_sha"),
                        result.getString("observed_title_digest"),
                        result.getString("observed_body_digest"),
                        result.getString("first_pass_digest"),
                        result.getString("second_pass_digest"));
        return new Probe(result.getString("probe_id"),
                result.getString("operation_id"), result.getString("plan_id"),
                result.getString("step_id"), result.getString("attempt_id"),
                result.getLong("claim_generation"),
                result.getString("claim_token_digest"),
                result.getInt("probe_number"), result.getInt("step_ordinal"),
                InitialPublishRecords.StepKind.valueOf(
                        result.getString("step_kind")),
                Outcome.valueOf(result.getString("outcome")),
                result.getString("observed_head"),
                result.getString("observation_digest"), identity,
                Instant.ofEpochMilli(result.getLong("observed_at")));
    }

    private static StepReceipt readInitialReceipt(ResultSet result)
            throws SQLException
    {
        PrIdentity identity = result.getString("detail_receipt_id") == null
                ? null : new PrIdentity(result.getString("state"),
                        result.getInt("draft") != 0,
                        result.getString("base_repository_external_id"),
                        result.getString("base_repository_owner"),
                        result.getString("base_repository_name"),
                        result.getString("head_repository_external_id"),
                        result.getString("head_repository_owner"),
                        result.getString("head_repository_name"),
                        result.getString("head_branch_ref"),
                        result.getString("detail_base_ref"),
                        result.getLong("pr_number"),
                        result.getString("pr_node_id"),
                        result.getString("html_url"),
                        result.getString("observed_base_sha"),
                        result.getString("observed_title_digest"),
                        result.getString("observed_body_digest"),
                        result.getString("first_pass_digest"),
                        result.getString("second_pass_digest"));
        return new StepReceipt(result.getString("receipt_id"),
                result.getString("operation_id"), result.getString("plan_id"),
                result.getString("plan_digest"), result.getString("step_id"),
                result.getString("step_digest"),
                result.getString("attempt_id"), result.getString("probe_id"),
                result.getInt("step_ordinal"),
                InitialPublishRecords.StepKind.valueOf(
                        result.getString("step_kind")),
                result.getString("branch_ref"),
                result.getString("target_base_ref"),
                result.getString("expected_base_sha"),
                result.getString("proposed_head"),
                result.getString("draft_revision_id"),
                result.getString("draft_digest"),
                result.getString("observation_digest"),
                result.getString("receipt_digest"), identity,
                Instant.ofEpochMilli(result.getLong("recorded_at")));
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
