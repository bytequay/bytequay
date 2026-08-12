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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.ci.CiAutofixCoordinator.CleanupBinding;
import com.bytequay.app.flow.ci.CiAutofixCoordinator.RepairBinding;
import com.bytequay.app.flow.ci.CiAutofixRecords.AttemptState;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupCompletion;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRepairAttempt;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.CleanupOutcome;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.gate.UserGateRecords.GateRevision;
import com.bytequay.app.flow.gate.UserGateRecords.GateState;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.gate.UserGates.InitialPublishDispositionKind;
import com.bytequay.app.flow.github.GitHubInitialPublishExecutor;
import com.bytequay.app.flow.github.InitialPublishRecords.Outcome;
import com.bytequay.app.flow.github.InitialPublishRecords.StepReceipt;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.SessionState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeTestSupport;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanInspection;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import com.bytequay.app.flow.timeline.PrTimelineProjection;
import com.bytequay.app.flow.timeline.PrTimelineProjection.EventKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.bytequay.app.flow.github.GitHubProviderFixtures.consumeInitial;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.exactInitialPr;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeInitialAbsent;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeInitialApplied;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeInitialBaseDrift;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeInitialInterrupted;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeInitialMissingAfterMutation;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeInitialPartialInterrupted;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeInitialWithBranchHook;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.initialProof;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.initialRepositoryObservation;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.observation;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.openInitialPublish;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Initial publish authorization, its mutation authority, and the repair
 * and cleanup successors a stopped fix reserves.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestCiAutofixCoordinatorInitialPublish
        extends BaseTestCiAutofixCoordinator
{
    @Test
    void initialPublishManualAuthorizationIsExactAndReplaySafe()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(runtime, pr.prId());

        char[] credential = "initial-secret".toCharArray();
        AtomicInteger lookups = new AtomicInteger();
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId(),
                () -> initialRepositoryObservation(
                        runtime, lineage.parentRunId(), "101", "101",
                        credential, lookups));
        var subject = userGates.initialSubject(opened.subjectManifestRef()).orElseThrow();
        var action = userGates.initialAction(opened.actionManifestRef()).orElseThrow();
        GateRevision openReplay = openInitialPublish(
                runtime, userGates, lineage.parentRunId(), () -> {
                    throw new AssertionError(
                            "durable gate replay performed provider lookup");
                });
        assertThat(openReplay).isEqualTo(opened);
        assertThat(opened.readinessEvidenceRef()).isNull();
        assertThat(subject.repositoryId()).isEqualTo("repo-1");
        assertThat(subject.baseRepositoryExternalId()).isEqualTo("101");
        assertThat(subject.headRepositoryExternalId()).isEqualTo("101");
        assertThat(lookups).hasValue(1);
        assertThat(credential).containsOnly('\0');
        assertThat(subject.draftRevisionId()).isEqualTo(lineage.draftRevisionId());
        assertThat(action.readyPolicy()).isEqualTo("KEEP_DRAFT");

        var authorized = userGates.authorizeInitialPublish(opened.gateId(),
                opened.revision(), opened.subjectDigest(), opened.actionDigest(),
                "initial-authorize");
        var replay = userGates.authorizeInitialPublish(opened.gateId(),
                opened.revision(), opened.subjectDigest(), opened.actionDigest(),
                "initial-authorize");
        assertThat(replay).isEqualTo(authorized);
        assertThat(githubEffects.initialPublishSteps(authorized.planId()))
                .extracting(step -> step.kind().name())
                .containsExactly("CREATE_REF_EXACT", "CREATE_DRAFT_PR");
        assertThat(runtime.operation(authorized.operationId()).orElseThrow().kind())
                .isEqualTo(OperationKind.PUBLISH);
        assertThat(userGates.transitions(opened.gateId()))
                .filteredOn(transition -> transition.toState() == GateState.AUTHORIZED)
                .singleElement()
                .extracting(transition -> transition.detailRef())
                .isEqualTo(authorized.authorization().authorizationId());
        assertThatThrownBy(() -> userGates.authorizeInitialPublish(opened.gateId(),
                opened.revision(), opened.subjectDigest(), opened.actionDigest(),
                "different-key"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void initialPublishRepositoryObservationRejectsInvalidIdentityAndLosesRaceSafely()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        int targets = count(
                "flow_github_initial_publish_target_snapshot", "1 = 1");
        int gates = count(
                "flow_user_gate", "kind = 'INITIAL_PUBLISH'");
        for (String[] ids : List.<String[]>of(
                new String[] {"not-numeric", "not-numeric"})) {
            char[] token = "invalid-secret".toCharArray();
            assertThatThrownBy(() -> openInitialPublish(
                    runtime, userGates, lineage.parentRunId(), () ->
                            initialRepositoryObservation(
                                    runtime, lineage.parentRunId(), ids[0],
                                    ids[1], token, new AtomicInteger())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("identity");
            assertThat(token).containsOnly('\0');
        }
        assertThat(count(
                "flow_github_initial_publish_target_snapshot", "1 = 1"))
                .isEqualTo(targets);
        assertThat(count(
                "flow_user_gate", "kind = 'INITIAL_PUBLISH'"))
                .isEqualTo(gates);

        AtomicReference<GateRevision> winner = new AtomicReference<>();
        GateRevision outer = openInitialPublish(
                runtime, userGates, lineage.parentRunId(), () -> {
                    var losing = initialRepositoryObservation(
                            runtime, lineage.parentRunId(), "202", "202",
                            "loser".toCharArray(), new AtomicInteger());
                    winner.set(openInitialPublish(
                            runtime, userGates, lineage.parentRunId(), () ->
                                    initialRepositoryObservation(
                                            runtime, lineage.parentRunId(),
                                            "101", "101",
                                            "winner".toCharArray(),
                                            new AtomicInteger())));
                    return losing;
                });
        assertThat(outer).isEqualTo(winner.get());
        var subject = userGates.initialSubject(
                outer.subjectManifestRef()).orElseThrow();
        assertThat(subject.baseRepositoryExternalId()).isEqualTo("101");
        assertThat(subject.headRepositoryExternalId()).isEqualTo("101");
        assertThat(count(
                "flow_github_initial_publish_target_snapshot", "1 = 1"))
                .isEqualTo(targets + 1);
        assertThat(count(
                "flow_user_gate_initial_publish_subject",
                "created_by_run_id = '" + lineage.parentRunId() + "'"))
                .isEqualTo(1);
        assertThat(count(
                "flow_user_gate_initial_publish_action", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void initialPublishCorruptReplayFailsBeforeRepositoryLookup()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var subject = userGates.initialSubject(
                opened.subjectManifestRef()).orElseThrow();
        updateWithoutForeignKeys(
                "UPDATE flow_github_initial_publish_target_snapshot "
                        + "SET target_snapshot_digest = 'corrupt' "
                        + "WHERE target_snapshot_id = ?",
                subject.targetSnapshotId());

        assertThatThrownBy(() -> openInitialPublish(
                runtime, userGates, lineage.parentRunId(), () -> {
                    throw new AssertionError(
                            "corrupt replay performed provider lookup");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("target snapshot");
    }

    @Test
    void initialPublishOwnerPersistsAttemptsProofsAndOrderedReceipts()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(opened.gateId(),
                opened.revision(), opened.subjectDigest(), opened.actionDigest(),
                "initial-owner");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim branchClaim = runtime.claimNextPublish("initial-branch", TTL)
                .orElseThrow();
        var branchTarget = githubEffects.prepareInitialPublishProbe(
                branchClaim, plan.planId());

        assertThatThrownBy(() -> githubEffects.recordInitialPublishProbe(
                branchClaim,
                initialProof(branchClaim, branchTarget, Outcome.APPLIED,
                        plan.proposedHead(), null), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
        githubEffects.recordInitialPublishProbe(branchClaim,
                initialProof(branchClaim, branchTarget, Outcome.ABSENT,
                        null, null), NOW);
        var branch = githubEffects.activateInitialPublishAttempt(
                branchClaim, plan.planId(), NOW);
        assertThat(githubEffects.initialPublishAttempts(plan.planId()))
                .singleElement().isEqualTo(branch.attempt());
        assertThatThrownBy(() -> githubEffects.activateInitialPublishAttempt(
                branchClaim, plan.planId(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ABSENT");

        consumeInitial(runtime, branchClaim, branch);
        var branchProbe = githubEffects.recordInitialPublishAttemptProbe(
                branchClaim, branch,
                initialProof(branchClaim, branch, Outcome.APPLIED,
                        plan.proposedHead(), null), NOW);
        var branchReceipt = githubEffects.insertInitialPublishStepReceipt(
                branchClaim, branchProbe, NOW);
        assertThat(branchReceipt.stepOrdinal()).isEqualTo(1);
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.RETRYABLE);
        assertThat(githubEffects.insertInitialPublishStepReceipt(
                branchClaim, branchProbe, NOW)).isEqualTo(branchReceipt);

        Claim prClaim = runtime.claimNextPublish("initial-pr", TTL)
                .orElseThrow();
        var prTarget = githubEffects.prepareInitialPublishProbe(
                prClaim, plan.planId());
        assertThatThrownBy(() -> githubEffects.recordInitialPublishProbe(
                prClaim,
                initialProof(prClaim, branchTarget, Outcome.ABSENT,
                        null, null), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current step");
        githubEffects.recordInitialPublishProbe(prClaim,
                initialProof(prClaim, prTarget, Outcome.ABSENT,
                        null, null), NOW);
        var draft = githubEffects.activateInitialPublishAttempt(
                prClaim, plan.planId(), NOW);
        assertThat(draft.attempt().stepOrdinal()).isEqualTo(2);
        consumeInitial(runtime, prClaim, draft);
        var identity = exactInitialPr(plan,
                "3333333333333333333333333333333333333333");
        var prProbe = githubEffects.recordInitialPublishAttemptProbe(
                prClaim, draft,
                initialProof(prClaim, draft, Outcome.APPLIED,
                        plan.proposedHead(), identity), NOW);
        var prReceipt = githubEffects.insertInitialPublishStepReceipt(
                prClaim, prProbe, NOW);

        assertThat(prReceipt.stepOrdinal()).isEqualTo(2);
        assertThat(prReceipt.prIdentity()).isEqualTo(identity);
        assertThat(githubEffects.initialPublishStepReceipts(plan.planId()))
                .extracting(receipt -> receipt.stepOrdinal())
                .containsExactly(1, 2);
        assertThat(count("flow_github_initial_pr_observation", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_github_initial_pr_receipt_detail", "1 = 1"))
                .isEqualTo(1);
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CLAIMED);
        assertThat(githubEffects.insertInitialPublishStepReceipt(
                branchClaim, branchProbe, NOW)).isEqualTo(branchReceipt);
    }

    @Test
    void initialExecutorProvesBothStepsAfterLostMutationResponses()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(
                opened.gateId(), opened.revision(), opened.subjectDigest(),
                opened.actionDigest(), "initial-executor-applied");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim claim = runtime.claimNextPublish("initial-executor", TTL)
                .orElseThrow();

        var execution = executeInitialApplied(
                runtime, userGates, githubEffects, claim, plan,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(execution.branch().kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.BRANCH_APPLIED);
        assertThat(execution.pullRequest().kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.SETTLED);
        assertThat(execution.pushes()).isEqualTo(1);
        assertThat(execution.posts()).isEqualTo(1);
        assertThat(execution.postBody())
                .doesNotContain("\"head_repo\"");
        assertThat(githubEffects.initialPublishStepReceipts(plan.planId()))
                .hasSize(2);
        assertThat(githubEffects.initialPublishStepReceipts(plan.planId())
                .getLast().prIdentity().observedBaseSha())
                .isEqualTo(plan.expectedBaseSha());
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.SUCCEEDED);
        assertThat(runtime.pullRequest(plan.prId()).orElseThrow().published())
                .isTrue();
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.CONSUMED);

        int transitions = userGates.transitions(opened.gateId()).size();
        int providerCommands = execution.providerCommands().get();
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "post-receipt-policy", "post-receipt-digest",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:99:changed"), List.of("SUCCESS"));
        var replay = execution.executor().execute(execution.prClaim());
        assertThat(replay.kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.SETTLED);
        assertThat(userGates.transitions(opened.gateId())).hasSize(transitions);
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.SUCCEEDED);
        assertThat(runtime.pullRequest(plan.prId()).orElseThrow().published())
                .isTrue();
        assertThat(execution.providerCommands()).hasValue(providerCommands);
        assertThat(count("flow_github_initial_publish_receipt", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_pr_ready_policy_revision", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_operation",
                "kind = 'OBSERVE_CI' AND owner_id IN (SELECT receipt_id "
                        + "FROM flow_github_initial_publish_receipt)"))
                .isEqualTo(1);
        var timeline = new PrTimelineProjection(dataSource)
                .page(pr.prId(), null, 100).events();
        assertThat(timeline).anySatisfy(event -> {
            assertThat(event.kind()).isEqualTo(EventKind.GATE_TRANSITION);
            assertThat(event.ownerRef().ownerId()).isEqualTo(opened.gateId());
            assertThat(event.status().name()).isEqualTo("GATE_CONSUMED");
        });
        assertThat(timeline).anySatisfy(event -> {
            assertThat(event.kind())
                    .isEqualTo(EventKind.EXTERNAL_EFFECT_RECEIPT);
            assertThat(event.ownerRef().ownerId()).isEqualTo(
                    ((GitHubInitialPublishExecutor.SettlementApplied)
                            execution.pullRequest()).settlement().resultId());
        });
        String policyDigest = jdbc.queryForObject(
                "SELECT policy_digest FROM "
                        + "flow_runtime_pr_ready_policy_revision",
                String.class);
        updateWithoutForeignKeys(
                "UPDATE flow_runtime_pr_ready_policy_revision "
                        + "SET policy_digest = 'corrupt-policy'");
        assertThatThrownBy(() -> execution.executor().execute(
                execution.prClaim()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ready-policy");
        updateWithoutForeignKeys(
                "UPDATE flow_runtime_pr_ready_policy_revision "
                        + "SET policy_digest = ?", policyDigest);
        String watchId = jdbc.queryForObject(
                "SELECT operation_id FROM flow_runtime_operation "
                        + "WHERE kind = 'OBSERVE_CI'",
                String.class);
        String watchSubjectDigest = jdbc.queryForObject(
                "SELECT subject_digest FROM flow_runtime_operation "
                        + "WHERE operation_id = ?",
                String.class, watchId);
        updateWithoutForeignKeys(
                "UPDATE flow_runtime_operation SET subject_digest = "
                        + "'corrupt-watch' WHERE operation_id = ?", watchId);
        assertThatThrownBy(() -> execution.executor().execute(
                execution.prClaim()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("watch");
        updateWithoutForeignKeys(
                "UPDATE flow_runtime_operation SET subject_digest = ? "
                        + "WHERE operation_id = ?",
                watchSubjectDigest, watchId);
        StepReceipt finalReceipt = githubEffects
                .initialPublishStepReceipts(plan.planId()).getLast();
        updateWithoutForeignKeys(
                "UPDATE flow_github_initial_publish_step_receipt "
                        + "SET receipt_digest = 'corrupt' WHERE receipt_id = ?",
                finalReceipt.receiptId());
        assertThatThrownBy(() -> execution.executor().execute(
                execution.prClaim()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt graph");
    }

    @Test
    void completeInitialRecoveryRollsBackLateFailureAndSettlesWithoutProvider()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(
                opened.gateId(), opened.revision(), opened.subjectDigest(),
                opened.actionDigest(), "initial-complete-recovery");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim branchClaim = runtime.claimNextPublish(
                "initial-complete-branch", TTL).orElseThrow();

        var interrupted = executeInitialInterrupted(
                runtime, userGates, githubEffects, branchClaim, plan,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> jdbc.execute("""
                        CREATE TRIGGER fail_initial_ready_policy
                        BEFORE INSERT ON flow_runtime_pr_ready_policy_revision
                        BEGIN SELECT RAISE(ABORT, 'forced late failure'); END
                        """));

        assertThat(interrupted.failure()).hasMessageContaining(
                "forced late failure");
        assertThat(githubEffects.initialPublishStepReceipts(plan.planId()))
                .hasSize(2);
        assertThat(count("flow_github_effect_receipt_envelope",
                "operation_id = '" + plan.operationId() + "'"))
                .isZero();
        assertThat(count("flow_github_initial_publish_receipt",
                "operation_id = '" + plan.operationId() + "'"))
                .isZero();
        assertThat(count("flow_runtime_remote_identity",
                "publication_receipt_id IS NOT NULL")).isZero();
        assertThat(count("flow_runtime_pr_ready_policy_revision", "1 = 1"))
                .isZero();
        assertThat(count("flow_runtime_operation",
                "kind = 'OBSERVE_CI'")).isZero();
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CLAIMED);
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.EXECUTING);

        int commands = interrupted.providerCommands().get();
        jdbc.execute("DROP TRIGGER fail_initial_ready_policy");
        advancePublicationClock(TTL.plusSeconds(1));
        userGates.recoverExpiredInitialPublish(
                plan.operationId(), interrupted.prClaim().generation());
        Claim replay = runtime.claimNextPublish(
                "initial-complete-replay", TTL).orElseThrow();
        assertThat(replay.generation())
                .isEqualTo(interrupted.prClaim().generation() + 1);
        var settled = interrupted.executor().execute(replay);

        assertThat(settled.kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.SETTLED);
        assertThat(interrupted.providerCommands()).hasValue(commands);
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.SUCCEEDED);
        assertThat(count("flow_github_initial_publish_receipt",
                "operation_id = '" + plan.operationId() + "'"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_pr_ready_policy_revision", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_operation",
                "kind = 'OBSERVE_CI'")).isEqualTo(1);

        assertThat(interrupted.executor().execute(replay).kind())
                .isEqualTo(GitHubInitialPublishExecutor.ResultKind.SETTLED);
        assertThat(interrupted.providerCommands()).hasValue(commands);
    }

    @Test
    void initialPublishAttemptRetryIsProbeOnlyAndCappedAtTwo()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(opened.gateId(),
                opened.revision(), opened.subjectDigest(), opened.actionDigest(),
                "initial-retry");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim first = runtime.claimNextPublish(
                "initial-first", Duration.ofSeconds(1)).orElseThrow();
        var target = githubEffects.prepareInitialPublishProbe(
                first, plan.planId());
        githubEffects.recordInitialPublishProbe(first,
                initialProof(first, target, Outcome.ABSENT, null, null), NOW);
        var attempt1 = githubEffects.activateInitialPublishAttempt(
                first, plan.planId(), NOW);
        consumeInitial(runtime, first, attempt1);
        githubEffects.recordInitialPublishAttemptProbe(first, attempt1,
                initialProof(first, attempt1, Outcome.ABSENT, null, null), NOW);

        advancePublicationClock(Duration.ofSeconds(2));
        userGates.recoverExpiredInitialPublish(
                first.operationId(), first.generation());
        userGates.recoverExpiredInitialPublish(
                first.operationId(), first.generation());
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.RETRYABLE);
        advancePublicationClock(Duration.ofSeconds(5));
        Claim second = runtime.claimNextPublish("initial-second", TTL)
                .orElseThrow();
        var secondTarget = githubEffects.prepareInitialPublishProbe(
                second, plan.planId());
        githubEffects.recordInitialPublishProbe(second,
                initialProof(second, secondTarget, Outcome.ABSENT, null, null),
                runtimeNow);
        var attempt2 = githubEffects.activateInitialPublishAttempt(
                second, plan.planId(), runtimeNow);
        assertThat(attempt2.attempt().attemptNumber()).isEqualTo(2);
        consumeInitial(runtime, second, attempt2);
        githubEffects.recordInitialPublishAttemptProbe(second, attempt2,
                initialProof(second, attempt2, Outcome.ABSENT, null, null),
                runtimeNow);

        advancePublicationClock(TTL.plusSeconds(1));
        userGates.recoverExpiredInitialPublish(
                second.operationId(), second.generation());
        advancePublicationClock(Duration.ofSeconds(5));
        Claim third = runtime.claimNextPublish("initial-third", TTL)
                .orElseThrow();
        var thirdTarget = githubEffects.prepareInitialPublishProbe(
                third, plan.planId());
        githubEffects.recordInitialPublishProbe(third,
                initialProof(third, thirdTarget, Outcome.ABSENT, null, null),
                runtimeNow);
        assertThatThrownBy(() -> githubEffects.activateInitialPublishAttempt(
                third, plan.planId(), runtimeNow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limit");
        assertThat(githubEffects.initialPublishAttempts(plan.planId()))
                .hasSize(2);
    }

    @Test
    void initialPublishPostCreateBaseRaceBindsIdentityAndSettlesPartial()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(
                opened.gateId(), opened.revision(), opened.subjectDigest(),
                opened.actionDigest(), "initial-post-create-base-race");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim claim = runtime.claimNextPublish("initial-base-race", TTL)
                .orElseThrow();
        String movedBase = "9999999999999999999999999999999999999999";

        var execution = executeInitialApplied(
                runtime, userGates, githubEffects, claim, plan,
                Clock.fixed(NOW, ZoneOffset.UTC), movedBase);

        assertThat(execution.pullRequest().kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.SETTLED);
        var settlement = ((GitHubInitialPublishExecutor.SettlementApplied)
                execution.pullRequest()).settlement();
        assertThat(settlement.succeeded()).isFalse();
        assertThat(settlement.bindsIdentity()).isTrue();
        assertThat(settlement.attentionReason()).isEqualTo(
                "REMOTE_BASE_DRIFT");
        var published = runtime.pullRequest(plan.prId()).orElseThrow();
        assertThat(published.published()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT publication_receipt_id FROM "
                        + "flow_runtime_remote_identity WHERE "
                        + "remote_identity_id = ?",
                String.class, published.remoteIdentityId()))
                .isEqualTo(githubEffects.initialPublishStepReceipts(
                        plan.planId()).getLast().receiptId());
        assertThat(count("flow_github_initial_publish_receipt", "1 = 1"))
                .isZero();
        assertThat(count("flow_runtime_pr_ready_policy_revision", "1 = 1"))
                .isZero();
        assertThat(count("flow_runtime_operation", "kind = 'OBSERVE_CI'"))
                .isZero();
        var timeline = new PrTimelineProjection(dataSource)
                .page(pr.prId(), null, 100).events();
        assertThat(timeline).anySatisfy(event -> {
            assertThat(event.kind()).isEqualTo(EventKind.GATE_TRANSITION);
            assertThat(event.ownerRef().ownerId()).isEqualTo(opened.gateId());
            assertThat(event.status().name()).isEqualTo("GATE_STALE");
        });
        assertThat(timeline).noneMatch(event ->
                event.kind() == EventKind.EXTERNAL_EFFECT_RECEIPT);
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CANCELED);
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.STALE);

        int commands = execution.providerCommands().get();
        var replay = execution.executor().execute(execution.prClaim());
        assertThat(replay.kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.SETTLED);
        assertThat(execution.providerCommands()).hasValue(commands);
    }

    @Test
    void createdPrPartialRollsBackEveryOwnerAndReplaysWithoutProvider()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(
                opened.gateId(), opened.revision(), opened.subjectDigest(),
                opened.actionDigest(), "initial-partial-rollback");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim branchClaim = runtime.claimNextPublish(
                "initial-partial-branch", TTL).orElseThrow();

        var interrupted = executeInitialPartialInterrupted(
                runtime, userGates, githubEffects, branchClaim, plan,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "9999999999999999999999999999999999999999",
                () -> jdbc.execute("""
                        CREATE TRIGGER fail_initial_partial_lifecycle
                        BEFORE INSERT ON flow_runtime_task_lifecycle_revision
                        BEGIN SELECT RAISE(ABORT, 'forced partial failure'); END
                        """));

        assertThat(interrupted.failure()).hasMessageContaining(
                "forced partial failure");
        assertThat(githubEffects.initialPublishStepReceipts(plan.planId()))
                .hasSize(2);
        assertThat(count("flow_github_initial_publish_partial_receipt",
                "operation_id = '" + plan.operationId() + "'"))
                .isZero();
        assertThat(count("flow_runtime_remote_identity",
                "publication_receipt_id IS NOT NULL")).isZero();
        assertThat(count("flow_runtime_task_lifecycle_revision",
                "operation_id = '" + plan.operationId() + "'"))
                .isZero();
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.EXECUTING);
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CLAIMED);

        int commands = interrupted.providerCommands().get();
        jdbc.execute("DROP TRIGGER fail_initial_partial_lifecycle");
        var settled = interrupted.executor().execute(interrupted.prClaim());

        assertThat(settled.kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.SETTLED);
        assertThat(interrupted.providerCommands()).hasValue(commands);
        assertThat(count("flow_github_initial_publish_partial_receipt",
                "operation_id = '" + plan.operationId() + "'"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_remote_identity",
                "publication_receipt_id IS NOT NULL")).isEqualTo(1);
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CANCELED);
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.STALE);
        assertThat(interrupted.executor().execute(interrupted.prClaim()).kind())
                .isEqualTo(GitHubInitialPublishExecutor.ResultKind.SETTLED);
        assertThat(interrupted.providerCommands()).hasValue(commands);
    }

    @Test
    void initialPublishBranchOnlyBaseDriftSettlesWithoutCreatingPr()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(
                opened.gateId(), opened.revision(), opened.subjectDigest(),
                opened.actionDigest(), "initial-branch-base-race");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim claim = runtime.claimNextPublish("initial-branch-base", TTL)
                .orElseThrow();

        var execution = executeInitialBaseDrift(
                runtime, userGates, githubEffects, claim, plan,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "8888888888888888888888888888888888888888");

        assertThat(execution.branch().kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.BRANCH_APPLIED);
        assertThat(execution.pullRequest().kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.SETTLED);
        var settlement = ((GitHubInitialPublishExecutor.SettlementApplied)
                execution.pullRequest()).settlement();
        assertThat(settlement.succeeded()).isFalse();
        assertThat(settlement.bindsIdentity()).isFalse();
        assertThat(execution.posts()).isZero();
        assertThat(runtime.pullRequest(plan.prId()).orElseThrow().published())
                .isFalse();
        assertThat(githubEffects.initialPublishStepReceipts(plan.planId()))
                .singleElement()
                .extracting(StepReceipt::stepOrdinal).isEqualTo(1);
        assertThat(count("flow_github_initial_base_preflight", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_github_initial_publish_partial_receipt",
                "kind = 'BRANCH_ONLY_BASE_DRIFT'"))
                .isEqualTo(1);
        assertThat(count("flow_github_initial_publish_receipt", "1 = 1"))
                .isZero();
        assertThat(count("flow_runtime_operation", "kind = 'OBSERVE_CI'"))
                .isZero();
        var timeline = new PrTimelineProjection(dataSource)
                .page(pr.prId(), null, 100).events();
        assertThat(timeline).anySatisfy(event -> {
            assertThat(event.kind()).isEqualTo(EventKind.GATE_TRANSITION);
            assertThat(event.ownerRef().ownerId()).isEqualTo(opened.gateId());
            assertThat(event.status().name()).isEqualTo("GATE_STALE");
        });
        assertThat(timeline).noneMatch(event ->
                event.kind() == EventKind.EXTERNAL_EFFECT_RECEIPT);
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CANCELED);
    }

    @Test
    void initialPublishPolicyDriftAfterBranchBecomesTypedPartial()
            throws Exception
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(
                opened.gateId(), opened.revision(), opened.subjectDigest(),
                opened.actionDigest(), "initial-branch-policy-drift");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim claim = runtime.claimNextPublish(
                "initial-branch-policy", TTL).orElseThrow();

        var execution = executeInitialWithBranchHook(
                runtime, userGates, githubEffects, claim, plan,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> autofix.recordPolicy(
                        task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                        "branch-drift-policy", "branch-drift-policy-digest",
                        PolicyResolution.RESOLVED, null,
                        List.of("GITHUB_CHECK:77:new"), List.of("SUCCESS")));

        assertThat(execution.branch().kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.BRANCH_APPLIED);
        assertThat(execution.pullRequest().kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.SETTLED);
        assertThat(execution.posts()).isZero();
        assertThat(githubEffects.initialPublishStepReceipts(plan.planId()))
                .singleElement()
                .extracting(StepReceipt::stepOrdinal).isEqualTo(1);
        assertThat(count("flow_github_initial_publish_partial_receipt",
                "kind = 'BRANCH_ONLY_STALE' AND reason_code = "
                        + "'LOCAL_AUTHORITY_DRIFT'"))
                .isEqualTo(1);
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CANCELED);
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.STALE);
        int commands = execution.providerCommands().get();
        assertThat(execution.executor().execute(execution.prClaim()).kind())
                .isEqualTo(GitHubInitialPublishExecutor.ResultKind.SETTLED);
        assertThat(execution.providerCommands()).hasValue(commands);
        var constructor = UserGates.InitialBranchStaleDisposition.class
                .getDeclaredConstructor(
                        Claim.class, String.class, String.class,
                        String.class, String.class);
        constructor.setAccessible(true);
        var substituted = constructor.newInstance(
                execution.prClaim(), plan.planId(),
                githubEffects.initialPublishStepReceipts(plan.planId())
                        .getFirst().receiptId(),
                "LOCAL_AUTHORITY_DRIFT", "substituted");
        assertThatThrownBy(() -> githubEffects.storeInitialBranchOnlyStale(
                substituted, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outer transaction");
    }

    @Test
    void initialPublishRetryRevalidatesAuthorityBeforeSecondMutation()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(
                opened.gateId(), opened.revision(), opened.subjectDigest(),
                opened.actionDigest(), "initial-retry-freshness");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim first = runtime.claimNextPublish(
                "initial-retry-first", TTL).orElseThrow();
        var execution = executeInitialMissingAfterMutation(
                runtime, userGates, githubEffects, first, plan,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(execution.first().kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.DEFERRED);
        assertThat(execution.pushes()).hasValue(1);
        assertThat(githubEffects.initialPublishAttempts(plan.planId()))
                .hasSize(1);
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "retry-policy-drift", "retry-policy-drift-digest",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:99:changed"), List.of("SUCCESS"));
        advancePublicationClock(Duration.ofSeconds(5));
        Claim retry = runtime.claimNextPublish(
                "initial-retry-drifted", TTL).orElseThrow();

        var result = execution.executor().execute(retry);

        assertThat(result.kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.DEFERRED);
        assertThat(execution.pushes()).hasValue(1);
        assertThat(githubEffects.initialPublishAttempts(plan.planId()))
                .hasSize(1);
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.NEEDS_ATTENTION);
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.RETRYABLE);
    }

    @Test
    void initialPublishDivergencePermanentlyPoisonsMutationAuthority()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(
                opened.gateId(), opened.revision(), opened.subjectDigest(),
                opened.actionDigest(), "initial-divergence-poison");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim claim = runtime.claimNextPublish("initial-diverged", TTL)
                .orElseThrow();
        var target = githubEffects.prepareInitialPublishProbe(
                claim, plan.planId());
        githubEffects.recordInitialPublishProbe(
                claim,
                initialProof(claim, target, Outcome.DIVERGED,
                        "3333333333333333333333333333333333333333", null),
                NOW);
        githubEffects.recordInitialPublishProbe(
                claim,
                initialProof(claim, target, Outcome.ABSENT, null, null),
                NOW);

        assertThatThrownBy(() -> githubEffects.activateInitialPublishAttempt(
                claim, plan.planId(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("diverged");
        assertThat(githubEffects.initialPublishAttempts(plan.planId()))
                .isEmpty();
    }

    @Test
    void initialPublishCallerCannotInventPoisonOrAttemptLimit()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(
                opened.gateId(), opened.revision(), opened.subjectDigest(),
                opened.actionDigest(), "initial-false-disposition");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim claim = runtime.claimNextPublish(
                "initial-false-disposition", TTL).orElseThrow();
        userGates.beginInitialPublishEffect(claim);
        var target = githubEffects.prepareInitialPublishProbe(
                claim, plan.planId());
        var absent = githubEffects.recordInitialPublishProbe(
                claim, initialProof(
                        claim, target, Outcome.ABSENT, null, null), NOW);

        assertThatThrownBy(() -> userGates.applyInitialPublishDisposition(
                claim, null, absent,
                InitialPublishDispositionKind.DIVERGENCE_LOCKED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predicate");
        assertThatThrownBy(() -> userGates.applyInitialPublishDisposition(
                claim, null, absent,
                InitialPublishDispositionKind.ATTEMPT_LIMIT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("predicate");
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.EXECUTING);
        assertThat(githubEffects.initialPublishAttempts(plan.planId()))
                .isEmpty();
    }

    @Test
    void initialPublishCrashAfterPreexistingBranchCancelsOnRecovery()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(
                        runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(
                opened.gateId(), opened.revision(), opened.subjectDigest(),
                opened.actionDigest(), "initial-divergence-crash");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim first = runtime.claimNextPublish(
                "initial-divergence-first", Duration.ofSeconds(1))
                .orElseThrow();
        userGates.beginInitialPublishEffect(first);
        var target = githubEffects.prepareInitialPublishProbe(
                first, plan.planId());
        githubEffects.recordInitialPublishProbe(
                first,
                initialProof(first, target, Outcome.DIVERGED,
                        "3333333333333333333333333333333333333333", null),
                NOW);

        advancePublicationClock(Duration.ofSeconds(2));
        userGates.recoverExpiredInitialPublish(
                first.operationId(), first.generation());
        Claim recovery = runtime.claimNextPublish(
                "initial-divergence-recovery", TTL).orElseThrow();
        var execution = executeInitialAbsent(
                runtime, userGates, githubEffects, recovery, plan,
                Clock.fixed(runtimeNow, ZoneOffset.UTC));

        assertThat(execution.result().kind()).isEqualTo(
                GitHubInitialPublishExecutor.ResultKind.CANCELED);
        assertThat(execution.pushes()).isZero();
        assertThat(githubEffects.initialPublishAttempts(plan.planId()))
                .isEmpty();
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CANCELED);
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.STALE);
    }

    @Test
    void initialPublishResponseLossRequiresProbeAndReplaysProbeClaimOnly()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(opened.gateId(),
                opened.revision(), opened.subjectDigest(), opened.actionDigest(),
                "initial-response-loss");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim first = runtime.claimNextPublish(
                "initial-lost", Duration.ofSeconds(1)).orElseThrow();
        var firstTarget = githubEffects.prepareInitialPublishProbe(
                first, plan.planId());
        githubEffects.recordInitialPublishProbe(first,
                initialProof(first, firstTarget, Outcome.ABSENT, null, null),
                NOW);
        githubEffects.activateInitialPublishAttempt(first, plan.planId(), NOW);

        advancePublicationClock(Duration.ofSeconds(2));
        userGates.recoverExpiredInitialPublish(
                first.operationId(), first.generation());
        advancePublicationClock(Duration.ofSeconds(5));
        Claim recovery = runtime.claimNextPublish("initial-recovery", TTL)
                .orElseThrow();
        var recoveryTarget = githubEffects.prepareInitialPublishProbe(
                recovery, plan.planId());
        assertThatThrownBy(() -> githubEffects.activateInitialPublishAttempt(
                recovery, plan.planId(), runtimeNow))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe claim binding");
        var applied = githubEffects.recordInitialPublishProbe(recovery,
                initialProof(recovery, recoveryTarget, Outcome.APPLIED,
                        plan.proposedHead(), null), runtimeNow);
        var receipt = githubEffects.insertInitialPublishStepReceipt(
                recovery, applied, runtimeNow);

        assertThat(githubEffects.insertInitialPublishStepReceipt(
                recovery, applied, runtimeNow)).isEqualTo(receipt);
        assertThatThrownBy(() -> githubEffects.insertInitialPublishStepReceipt(
                first, applied, runtimeNow))
                .isInstanceOf(FlowRuntime.StaleClaimException.class);
        Claim forged = new Claim(recovery.operationId(), recovery.taskId(),
                recovery.kind(), recovery.generation(), "wrong-token",
                recovery.workerId(), recovery.expiresAt());
        assertThatThrownBy(() -> githubEffects.insertInitialPublishStepReceipt(
                forged, applied, runtimeNow))
                .isInstanceOf(FlowRuntime.StaleClaimException.class);
    }

    @Test
    void initialPublishCorruptionAndLateRearmFailureFailClosed()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        var authorized = userGates.authorizeInitialPublish(opened.gateId(),
                opened.revision(), opened.subjectDigest(), opened.actionDigest(),
                "initial-corruption");
        var plan = githubEffects.initialPublishPlan(authorized.planId())
                .orElseThrow();
        Claim claim = runtime.claimNextPublish("initial-corrupt", TTL)
                .orElseThrow();
        var target = githubEffects.prepareInitialPublishProbe(
                claim, plan.planId());
        var absent = githubEffects.recordInitialPublishProbe(claim,
                initialProof(claim, target, Outcome.ABSENT, null, null), NOW);
        updateWithoutForeignKeys(
                "UPDATE flow_github_initial_publish_probe "
                        + "SET claim_token_digest = 'corrupt' WHERE probe_id = ?",
                absent.probeId());
        assertThatThrownBy(() -> githubEffects.activateInitialPublishAttempt(
                claim, plan.planId(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe");
        updateWithoutForeignKeys(
                "UPDATE flow_github_initial_publish_probe "
                        + "SET claim_token_digest = ? WHERE probe_id = ?",
                absent.claimTokenDigest(), absent.probeId());
        var activated = githubEffects.activateInitialPublishAttempt(
                claim, plan.planId(), NOW);
        consumeInitial(runtime, claim, activated);
        var applied = githubEffects.recordInitialPublishAttemptProbe(
                claim, activated,
                initialProof(claim, activated, Outcome.APPLIED,
                        plan.proposedHead(), null), NOW);
        jdbc.execute("""
                CREATE TRIGGER fail_initial_rearm
                BEFORE UPDATE OF state ON flow_runtime_operation
                WHEN NEW.result_ref LIKE 'INITIAL_STEP_RECEIPT:%'
                BEGIN SELECT RAISE(ABORT, 'forced rearm failure'); END
                """);
        assertThatThrownBy(() -> githubEffects.insertInitialPublishStepReceipt(
                claim, applied, NOW)).isInstanceOf(RuntimeException.class);
        assertThat(count("flow_github_initial_publish_step_receipt", "1 = 1"))
                .isZero();
        assertThat(runtime.operation(plan.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CLAIMED);
        jdbc.execute("DROP TRIGGER fail_initial_rearm");
        var receipt = githubEffects.insertInitialPublishStepReceipt(
                claim, applied, NOW);
        updateWithoutForeignKeys(
                "UPDATE flow_github_initial_publish_step_receipt "
                        + "SET receipt_digest = 'corrupt' WHERE receipt_id = ?",
                receipt.receiptId());
        assertThatThrownBy(() -> githubEffects.insertInitialPublishStepReceipt(
                claim, applied, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt graph");
    }

    @Test
    void initialPublishPolicyDriftStalesWithoutPublishingGraph()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        autofix.recordPolicy(task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "changed", "changed", PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));

        assertThatThrownBy(() -> userGates.authorizeInitialPublish(opened.gateId(),
                opened.revision(), opened.subjectDigest(), opened.actionDigest(),
                "stale-policy"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("REQUIRED_CI_POLICY_STALE");
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.STALE);
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(count("flow_github_initial_publish_plan", "1 = 1")).isZero();
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'")).isZero();
    }

    @Test
    void initialPublishCorruptionAndLatePlanFailureCannotPartiallyAuthorize()
    {
        FlowRuntimeTestSupport.InitialPublishLineage lineage =
                FlowRuntimeTestSupport.seedInitialPublishLineage(runtime, pr.prId());
        GateRevision opened = openInitialPublish(
                runtime, userGates, lineage.parentRunId());
        jdbc.update("UPDATE flow_user_gate_initial_publish_action "
                        + "SET target_base_ref = 'corrupt' WHERE action_ref = ?",
                opened.actionManifestRef());
        assertThatThrownBy(() -> userGates.authorizeInitialPublish(opened.gateId(),
                opened.revision(), opened.subjectDigest(), opened.actionDigest(),
                "corrupt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initial gate revision is inconsistent");
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();

        jdbc.update("UPDATE flow_user_gate_initial_publish_action "
                        + "SET target_base_ref = ? WHERE action_ref = ?",
                pr.targetBaseRef(), opened.actionManifestRef());
        jdbc.execute("""
                CREATE TRIGGER fail_initial_step
                BEFORE INSERT ON flow_github_initial_publish_step
                BEGIN SELECT RAISE(ABORT, 'forced initial step failure'); END
                """);
        assertThatThrownBy(() -> userGates.authorizeInitialPublish(opened.gateId(),
                opened.revision(), opened.subjectDigest(), opened.actionDigest(),
                "rollback"))
                .isInstanceOf(RuntimeException.class);
        assertThat(userGates.transitions(opened.gateId()).getLast().toState())
                .isEqualTo(GateState.OPEN);
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(count("flow_github_effect_plan_envelope", "1 = 1")).isZero();
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'")).isZero();
        assertThat(count("flow_runtime_dispatch_ticket",
                "operation_id IN (SELECT operation_id FROM flow_runtime_operation "
                        + "WHERE kind = 'PUBLISH')")).isZero();
    }

    @Test
    void enqueueIsAtomicLogCompleteAndIdempotent()
    {
        CiRound red = failedRound("failure-1", NOW);

        assertThatThrownBy(() -> coordinator.enqueueRepair(red.roundId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing bounded log");
        assertThat(autofix.roundById(red.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);

        var observation = red.checkObservationIds().stream()
                .findFirst().orElseThrow();
        var log = autofix.attachLog(
                observation,
                "failing output".getBytes(StandardCharsets.UTF_8),
                List.of());
        jdbc.execute("""
                CREATE TRIGGER fail_ci_inbox
                BEFORE INSERT ON flow_runtime_inbox
                WHEN NEW.source = 'CI'
                BEGIN
                    SELECT RAISE(ABORT, 'forced inbox failure');
                END
                """);
        assertThatThrownBy(() -> coordinator.enqueueRepair(red.roundId()))
                .isInstanceOf(RuntimeException.class);
        assertThat(autofix.roundById(red.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .isEmpty();
        jdbc.execute("DROP TRIGGER fail_ci_inbox");

        var first = coordinator.enqueueRepair(red.roundId());
        var duplicate = coordinator.enqueueRepair(red.roundId());

        assertThat(duplicate).isEqualTo(first);
        assertThat(first.round().state()).isEqualTo(RoundState.QUEUED);
        assertThat(first.round().failedLogRefs()).containsExactly(log.logRef());
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .hasSize(1);
        assertThat(count("flow_runtime_operation", "kind = 'RECONCILE_TASK'"))
                .isEqualTo(2);
    }

    @Test
    void newerSameHeadGreenCancelsQueuedRedBeforeWriterSelection()
    {
        CiRound old = enqueueFailedRound();
        autofix.observeCi(pr.prId(), check(
                "new-check", "new-run", "SUCCESS", "success-2",
                NOW.plusSeconds(30)));

        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(coordinator.selectNext(reconciliation)).isEmpty();

        CiRound oldStored = autofix.roundById(old.roundId()).orElseThrow();
        CiRound successor = autofix.round(
                pr.prId(), publishedHead, old.policyRevisionId()).orElseThrow();
        assertThat(oldStored.state()).isEqualTo(RoundState.SUPERSEDED);
        assertThat(oldStored.checkObservationIds())
                .isEqualTo(old.checkObservationIds());
        assertThat(successor.evidenceRevision()).isEqualTo(1);
        assertThat(successor.state()).isEqualTo(RoundState.GREEN);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.externalKey().equals(old.roundId()))
                .singleElement()
                .satisfies(work -> {
                    assertThat(work.handledByOperationId())
                            .isEqualTo(reconciliation.operationId());
                    assertThat(work.selectedByOperationId()).isNull();
                });
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isZero();
    }

    @Test
    void exactCurrentRedSelectsOneCiWriterOperation()
    {
        CiRound queued = enqueueFailedRound();

        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = coordinator.selectNext(reconciliation)
                .orElseThrow();

        assertThat(selected.kind()).isEqualTo(OperationKind.RUN_CI_FIXER);
        assertThat(selected.ownerKind()).isEqualTo("CI_ROUND");
        assertThat(selected.ownerId()).isEqualTo(queued.roundId());
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isEqualTo(selected.operationId());
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isEqualTo(1);
    }

    @Test
    void repairAttemptAllowsMissingOperationAndRunOnlyWhilePending()
    {
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", null,
                publishedHead, publishedHead, "change-set",
                null, null, List.of(), null,
                AttemptState.ACTIVE, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                null, null, List.of(), null,
                AttemptState.PENDING, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                null, null, List.of(), null,
                AttemptState.NON_CLEAN_HANDOFF, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalized repair work");
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                null, null, List.of(), "result",
                AttemptState.ACTIVE, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalized repair work");
        assertThat(new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                null, null, List.of(), "result",
                AttemptState.NON_CLEAN_HANDOFF, null, 0, NOW).state())
                .isEqualTo(AttemptState.NON_CLEAN_HANDOFF);
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                publishedHead, "output-change-set", List.of(), "result",
                AttemptState.FIX_PREPARED, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objective head");
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                "cccccccccccccccccccccccccccccccccccccccc",
                "output-change-set", List.of(), "result",
                AttemptState.NO_HEAD_CHANGE, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objective head");
    }

    @Test
    void cleanupHandoffReceiptCannotBeCallerConstructed()
    {
        assertThat(FlowRuntime.CleanupHandoff.class.getConstructors())
                .isEmpty();
    }

    @Test
    void cleanCiFixStoresOpaqueResultAndOneExactContinuation()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> commitCiChange(
                            "ci-fix.txt", "fixed\n", "fix CI"));
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.FAILED,
                            "looks strange; verdict=whatever; {not-json}",
                            "model-ended-after-commit");
                });

        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);
        CiRepairAttempt attempt = autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow();
        ChangeSetRevision output = runtime.currentChangeSet(task.taskId())
                .orElseThrow();

        assertThat(result.finalContent())
                .isEqualTo("looks strange; verdict=whatever; {not-json}");
        assertThat(attempt.state()).isEqualTo(AttemptState.FIX_PREPARED);
        assertThat(attempt.outputLocalHead()).isEqualTo(output.headSha());
        assertThat(attempt.outputChangeSetRevisionId())
                .isEqualTo(output.changeSetRevisionId());
        assertThat(attempt.resultRef()).isEqualTo(result.resultId());
        assertThat(output.sourceRunId())
                .isEqualTo(started.binding().run().runId());
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .singleElement()
                .satisfies(ready -> {
                    assertThat(ready.agentResultId()).isEqualTo(result.resultId());
                    assertThat(ready.externalKey()).isEqualTo(attempt.attemptId());
                    assertThat(ready.subjectHead()).isEqualTo(output.headSha());
                    assertThat(ready.payloadRef()).contains("FIX_PREPARED");
                });
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();

        AgentResult replay = coordinator.finalizeRepairAttempt(
                attempt.attemptId(),
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.FAILED,
                        "looks strange; verdict=whatever; {not-json}",
                        "model-ended-after-commit"),
                repositoryRoot);
        assertThat(replay).isEqualTo(result);
        assertThatThrownBy(() -> runtime.finishCiAgentRun(
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                TerminalOutcome.FAILED,
                result.finalContent(),
                result.errorRef(),
                "different-attempt",
                CiFixOutcome.FIX_PREPARED,
                output.headSha(),
                output.changeSetRevisionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("continuation identity");
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .hasSize(1);
    }

    @Test
    void cleanNoHeadChangeSettlesWithoutParsingAgentText()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.COMPLETED, "no changes were needed", null));

        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);
        CiRepairAttempt attempt = autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow();

        assertThat(attempt.state()).isEqualTo(AttemptState.NO_HEAD_CHANGE);
        assertThat(attempt.outputLocalHead()).isEqualTo(publishedHead);
        assertThat(attempt.resultRef()).isEqualTo(result.resultId());
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .singleElement()
                .satisfies(ready -> {
                    assertThat(ready.subjectHead()).isEqualTo(publishedHead);
                    assertThat(ready.payloadRef()).contains("NO_HEAD_CHANGE");
                });
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
    }

    @Test
    void launchRedeliveryReusesLiveExecutionAndNeverRerunsBody()
            throws Exception
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var bodyStarts = new AtomicInteger();
        var body = (Function<
                InProcessWriterAgentSupervisor.WriterToolCapability,
                InProcessWriterAgentSupervisor.AgentCompletion>) capability -> {
                    bodyStarts.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test body timed out");
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                };
        var first = coordinator.launchRepair(
                supervisor, started.binding(), started.claim(), started.fence(),
                repositoryRoot, body);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        RepairBinding redelivered = coordinator.beginRepair(
                started.binding().attempt().roundId(),
                started.claim(),
                started.fence());
        var second = coordinator.launchRepair(
                supervisor, redelivered, started.claim(), started.fence(),
                repositoryRoot, body);

        assertThat(second).isEqualTo(first);
        assertThat(bodyStarts).hasValue(1);
        assertThat(count("flow_runtime_agent_run", "role = 'CI_FIXER'"))
                .isEqualTo(1);
        release.countDown();
        coordinator.awaitRepair(supervisor, redelivered, second, TTL);
    }

    @Test
    void launchRejectsCallerModifiedRepairBindingBeforeBodyExposure()
    {
        StartedRepair started = startRepair();
        CiRepairAttempt attempt = started.binding().attempt();
        CiRepairAttempt changed = new CiRepairAttempt(
                attempt.attemptId(),
                attempt.roundId(),
                attempt.operationId(),
                attempt.agentRunId(),
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                attempt.inputRemoteHead(),
                attempt.inputChangeSetRevisionId(),
                attempt.outputLocalHead(),
                attempt.outputChangeSetRevisionId(),
                attempt.localCheckRunIds(),
                attempt.resultRef(),
                attempt.state(),
                attempt.retryOfAttemptId(),
                attempt.retryOrdinal(),
                attempt.createdAt());
        var bodies = new AtomicInteger();

        assertThatThrownBy(() -> coordinator.launchRepair(
                new InProcessWriterAgentSupervisor(runtime),
                new RepairBinding(changed, started.binding().run()),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active attempt");
        assertThat(bodies).hasValue(0);
    }

    @Test
    void directFinalizationCannotInspectBeforeExactThreadStops()
            throws Exception
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var toolCalls = new AtomicInteger();
        Path transientDirty = Path.of(task.worktreePath())
                .resolve("transient-dirty.txt");
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> {
                        toolCalls.incrementAndGet();
                        try {
                            Files.writeString(
                                    transientDirty,
                                    "transient\n",
                                    StandardCharsets.UTF_8);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test body timed out");
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    capability.runTool(() -> {
                        toolCalls.incrementAndGet();
                        try {
                            Files.delete(transientDirty);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> coordinator.finalizeRepairAttempt(
                started.binding().attempt().attemptId(),
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.COMPLETED, "forged-early", null),
                repositoryRoot))
                .isInstanceOf(IllegalStateException.class);
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(AttemptState.ACTIVE);
        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isEmpty();
        assertThat(autofix.roundById(
                started.binding().attempt().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.ACTIVE);
        assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow()
                .headSha()).isEqualTo(publishedHead);

        release.countDown();
        coordinator.awaitRepair(supervisor, started.binding(), handle, TTL);
        assertThat(toolCalls).hasValue(2);
    }

    @Test
    void lateCiWriteFailureRollsBackWholeFinishAndExactRetryCommits()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var bodies = new AtomicInteger();
        var tools = new AtomicInteger();
        int changeSetsBefore = count(
                "flow_runtime_change_set_revision", "1 = 1");
        int reconciliationsBefore = count(
                "flow_runtime_operation", "kind = 'RECONCILE_TASK'");
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    capability.runTool(() -> {
                        tools.incrementAndGet();
                        commitCiChange("rollback.txt", "fixed\n", "fix CI");
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        jdbc.execute("""
                CREATE TRIGGER fail_ci_attempt_finish
                BEFORE UPDATE ON flow_ci_repair_attempt
                WHEN NEW.state IN ('FIX_PREPARED', 'NO_HEAD_CHANGE')
                BEGIN
                    SELECT RAISE(ABORT, 'forced CI attempt failure');
                END
                """);

        assertThatThrownBy(() -> coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL))
                .isInstanceOf(RuntimeException.class);

        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isEmpty();
        assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow()
                .headSha()).isEqualTo(publishedHead);
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(AttemptState.ACTIVE);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(started.claim().operationId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isOne();
        assertThat(runtime.operation(started.claim().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.CLAIMED);
        assertThat(runtime.operation(started.claim().operationId())
                .orElseThrow().resultRef()).isNull();
        assertThat(count(
                "flow_runtime_dispatch_ticket",
                "operation_id = '" + started.claim().operationId()
                        + "' AND delivery_state = 'CLAIMED'"))
                .isOne();
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> {
                    assertThat(session.state()).isEqualTo(SessionState.RUNNING);
                    assertThat(session.lastRunId())
                            .isEqualTo(started.binding().run().runId());
                });
        assertThat(count(
                "flow_runtime_inbox",
                "selected_by_operation_id = '"
                        + started.claim().operationId()
                        + "' AND kind = 'FINAL_RED'"
                        + " AND handled_by_operation_id IS NULL"))
                .isOne();
        assertThat(count("flow_runtime_change_set_revision", "1 = 1"))
                .isEqualTo(changeSetsBefore);
        assertThat(count(
                "flow_runtime_operation", "kind = 'RECONCILE_TASK'"))
                .isEqualTo(reconciliationsBefore);

        jdbc.execute("DROP TRIGGER fail_ci_attempt_finish");
        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);

        assertThat(result.finalContent()).isEqualTo("opaque");
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
        assertThat(count("flow_runtime_agent_run", "role = 'CI_FIXER'"))
                .isOne();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        assertThat(runtime.operation(started.claim().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(count(
                "flow_runtime_dispatch_ticket",
                "operation_id = '" + started.claim().operationId()
                        + "' AND delivery_state = 'DONE'"))
                .isOne();
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> assertThat(session.state())
                        .isEqualTo(SessionState.IDLE));
        assertThat(count(
                "flow_runtime_inbox",
                "handled_by_operation_id = '"
                        + started.claim().operationId()
                        + "' AND kind = 'FINAL_RED'"))
                .isOne();
    }

    @Test
    void newerSameHeadEvidenceDoesNotInterruptActiveFix() throws Exception
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var bodies = new AtomicInteger();
        var tools = new AtomicInteger();
        Function<InProcessWriterAgentSupervisor.WriterToolCapability,
                InProcessWriterAgentSupervisor.AgentCompletion> body =
                capability -> {
                    bodies.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test body timed out");
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    capability.runTool(() -> {
                        tools.incrementAndGet();
                        commitCiChange("new-evidence.txt", "fixed\n", "fix CI");
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                };
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                body);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        var observation = autofix.observeCi(pr.prId(), check(
                "new-active-check", "new-active-run", "FAILURE",
                "new-active-failure", NOW.plusSeconds(60)));
        autofix.attachLog(
                observation.observationId(),
                "new failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        CiRound successor = ((FinalizedRound) autofix.finalizeHeadSnapshot(
                pr.prId(), publishedHead)).round();
        assertThat(autofix.roundById(
                started.binding().attempt().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);
        assertThat(successor.state()).isEqualTo(RoundState.FINAL_RED);

        RepairBinding redelivered = coordinator.beginRepair(
                started.binding().attempt().roundId(),
                started.claim(),
                started.fence());
        var duplicateHandle = coordinator.launchRepair(
                supervisor,
                redelivered,
                started.claim(),
                started.fence(),
                repositoryRoot,
                body);
        assertThat(duplicateHandle).isEqualTo(handle);
        assertThat(redelivered.attempt()).isEqualTo(started.binding().attempt());

        release.countDown();
        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);

        assertThat(result.finalContent()).isEqualTo("done");
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(AttemptState.FIX_PREPARED);
        assertThat(autofix.roundById(
                started.binding().attempt().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);
        assertThat(autofix.roundById(successor.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .hasSize(1);
    }

    @Test
    void boundQueuedRepairSurvivesSameHeadEvidenceAndRuntimeRestart()
    {
        StartedRepair started = startRepair();
        var bodies = new AtomicInteger();
        int processAttemptsBefore = count(
                "flow_runtime_agent_process_attempt", "1 = 1");
        var observation = autofix.observeCi(pr.prId(), check(
                "prelaunch-check", "prelaunch-run", "FAILURE",
                "prelaunch-failure", NOW.plusSeconds(60)));
        autofix.attachLog(
                observation.observationId(),
                "new failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        CiRound successor = ((FinalizedRound) autofix.finalizeHeadSnapshot(
                pr.prId(), publishedHead)).round();
        assertThat(autofix.roundById(
                started.binding().attempt().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);

        restart();
        RepairBinding redelivered = coordinator.beginRepair(
                started.binding().attempt().roundId(),
                started.claim(),
                started.fence());
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchRepair(
                supervisor,
                redelivered,
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        coordinator.awaitRepair(supervisor, redelivered, handle, TTL);

        assertThat(redelivered.attempt()).isEqualTo(started.binding().attempt());
        assertThat(redelivered.run().runId())
                .isEqualTo(started.binding().run().runId());
        assertThat(bodies).hasValue(1);
        assertThat(autofix.roundById(
                started.binding().attempt().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);
        assertThat(autofix.roundById(successor.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);
        assertThat(count("flow_runtime_agent_process_attempt", "1 = 1"))
                .isEqualTo(processAttemptsBefore + 1);
        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isPresent();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
    }

    @Test
    void dirtyStoppedFixAtomicallyReservesOneCleanupSuccessor()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var bodies = new AtomicInteger();
        var tools = new AtomicInteger();
        Path dirtyPath = Path.of(task.worktreePath()).resolve("dirty.txt");
        var completion = new InProcessWriterAgentSupervisor.AgentCompletion(
                TerminalOutcome.COMPLETED,
                "looks good; verdict=PASSED but workspace is dirty",
                null);
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    capability.runTool(() -> {
                        tools.incrementAndGet();
                        commitCiChange("committed.txt", "candidate\n", "candidate");
                        try {
                            Files.writeString(
                                    dirtyPath,
                                    "dirty\n",
                                    StandardCharsets.UTF_8);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return completion;
                });

        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);
        CiRepairAttempt attempt = autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow();
        var seal = autofix.cleanupSealForRepair(attempt.attemptId())
                .orElseThrow();
        Operation successor = runtime.operation(seal.successorOperationId())
                .orElseThrow();

        assertThat(result.finalContent()).isEqualTo(completion.finalContent());
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
        assertThat(attempt.state()).isEqualTo(AttemptState.NON_CLEAN_HANDOFF);
        assertThat(attempt.resultRef()).isEqualTo(result.resultId());
        assertThat(attempt.outputLocalHead()).isNull();
        assertThat(attempt.outputChangeSetRevisionId()).isNull();
        assertThat(seal.actualHead()).isEqualTo(
                gitOutput(Path.of(task.worktreePath()), "rev-parse", "HEAD"));
        assertThat(seal.actualHead()).isNotEqualTo(publishedHead);
        assertThat(seal.successorOperationId())
                .isEqualTo(successor.operationId());
        assertThat(successor.ownerKind()).isEqualTo("CI_CLEANUP");
        assertThat(successor.ownerId()).isEqualTo(seal.cleanupId());
        assertThat(successor.state()).isEqualTo(OperationState.READY);
        assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow()
                .headSha()).isEqualTo(publishedHead);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(successor.operationId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        assertThat(runtime.runForOperation(successor.operationId())).isEmpty();
        String cleanupTicketPredicate = """
                operation_id = '%s' AND delivery_state = 'AVAILABLE'
                AND claim_generation = 0
                AND claim_owner IS NULL
                AND claim_token IS NULL
                """.formatted(successor.operationId());
        assertThat(count(
                "flow_runtime_dispatch_ticket",
                cleanupTicketPredicate))
                .isOne();
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> {
                    assertThat(session.state()).isEqualTo(SessionState.IDLE);
                    assertThat(session.lastRunId())
                            .isEqualTo(started.binding().run().runId());
                });
        assertThat(runtime.operation(started.claim().operationId())
                .orElseThrow().resultRef()).isEqualTo(result.resultId());
        assertThat(autofix.roundById(attempt.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.ACTIVE);

        Claim cleanupClaim = claim(OperationKind.RUN_CI_FIXER);
        assertThat(cleanupClaim.operationId())
                .isEqualTo(successor.operationId());
        assertThat(runtime.operation(successor.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CLAIMED);
        assertThat(runtime.runForOperation(successor.operationId())).isEmpty();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();

        restart();
        AgentResult replay = coordinator.finalizeRepairAttempt(
                attempt.attemptId(),
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                completion,
                repositoryRoot);
        assertThat(replay).isEqualTo(result);
        assertThat(autofix.cleanupSealForRepair(attempt.attemptId()))
                .contains(seal);
        assertThat(count("flow_ci_cleanup_seal", "1 = 1")).isOne();
        assertThat(count("flow_runtime_operation", "owner_kind = 'CI_CLEANUP'"))
                .isOne();
        assertThatThrownBy(() -> coordinator.finalizeRepairAttempt(
                attempt.attemptId(),
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.COMPLETED, "changed prose", null),
                repositoryRoot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal content");
        NonCleanInspection persistedSeal = new NonCleanInspection(
                seal.actualHead(),
                seal.branchHead(),
                seal.attachmentState(),
                seal.kind(),
                seal.operations(),
                seal.stateDigest());
        assertThatThrownBy(() -> runtime.replayStoppedCiCleanupHandoff(
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                completion.terminalOutcome(),
                completion.finalContent(),
                completion.errorRef(),
                attempt.attemptId(),
                seal.cleanupId(),
                attempt.inputChangeSetRevisionId(),
                attempt.inputLocalHead(),
                persistedSeal,
                "0".repeat(64),
                seal.successorOperationId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
    }

    @Test
    void sealedCleanupReusesSessionAndAdoptsOneOpaqueCleanCandidate()
    {
        ReservedCleanup reserved = reserveCleanup("clean-success");
        String logicalHead = reserved.predecessor().inputLocalHead();
        String sessionId = runtime.session(task.taskId(), AgentRole.CI_FIXER)
                .orElseThrow().sessionId();

        assertThatThrownBy(() -> runtime.acquireWriterLease(
                reserved.claim(),
                AgentRole.CI_FIXER,
                new WorktreeSnapshot(logicalHead, "forged", "forged"),
                TTL))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("cleanup");
        CleanupBinding binding = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        assertThat(binding.run().headSha()).isEqualTo(logicalHead);
        assertThat(binding.fence().headSha()).isEqualTo(logicalHead);
        assertThat(binding.fence().treeDigest())
                .isEqualTo(reserved.seal().stateDigest());
        assertThat(binding.run().capabilitySetRef())
                .isEqualTo("ci-cleanup-capabilities:v1");
        assertThat(binding.seal().actualHead()).isNotEqualTo(logicalHead);
        assertThat(binding.run().sessionId()).isEqualTo(sessionId);
        assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow().headSha())
                .isEqualTo(logicalHead);
        int finalRedInboxCount = count(
                "flow_runtime_inbox", "kind = 'FINAL_RED'");
        var newerObservation = autofix.observeCi(pr.prId(), check(
                "cleanup-new-check", "cleanup-new-run", "FAILURE",
                "cleanup-new-failure", NOW.plusSeconds(60)));
        autofix.attachLog(
                newerObservation.observationId(),
                "new failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        CiRound newerRound = ((FinalizedRound) autofix.finalizeHeadSnapshot(
                pr.prId(), publishedHead)).round();
        assertThat(autofix.roundById(
                reserved.predecessor().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);

        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger tools = new AtomicInteger();
        var completion = new InProcessWriterAgentSupervisor.AgentCompletion(
                TerminalOutcome.FAILED,
                "{\"verdict\":\"dirty\"}; arbitrary prose",
                "model-failed-after-cleanup");
        var handle = coordinator.launchCleanup(
                supervisor,
                binding,
                reserved.claim(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    capability.runTool(() -> {
                        tools.incrementAndGet();
                        try {
                            Files.delete(reserved.dirtyPath());
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        commitCiChange(
                                "cleanup-result.txt", "clean\n", "cleanup CI");
                    });
                    return completion;
                });

        AgentResult result = coordinator.awaitCleanup(
                supervisor, binding, handle, TTL);
        CiCleanupCompletion stored = autofix.cleanupCompletion(
                reserved.seal().cleanupId()).orElseThrow();
        ChangeSetRevision output = runtime.currentChangeSet(task.taskId())
                .orElseThrow();

        assertThat(result.finalContent()).isEqualTo(completion.finalContent());
        assertThat(stored.outcome()).isEqualTo(CleanupOutcome.FIX_PREPARED);
        assertThat(stored.runId()).isEqualTo(binding.run().runId());
        assertThat(stored.resultRef()).isEqualTo(result.resultId());
        assertThat(stored.outputHead()).isEqualTo(output.headSha());
        assertThat(output.previousHeadSha()).isEqualTo(logicalHead);
        assertThat(output.previousChangeSetRevisionId())
                .isEqualTo(reserved.predecessor()
                        .inputChangeSetRevisionId());
        assertThat(output.sourceOperationId())
                .isEqualTo(reserved.claim().operationId());
        assertThat(output.sourceRunId()).isEqualTo(binding.run().runId());
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .singleElement()
                .satisfies(work -> {
                    assertThat(work.externalKey())
                            .isEqualTo(reserved.seal().cleanupId());
                    assertThat(work.agentResultId()).isEqualTo(result.resultId());
                });
        assertThat(autofix.roundById(newerRound.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);
        assertThat(count("flow_runtime_inbox", "kind = 'FINAL_RED'"))
                .isEqualTo(finalRedInboxCount);
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);

        AgentResult replay = coordinator.finalizeCleanup(
                reserved.seal().cleanupId(),
                binding.run().runId(),
                reserved.claim(),
                binding.fence(),
                completion,
                repositoryRoot);
        assertThat(replay).isEqualTo(result);
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
        AgentResult predecessorReplay = coordinator.finalizeRepairAttempt(
                reserved.predecessor().attemptId(),
                reserved.repair().binding().run().runId(),
                reserved.repair().claim(),
                reserved.repair().fence(),
                reserved.predecessorCompletion(),
                repositoryRoot);
        assertThat(predecessorReplay.resultId())
                .isEqualTo(reserved.predecessor().resultRef());
        assertThatThrownBy(() -> runtime.finishCiAgentRun(
                binding.run().runId(),
                reserved.claim(),
                binding.fence(),
                completion.terminalOutcome(),
                completion.finalContent(),
                completion.errorRef(),
                reserved.predecessor().attemptId(),
                CiFixOutcome.FIX_PREPARED,
                                output.headSha(),
                                output.changeSetRevisionId()))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class);
    }

    @Test
    void cleanupRunCannotMintAThirdCleanupSuccessor()
    {
        ReservedCleanup reserved = reserveCleanup("no-third-cleanup");
        CleanupBinding binding = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        var completion = new InProcessWriterAgentSupervisor.AgentCompletion(
                TerminalOutcome.COMPLETED, "still non-clean", null);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        String finalizerKey = "test:cleanup-owner-guard";
        var handle = supervisor.launch(
                binding.run().runId(),
                reserved.claim(),
                binding.fence(),
                finalizerKey,
                (runId, claim, fence, stoppedCompletion) -> {
                    assertThatThrownBy(() -> runtime.prepareNonCleanState(
                            claim,
                            fence,
                            repositoryRoot,
                            reserved.predecessor()
                                    .inputChangeSetRevisionId()))
                            .isInstanceOf(
                                    FlowRuntime.MutationRejectedException.class)
                            .hasMessageContaining("CI round");
                    var prepared = runtime.prepareCiCleanupFinalState(
                            claim,
                            fence,
                            repositoryRoot,
                            reserved.predecessor()
                                    .inputChangeSetRevisionId());
                    assertThat(prepared.nonClean()).isPresent();
                    assertThatThrownBy(() ->
                            runtime.handoffStoppedCiRunToCleanup(
                                    runId,
                                    claim,
                                    fence,
                                    stoppedCompletion.terminalOutcome(),
                                    stoppedCompletion.finalContent(),
                                    stoppedCompletion.errorRef(),
                                    reserved.predecessor().attemptId(),
                                    prepared.nonClean().orElseThrow()))
                            .isInstanceOf(
                                    FlowRuntime.MutationRejectedException.class)
                            .hasMessageContaining("CI round");
                    return coordinator.finalizeCleanup(
                            reserved.seal().cleanupId(),
                            runId,
                            claim,
                            fence,
                            stoppedCompletion,
                            repositoryRoot);
                },
                ignored -> completion);

        supervisor.awaitAndFinalize(handle, TTL, finalizerKey);

        assertThat(count("flow_runtime_operation", "owner_kind = 'CI_CLEANUP'"))
                .isOne();
        assertThat(autofix.cleanupCompletion(reserved.seal().cleanupId()))
                .hasValueSatisfying(stored -> assertThat(stored.outcome())
                        .isEqualTo(CleanupOutcome.NEEDS_ATTENTION));
    }

    @Test
    void secondDirtyCleanupStoresAttentionAndBlocksEveryLaterMutation()
    {
        ReservedCleanup reserved = reserveCleanup("second-dirty");
        CleanupBinding binding = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        Path secondDirty = Path.of(task.worktreePath()).resolve(
                "second-dirty.txt");
        var handle = coordinator.launchCleanup(
                supervisor,
                binding,
                reserved.claim(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> {
                        try {
                            Files.writeString(
                                    secondDirty,
                                    "still dirty\n",
                                    StandardCharsets.UTF_8);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "verdict=clean (ignored)",
                            null);
                });

        coordinator.awaitCleanup(supervisor, binding, handle, TTL);
        CiCleanupCompletion stored = autofix.cleanupCompletion(
                reserved.seal().cleanupId()).orElseThrow();
        Task blocked = runtime.task(task.taskId()).orElseThrow();

        assertThat(stored.outcome()).isEqualTo(CleanupOutcome.NEEDS_ATTENTION);
        assertThat(stored.finalStateDigest()).isNotBlank();
        assertThat(blocked.status()).isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(blocked.selectedWriterOperationId()).isNull();
        assertThat(blocked.waitingMutationStateRef())
                .isEqualTo("ci-cleanup-attention:" + reserved.seal().cleanupId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        assertThat(count("flow_runtime_operation", "owner_kind = 'CI_CLEANUP'"))
                .isOne();
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .isEmpty();
        assertThatThrownBy(() -> runtime.transitionTask(
                blocked.taskId(),
                blocked.currentLifecycleRevisionId(),
                TaskStatus.ACTIVE,
                "UNSAFE_RESUME",
                "test:unsafe"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("typed recovery");
        assertThatThrownBy(() -> runtime.transitionTask(
                blocked.taskId(),
                blocked.currentLifecycleRevisionId(),
                TaskStatus.CANCELED,
                "UNSAFE_CANCEL",
                "test:unsafe"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class);
        assertThatThrownBy(() -> runtime.transitionTask(
                blocked.taskId(),
                blocked.currentLifecycleRevisionId(),
                TaskStatus.COMPLETED,
                "UNSAFE_COMPLETE",
                "test:unsafe"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class);
        assertThat(runtime.claimNext("blocked-cleanup-worker", TTL)).isEmpty();
        AgentResult predecessorReplay = coordinator.finalizeRepairAttempt(
                reserved.predecessor().attemptId(),
                reserved.repair().binding().run().runId(),
                reserved.repair().claim(),
                reserved.repair().fence(),
                reserved.predecessorCompletion(),
                repositoryRoot);
        assertThat(predecessorReplay.resultId())
                .isEqualTo(reserved.predecessor().resultRef());
    }

    @Test
    void changedSealedStateBlocksBeforeCleanupBodyAndReplaysExactly()
            throws IOException
    {
        ReservedCleanup reserved = reserveCleanup("admission-mismatch");
        Files.writeString(
                reserved.dirtyPath(),
                "changed after seal\n",
                StandardCharsets.UTF_8);

        assertThat(coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL)).isEmpty();
        CiCleanupCompletion blocked = autofix.cleanupCompletion(
                reserved.seal().cleanupId()).orElseThrow();
        assertThat(blocked.outcome())
                .isEqualTo(CleanupOutcome.ADMISSION_BLOCKED);
        assertThat(blocked.runId()).isNull();
        assertThat(blocked.resultRef()).isNull();
        assertThat(blocked.finalStateDigest())
                .isNotEqualTo(reserved.seal().stateDigest());
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.runForOperation(reserved.claim().operationId()))
                .isEmpty();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        assertThat(coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL)).isEmpty();
        assertThat(count("flow_ci_cleanup_completion", "1 = 1")).isOne();
    }

    @Test
    void unexpectedlyCleanSealedStateBlocksBeforeCleanupRun()
    {
        ReservedCleanup reserved = reserveCleanup("admission-clean");
        Path worktree = Path.of(task.worktreePath());
        gitOutput(
                worktree,
                "reset",
                "--hard",
                reserved.predecessor().inputLocalHead());
        gitOutput(worktree, "clean", "-fd");

        assertThat(coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL)).isEmpty();

        assertThat(autofix.cleanupCompletion(reserved.seal().cleanupId()))
                .hasValueSatisfying(blocked -> {
                    assertThat(blocked.outcome())
                            .isEqualTo(CleanupOutcome.ADMISSION_BLOCKED);
                    assertThat(blocked.inspectionFailureCode())
                            .isEqualTo(FailureCode.CLEAN);
                    assertThat(blocked.runId()).isNull();
                    assertThat(blocked.resultRef()).isNull();
                });
        assertThat(runtime.runForOperation(reserved.claim().operationId()))
                .isEmpty();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
    }

    @Test
    void cleanupCanObjectivelyRestoreLogicalInputWithoutHeadChange()
    {
        ReservedCleanup reserved = reserveCleanup("restore-input");
        CleanupBinding binding = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchCleanup(
                supervisor,
                binding,
                reserved.claim(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> {
                        Path worktree = Path.of(task.worktreePath());
                        gitOutput(
                                worktree,
                                "reset",
                                "--hard",
                                reserved.predecessor().inputLocalHead());
                        gitOutput(worktree, "clean", "-fd");
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "I claim a fix, but Git decides",
                            null);
                });

        coordinator.awaitCleanup(supervisor, binding, handle, TTL);
        CiCleanupCompletion completion = autofix.cleanupCompletion(
                reserved.seal().cleanupId()).orElseThrow();
        ChangeSetRevision restored = runtime.currentChangeSet(task.taskId())
                .orElseThrow();

        assertThat(completion.outcome())
                .isEqualTo(CleanupOutcome.NO_HEAD_CHANGE);
        assertThat(restored.headSha())
                .isEqualTo(reserved.predecessor().inputLocalHead());
        assertThat(restored.changeSetRevisionId())
                .isNotEqualTo(reserved.predecessor()
                        .inputChangeSetRevisionId());
        assertThat(restored.previousChangeSetRevisionId())
                .isEqualTo(reserved.predecessor()
                        .inputChangeSetRevisionId());
    }

    @Test
    void neverLaunchedCleanupRecoveryReinspectsAndReusesQueuedRun()
    {
        ReservedCleanup reserved = reserveCleanup("recover-start");
        CleanupBinding first = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket
                SET claim_expires_at = ? WHERE operation_id = ?
                """,
                NOW.minusMillis(1).toEpochMilli(),
                reserved.claim().operationId());
        assertThat(runtime.recoverExpiredClaim(
                reserved.claim().operationId(),
                reserved.claim().generation())).isTrue();
        runtime.redriveRetryable(reserved.claim().operationId());
        Claim recovered = claim(OperationKind.RUN_CI_FIXER);
        restart();

        CleanupBinding redelivered = coordinator.beginCleanup(
                recovered, repositoryRoot, TTL).orElseThrow();

        assertThat(redelivered.run().runId()).isEqualTo(first.run().runId());
        assertThat(redelivered.fence().claimGeneration())
                .isEqualTo(recovered.generation());
        assertThat(redelivered.fence().claimTokenDigest())
                .isNotEqualTo(first.fence().claimTokenDigest());
        assertThat(count(
                "flow_runtime_agent_run",
                "operation_id = '" + recovered.operationId() + "'"))
                .isOne();
        assertThat(count(
                "flow_runtime_agent_process_attempt",
                "operation_id = '" + recovered.operationId() + "'"))
                .isZero();
    }
}
