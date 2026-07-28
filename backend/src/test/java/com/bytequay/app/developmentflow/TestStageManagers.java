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
package com.bytequay.app.developmentflow;

import com.bytequay.app.developmentflow.stage.CleanupStageManager;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.COMMAND_ID_CONFLICT;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.NOT_FOUND;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_EPOCH;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_GENERATION;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.WRONG_STAGE_KIND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestStageManagers
{
    @Test
    void appliesEveryStandaloneStructuralCommand()
    {
        assertMove(StageKind.PLAN, StageCheckpoint.DRAFTING, StageCheckpoint.SELF_REVIEW,
                true, fixture -> fixture.plan.acceptDrafted(fixture.result("drafted")));
        assertMove(StageKind.PLAN, StageCheckpoint.AWAITING_APPROVAL,
                StageCheckpoint.DRAFTING, false,
                fixture -> fixture.plan.reviseBeforeApproval(fixture.revision("revise")));

        Fixture structuralReplay = new Fixture(
                StageKind.PLAN, StageCheckpoint.AWAITING_APPROVAL, false,
                TaskLifecycle.ACTIVE);
        assertThat(structuralReplay.plan.reviseBeforeApproval(
                structuralReplay.revision("same-structural-id")).disposition())
                .isEqualTo(CommandResult.Disposition.APPLIED);
        assertRejected(CommandRejectedException.Reason.COMMAND_ID_CONFLICT,
                () -> structuralReplay.plan.reviseBeforeApproval(
                        structuralReplay.revision(new StageManager.Command(
                                "same-structural-id", "user", "task", 3,
                                "stage", 2, 10))));

        assertMove(StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.IMPLEMENTING,
                StageCheckpoint.VALIDATING, true,
                fixture -> fixture.local.acceptImplementation(fixture.result("implemented")));
        assertMove(StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.VALIDATING,
                StageCheckpoint.BRAIN_REVIEW, true,
                fixture -> fixture.local.acceptValidation(fixture.result("validated")));
        assertMove(StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.ADDRESSING_BRAIN_FINDINGS,
                StageCheckpoint.IMPLEMENTING, true,
                fixture -> fixture.local.acceptBrainFixes(fixture.result("brain-fixes")));
        assertMove(StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.LOCAL_REVIEW,
                StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK, false,
                fixture -> fixture.local.submitLocalFeedback(fixture.localFeedback("feedback")));
        assertMove(StageKind.LOCAL_DEVELOPMENT,
                StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK,
                StageCheckpoint.IMPLEMENTING, true,
                fixture -> fixture.local.acceptLocalFeedbackFixes(
                        fixture.result("feedback-fixes")));
        assertMove(StageKind.REMOTE_DEVELOPMENT, StageCheckpoint.WAITING_CI,
                StageCheckpoint.AWAITING_READY, true,
                fixture -> fixture.remote.acceptCi(fixture.result("ci")));
        assertMove(StageKind.REMOTE_DEVELOPMENT, StageCheckpoint.AWAITING_READY,
                StageCheckpoint.WAITING_REMOTE_REVIEW, true,
                fixture -> fixture.remote.acceptReady(fixture.result("ready")));
        assertMove(StageKind.REMOTE_DEVELOPMENT, StageCheckpoint.WAITING_REMOTE_REVIEW,
                StageCheckpoint.ADDRESSING_REMOTE_FEEDBACK, false,
                fixture -> fixture.remote.beginRemoteFeedback(fixture.remoteFeedback("feedback")));
        assertMove(StageKind.REMOTE_DEVELOPMENT,
                StageCheckpoint.ADDRESSING_REMOTE_FEEDBACK,
                StageCheckpoint.WAITING_CI, true,
                fixture -> fixture.remote.acceptRemoteFeedbackPush(
                        fixture.result("feedback-push")));
        assertMove(StageKind.REMOTE_DEVELOPMENT, StageCheckpoint.WAITING_REMOTE_REVIEW,
                StageCheckpoint.READY_TO_MERGE, true,
                fixture -> fixture.remote.acceptReadiness(fixture.result("readiness")));
    }

    @Test
    void exactFencesRejectOrSupersedeStaleSubjects()
    {
        Fixture fixture = new Fixture(
                StageKind.PLAN, StageCheckpoint.SELF_REVIEW, false,
                TaskLifecycle.ACTIVE);
        assertRejected(INVALID_STATE,
                () -> fixture.plan.reviseBeforeApproval(fixture.revision(new StageManager.Command(
                        "wrong-source", "user", "task", 3, "stage", 2, 11))));
        assertRejected(STALE_EPOCH,
                () -> fixture.plan.reviseBeforeApproval(fixture.revision(new StageManager.Command(
                        "epoch", "user", "task", 2, "stage", 2, 11))));
        assertRejected(STALE_GENERATION,
                () -> fixture.plan.reviseBeforeApproval(fixture.revision(new StageManager.Command(
                        "generation", "user", "task", 3, "stage", 1, 11))));
        assertRejected(STALE_VERSION,
                () -> fixture.plan.reviseBeforeApproval(fixture.revision(new StageManager.Command(
                        "version", "user", "task", 3, "stage", 2, 10))));
        assertRejected(WRONG_STAGE_KIND,
                () -> fixture.local.submitLocalFeedback(fixture.localFeedback("kind")));

        Fixture revisionFixture = new Fixture(
                StageKind.PLAN, StageCheckpoint.AWAITING_APPROVAL, false,
                TaskLifecycle.ACTIVE);
        assertRejected(INVALID_STATE, () -> revisionFixture.plan.reviseBeforeApproval(
                new PlanStageManager.RevisionCommand(
                        revisionFixture.command("tampered-revision"),
                        "revision", "previous", "tampered-digest")));
        Fixture localFeedbackFixture = new Fixture(
                StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.LOCAL_REVIEW, false,
                TaskLifecycle.ACTIVE);
        assertRejected(INVALID_STATE, () -> localFeedbackFixture.local.submitLocalFeedback(
                new LocalDevelopmentStageManager.FeedbackCommand(
                        localFeedbackFixture.command("tampered-local-feedback"),
                        "batch", "submission", "tampered-digest")));
        Fixture remoteFeedbackFixture = new Fixture(
                StageKind.REMOTE_DEVELOPMENT, StageCheckpoint.WAITING_REMOTE_REVIEW, false,
                TaskLifecycle.ACTIVE);
        assertRejected(INVALID_STATE, () -> remoteFeedbackFixture.remote.beginRemoteFeedback(
                new RemoteDevelopmentStageManager.FeedbackCommand(
                        remoteFeedbackFixture.command("tampered-remote-feedback"),
                        "batch", "observation", "tampered-digest")));

        Fixture resultFixture = new Fixture(
                StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.IMPLEMENTING, true,
                TaskLifecycle.ACTIVE);
        StageManager.ResultCommand result = resultFixture.result("result");
        assertThat(resultFixture.local.acceptImplementation(result).disposition())
                .isEqualTo(CommandResult.Disposition.APPLIED);
        assertThat(resultFixture.local.acceptImplementation(result).disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertRejected(CommandRejectedException.Reason.COMMAND_ID_CONFLICT,
                () -> resultFixture.local.acceptImplementation(
                        new StageManager.ResultCommand(
                                "result", "dispatcher", "task",
                                new ResultFence(
                                        3, "stage", 2, "other-operation", 1,
                                        "code", "head", "base"))));

        for (ResultFence stale : List.of(
                new ResultFence(2, "stage", 2, "operation", 1, "code", "head", "base"),
                new ResultFence(3, "stage", 1, "operation", 1, "code", "head", "base"),
                new ResultFence(3, "stage", 2, "other", 1, "code", "head", "base"),
                new ResultFence(3, "stage", 2, "operation", 2, "code", "head", "base"),
                new ResultFence(3, "stage", 2, "operation", 1, "other", "head", "base"))) {
            Fixture staleFixture = new Fixture(
                    StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.IMPLEMENTING, true,
                    TaskLifecycle.ACTIVE);
            assertThat(staleFixture.local.acceptImplementation(
                    new StageManager.ResultCommand(
                            "stale-" + stale.hashCode(), "dispatcher", "task", stale))
                    .disposition()).isEqualTo(CommandResult.Disposition.SUPERSEDED);
        }

        assertThatThrownBy(() -> resultFixture.local.acceptImplementation(
                new StageManager.ResultCommand(
                        "result", "dispatcher", "other-task", result.resultFence())))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(NOT_FOUND));
    }

    @Test
    void localWorkArmsReplacesAndClearsOneExactPendingResult()
    {
        Fixture fixture = new Fixture(
                StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.IMPLEMENTING, false,
                TaskLifecycle.ACTIVE);
        ResultFence first = new ResultFence(
                3, "stage", 2, "implementation-one", 1,
                "code", "head", "base");
        StageManager.Command request = fixture.command("request-implementation");

        assertThat(fixture.local.requestImplementation(
                request, first, "turn-request-one").state().pendingResult())
                .isEqualTo(first);
        assertThat(fixture.local.requestImplementation(
                request, first, "turn-request-one").disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);

        ResultFence replacement = new ResultFence(
                3, "stage", 2, "implementation-two", 1,
                "code", "head", "base");
        TaskCommandExecutor commands = CommandTestSupport.executor();
        StageManager.ResultCommand replaceCommand = new StageManager.ResultCommand(
                "replace-implementation", "user", "task", first);
        CommandResult<StageManager.State> replaced = commands.execute("task", () ->
                fixture.local.replaceImplementationTurnInCommand(
                        replaceCommand, replacement, "turn-request-two"));
        assertThat(replaced.state().pendingResult()).isEqualTo(replacement);
        assertThatThrownBy(() -> commands.execute("task", () ->
                fixture.local.replaceImplementationTurnInCommand(
                        replaceCommand,
                        new ResultFence(
                                3, "stage", 2, "conflicting-operation", 1,
                                "code", "head", "base"),
                        "turn-request-two")))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(COMMAND_ID_CONFLICT));

        Fixture sameWork = new Fixture(
                StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.IMPLEMENTING, true,
                TaskLifecycle.ACTIVE);
        assertRejected(INVALID_STATE, () -> commands.execute("task", () ->
                sameWork.local.replaceImplementationTurnInCommand(
                        sameWork.result("same-work"), sameWork.fence,
                        "turn-request-two")));

        CommandResult<StageManager.State> lateOldResult = commands.execute("task", () ->
                fixture.local.clearImplementationTurnInCommand(
                        new StageManager.ResultCommand(
                                "late-old-result", "dispatcher", "task", first),
                        "turn-request-one"));
        assertThat(lateOldResult.disposition())
                .isEqualTo(CommandResult.Disposition.SUPERSEDED);
        assertThat(lateOldResult.state().pendingResult()).isEqualTo(replacement);

        CommandResult<StageManager.State> cleared = commands.execute("task", () ->
                fixture.local.clearImplementationTurnInCommand(
                        new StageManager.ResultCommand(
                                "clear-implementation", "dispatcher", "task",
                                replacement),
                        "turn-request-two"));
        assertThat(cleared.state().pendingResult()).isNull();
        assertThat(cleared.state().checkpoint()).isEqualTo(StageCheckpoint.IMPLEMENTING);
    }

    @Test
    void terminalAndRemoteObservationTransitionsHaveNoStandaloneBypass()
    {
        Set<String> unsafe = Set.of(
                "approve", "approveInCommand",
                "acceptPublished", "acceptPublishedInCommand",
                "acceptCleanupComplete", "acceptCleanupCompleteInCommand",
                "acceptObservedMerged", "acceptObservedMergedInCommand",
                "acceptObservedClosed", "acceptObservedClosedInCommand",
                "acceptQuiescence", "acceptQuiescenceInCommand",
                "sealForReplan", "sealForReplanInCommand",
                "sealForTaskCancellation", "sealForTaskCancellationInCommand");
        assertThat(List.of(
                        PlanStageManager.class,
                        LocalDevelopmentStageManager.class,
                        RemoteDevelopmentStageManager.class,
                        CleanupStageManager.class).stream()
                .flatMap(type -> Arrays.stream(type.getMethods()))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(unsafe::contains))
                .isEmpty();
    }

    @Test
    void publishAndMergeEntryRequireExactPersistedAuthorizations()
    {
        AtomicReference<TaskManager.State> task = new AtomicReference<>(
                new TaskManager.State(
                        "task", "trunk", TaskLifecycle.ACTIVE, 3, 20, "local",
                        null, null, null, null));
        ResultFence publishFence = new ResultFence(
                3, "local", 2, "publish-op", 1, "code", "head", "base");
        CommandTestSupport.Stages localStore = new CommandTestSupport.Stages(
                ignored -> task.get());
        localStore.put(new StageManager.State(
                "local", "task", StageKind.LOCAL_DEVELOPMENT, 2, 11,
                StageCheckpoint.LOCAL_REVIEW, null, null));
        LocalDevelopmentStageManager.PublishAuthorizationEvidence publish =
                new LocalDevelopmentStageManager.PublishAuthorizationEvidence(
                        "task", 3, "local", 2, "publish-auth", "policy",
                        "consent", publishFence);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                CommandTestSupport.executor(), localStore,
                new LocalDevelopmentStageManager.EvidenceStore()
                {
                    @Override
                    public Optional<LocalDevelopmentStageManager.FeedbackEvidence>
                            findLocalFeedback(
                                    String taskId,
                                    String stageId,
                                    long generation,
                                    String batchId)
                    {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<
                            LocalDevelopmentStageManager.PublishAuthorizationEvidence>
                            findPublishAuthorization(
                                    String taskId,
                                    String stageId,
                                    long generation,
                                    String authorizationId)
                    {
                        return Optional.of(publish);
                    }
                });
        LocalDevelopmentStageManager.PublishCommand publishCommand =
                new LocalDevelopmentStageManager.PublishCommand(
                        new StageManager.Command(
                                "publish-auth-command", "user", "task", 3,
                                "local", 2, 11),
                        "publish-auth", "policy", "consent", publishFence);
        assertRejected(INVALID_STATE, () -> local.authorizePublish(
                new LocalDevelopmentStageManager.PublishCommand(
                        new StageManager.Command(
                                "wrong-publish-auth", "user", "task", 3,
                                "local", 2, 11),
                        "publish-auth", "other-policy", "consent", publishFence)));
        assertThat(local.authorizePublish(publishCommand).state())
                .extracting(StageManager.State::checkpoint, StageManager.State::pendingResult)
                .containsExactly(StageCheckpoint.PUBLISHING, publishFence);
        assertThat(local.authorizePublish(publishCommand).disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);

        ResultFence mergeFence = new ResultFence(
                3, "remote", 1, "merge-op", 1, "code", "remote-head", "base");
        task.set(new TaskManager.State(
                "task", "trunk", TaskLifecycle.ACTIVE, 3, 21, "remote",
                null, null, null, null));
        CommandTestSupport.Stages remoteStore = new CommandTestSupport.Stages(
                ignored -> task.get());
        remoteStore.put(new StageManager.State(
                "remote", "task", StageKind.REMOTE_DEVELOPMENT, 1, 8,
                StageCheckpoint.READY_TO_MERGE, null, null));
        RemoteDevelopmentStageManager.MergeAuthorizationEvidence merge =
                new RemoteDevelopmentStageManager.MergeAuthorizationEvidence(
                        "task", 3, "remote", 1, "merge-auth", "readiness",
                        4, "policy", "consent", mergeFence);
        RemoteDevelopmentStageManager remote = new RemoteDevelopmentStageManager(
                CommandTestSupport.executor(), remoteStore,
                remoteEvidence(
                        Optional.of(merge), Optional.empty(),
                        Optional.of(new RemoteDevelopmentStageManager.RemoteSubjectEvidence(
                                "task", 3, "remote", 1, 4,
                                "remote-head", "base"))));
        RemoteDevelopmentStageManager.MergeAuthorizationCommand mergeCommand =
                new RemoteDevelopmentStageManager.MergeAuthorizationCommand(
                        new StageManager.Command(
                                "merge-auth-command", "user", "task", 3,
                                "remote", 1, 8),
                        "merge-auth", "readiness", 4,
                        "policy", "consent", mergeFence);
        assertRejected(INVALID_STATE, () -> remote.authorizeMerge(
                new RemoteDevelopmentStageManager.MergeAuthorizationCommand(
                        new StageManager.Command(
                                "wrong-merge-auth", "user", "task", 3,
                                "remote", 1, 8),
                        "merge-auth", "other-readiness", 4,
                        "policy", "consent", mergeFence)));
        assertThat(remote.authorizeMerge(mergeCommand).state())
                .extracting(StageManager.State::checkpoint, StageManager.State::pendingResult)
                .containsExactly(StageCheckpoint.MERGING, mergeFence);
        assertThat(remote.authorizeMerge(mergeCommand).disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertRejected(COMMAND_ID_CONFLICT, () -> remote.authorizeMerge(
                new RemoteDevelopmentStageManager.MergeAuthorizationCommand(
                        mergeCommand.stage(), "merge-auth", "readiness", 5,
                        "policy", "consent", mergeFence)));
    }

    @Test
    void mergeAuthorizationRejectsOldHeadAndStaleRemoteObservationRevision()
    {
        AtomicReference<TaskManager.State> task = new AtomicReference<>(
                new TaskManager.State(
                        "task", "trunk", TaskLifecycle.ACTIVE, 3, 21, "remote",
                        null, null, null, null));
        CommandTestSupport.Stages store = new CommandTestSupport.Stages(ignored -> task.get());
        store.put(new StageManager.State(
                "remote", "task", StageKind.REMOTE_DEVELOPMENT, 1, 8,
                StageCheckpoint.READY_TO_MERGE, null, null));
        ResultFence oldFence = new ResultFence(
                3, "remote", 1, "merge-old", 1,
                "code", "old-head", "base");
        RemoteDevelopmentStageManager.MergeAuthorizationEvidence authorization =
                new RemoteDevelopmentStageManager.MergeAuthorizationEvidence(
                        "task", 3, "remote", 1, "merge-auth", "readiness",
                        4, "policy", "consent", oldFence);
        RemoteDevelopmentStageManager.MergeAuthorizationCommand command =
                new RemoteDevelopmentStageManager.MergeAuthorizationCommand(
                        new StageManager.Command(
                                "merge-old-command", "user", "task", 3,
                                "remote", 1, 8),
                        "merge-auth", "readiness", 4,
                        "policy", "consent", oldFence);

        for (RemoteDevelopmentStageManager.RemoteSubjectEvidence current : List.of(
                new RemoteDevelopmentStageManager.RemoteSubjectEvidence(
                        "task", 3, "remote", 1, 4, "new-head", "base"),
                new RemoteDevelopmentStageManager.RemoteSubjectEvidence(
                        "task", 3, "remote", 1, 5, "old-head", "base"))) {
            RemoteDevelopmentStageManager remote = new RemoteDevelopmentStageManager(
                    CommandTestSupport.executor(), store,
                    remoteEvidence(
                            Optional.of(authorization), Optional.empty(), Optional.of(current)));
            assertRejected(INVALID_STATE, () -> remote.authorizeMerge(command));
        }
    }

    @Test
    void acceptedRemoteFactsAdvanceTheOwnerAndNewHeadClearsArmedMerge()
    {
        AtomicReference<TaskManager.State> task = new AtomicReference<>(
                new TaskManager.State(
                        "task", "trunk", TaskLifecycle.ACTIVE, 3, 21, "remote",
                        null, null, null, null));
        CommandTestSupport.Stages store = new CommandTestSupport.Stages(
                ignored -> task.get());
        store.put(new StageManager.State(
                "remote", "task", StageKind.REMOTE_DEVELOPMENT, 1, 8,
                StageCheckpoint.WAITING_CI, null, null));
        RemoteDevelopmentStageManager.RemoteGateEvidence ci =
                new RemoteDevelopmentStageManager.RemoteGateEvidence(
                        "task", 3, "remote", 1, "ci-1", "head-1", "base-1");
        RemoteDevelopmentStageManager.RemoteGateEvidence ready =
                new RemoteDevelopmentStageManager.RemoteGateEvidence(
                        "task", 3, "remote", 1, "snapshot-1",
                        "head-1", "base-1");
        RemoteDevelopmentStageManager initialRemote =
                new RemoteDevelopmentStageManager(
                CommandTestSupport.executor(), store,
                remoteGateEvidence(ci, ready, Optional.empty()));
        TaskCommandExecutor commands = CommandTestSupport.executor();

        CommandResult<StageManager.State> ciAccepted = commands.execute(
                "task", () -> initialRemote.acceptCiEvidenceInCommand(
                        gate("accept-ci", 8, ci)));
        assertThat(ciAccepted.state().checkpoint())
                .isEqualTo(StageCheckpoint.AWAITING_READY);
        CommandResult<StageManager.State> openAccepted = commands.execute(
                "task", () -> initialRemote.acceptObservedReadyInCommand(
                        gate("accept-open", 9, ready)));
        assertThat(openAccepted.state().checkpoint())
                .isEqualTo(StageCheckpoint.WAITING_REMOTE_REVIEW);

        ResultFence merge = new ResultFence(
                3, "remote", 1, "merge-operation", 1,
                null, "head-1", "base-1");
        store.put(new StageManager.State(
                "remote", "task", StageKind.REMOTE_DEVELOPMENT, 1, 20,
                StageCheckpoint.MERGING, null, merge));
        RemoteDevelopmentStageManager.RemoteGateEvidence changed =
                new RemoteDevelopmentStageManager.RemoteGateEvidence(
                        "task", 3, "remote", 1, "snapshot-2",
                        "head-2", "base-2");
        RemoteDevelopmentStageManager changedRemote =
                new RemoteDevelopmentStageManager(
                CommandTestSupport.executor(), store,
                remoteGateEvidence(
                        Optional.empty(), Optional.empty(), Optional.of(changed)));

        CommandResult<StageManager.State> reset = commands.execute(
                "task", () -> changedRemote.acceptHeadChangeInCommand(
                        gate("accept-head-change", 20, changed),
                        StageCheckpoint.MERGING));
        assertThat(reset.state())
                .extracting(StageManager.State::checkpoint,
                        StageManager.State::pendingResult)
                .containsExactly(StageCheckpoint.WAITING_CI, null);
    }

    @Test
    void multipleSupersededReceiptsAtOneVersionReplayTheirImmutableState()
    {
        Fixture fixture = new Fixture(
                StageKind.LOCAL_DEVELOPMENT, StageCheckpoint.IMPLEMENTING, true,
                TaskLifecycle.ACTIVE);
        ResultFence staleOne = new ResultFence(
                3, "stage", 2, "stale-one", 1, "code", "head", "base");
        ResultFence staleTwo = new ResultFence(
                3, "stage", 2, "stale-two", 1, "code", "head", "base");
        StageManager.ResultCommand first = new StageManager.ResultCommand(
                "stale-one-command", "dispatcher", "task", staleOne);
        StageManager.ResultCommand second = new StageManager.ResultCommand(
                "stale-two-command", "dispatcher", "task", staleTwo);

        assertThat(fixture.local.acceptImplementation(first).disposition())
                .isEqualTo(CommandResult.Disposition.SUPERSEDED);
        assertThat(fixture.local.acceptImplementation(second).disposition())
                .isEqualTo(CommandResult.Disposition.SUPERSEDED);
        assertThat(fixture.local.acceptImplementation(fixture.result("valid")).disposition())
                .isEqualTo(CommandResult.Disposition.APPLIED);

        CommandResult<StageManager.State> replay = fixture.local.acceptImplementation(first);
        assertThat(replay.disposition()).isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(replay.state())
                .extracting(StageManager.State::checkpoint, StageManager.State::version)
                .containsExactly(StageCheckpoint.IMPLEMENTING, 11L);
    }

    private static void assertMove(
            StageKind kind,
            StageCheckpoint source,
            StageCheckpoint target,
            boolean resultCommand,
            Function<Fixture, CommandResult<StageManager.State>> command)
    {
        Fixture fixture = new Fixture(
                kind, source, resultCommand, TaskLifecycle.ACTIVE);
        CommandResult<StageManager.State> result = command.apply(fixture);
        assertThat(result.disposition()).isEqualTo(CommandResult.Disposition.APPLIED);
        assertThat(result.state().checkpoint()).isEqualTo(target);
        assertThat(result.state().version()).isEqualTo(12);
    }

    private static void assertRejected(
            CommandRejectedException.Reason reason, Runnable command)
    {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static TaskManager.State task(TaskLifecycle lifecycle)
    {
        return new TaskManager.State(
                "task", "trunk", lifecycle, 3, 20, "stage",
                null, null, null, null);
    }

    private static RemoteDevelopmentStageManager.EvidenceStore remoteEvidence(
            Optional<RemoteDevelopmentStageManager.MergeAuthorizationEvidence> merge,
            Optional<RemoteDevelopmentStageManager.TerminalObservationEvidence> terminal,
            Optional<RemoteDevelopmentStageManager.RemoteSubjectEvidence> subject)
    {
        return new RemoteDevelopmentStageManager.EvidenceStore()
        {
            @Override
            public Optional<RemoteDevelopmentStageManager.FeedbackEvidence>
                    findRemoteFeedback(
                            String taskId, String stageId, long generation, String batchId)
            {
                return Optional.empty();
            }

            @Override
            public Optional<RemoteDevelopmentStageManager.MergeAuthorizationEvidence>
                    findMergeAuthorization(
                            String taskId,
                            String stageId,
                            long generation,
                            String authorizationId)
            {
                return merge;
            }

            @Override
            public Optional<RemoteDevelopmentStageManager.TerminalObservationEvidence>
                    findTerminalObservation(
                            String taskId,
                            String stageId,
                            long generation,
                            String observationId)
            {
                return terminal;
            }

            @Override
            public Optional<RemoteDevelopmentStageManager.RemoteSubjectEvidence>
                    findCurrentRemoteSubject(
                            String taskId, String stageId, long generation)
            {
                return subject;
            }
        };
    }

    private static RemoteDevelopmentStageManager.EvidenceStore remoteGateEvidence(
            RemoteDevelopmentStageManager.RemoteGateEvidence ci,
            RemoteDevelopmentStageManager.RemoteGateEvidence ready,
            Optional<RemoteDevelopmentStageManager.RemoteGateEvidence> changed)
    {
        return remoteGateEvidence(Optional.of(ci), Optional.of(ready), changed);
    }

    private static RemoteDevelopmentStageManager.EvidenceStore remoteGateEvidence(
            Optional<RemoteDevelopmentStageManager.RemoteGateEvidence> ci,
            Optional<RemoteDevelopmentStageManager.RemoteGateEvidence> ready,
            Optional<RemoteDevelopmentStageManager.RemoteGateEvidence> changed)
    {
        return new RemoteDevelopmentStageManager.EvidenceStore()
        {
            @Override
            public Optional<RemoteDevelopmentStageManager.FeedbackEvidence>
                    findRemoteFeedback(
                            String taskId,
                            String stageId,
                            long stageGeneration,
                            String batchId)
            {
                return Optional.empty();
            }

            @Override
            public Optional<RemoteDevelopmentStageManager.RemoteGateEvidence>
                    findAcceptedCi(
                            String taskId,
                            String stageId,
                            long stageGeneration,
                            String evidenceId)
            {
                return ci.filter(
                        evidence -> evidence.proofId().equals(evidenceId));
            }

            @Override
            public Optional<RemoteDevelopmentStageManager.RemoteGateEvidence>
                    findObservedReady(
                            String taskId,
                            String stageId,
                            long stageGeneration,
                            String snapshotId)
            {
                return ready.filter(
                        evidence -> evidence.proofId().equals(snapshotId));
            }

            @Override
            public Optional<RemoteDevelopmentStageManager.RemoteGateEvidence>
                    findHeadChange(
                            String taskId,
                            String stageId,
                            long stageGeneration,
                            String snapshotId)
            {
                return changed.filter(
                        evidence -> evidence.proofId().equals(snapshotId));
            }

            @Override
            public Optional<
                    RemoteDevelopmentStageManager.MergeAuthorizationEvidence>
                    findMergeAuthorization(
                            String taskId,
                            String stageId,
                            long stageGeneration,
                            String authorizationId)
            {
                return Optional.empty();
            }

            @Override
            public Optional<
                    RemoteDevelopmentStageManager.TerminalObservationEvidence>
                    findTerminalObservation(
                            String taskId,
                            String stageId,
                            long stageGeneration,
                            String observationId)
            {
                return Optional.empty();
            }

            @Override
            public Optional<RemoteDevelopmentStageManager.RemoteSubjectEvidence>
                    findCurrentRemoteSubject(
                            String taskId, String stageId, long stageGeneration)
            {
                return Optional.empty();
            }
        };
    }

    private static RemoteDevelopmentStageManager.RemoteGateCommand gate(
            String commandId,
            long stageVersion,
            RemoteDevelopmentStageManager.RemoteGateEvidence evidence)
    {
        return new RemoteDevelopmentStageManager.RemoteGateCommand(
                new StageManager.Command(
                        commandId, "remote-observer", evidence.taskId(),
                        evidence.taskEpoch(), evidence.stageId(),
                        evidence.stageGeneration(), stageVersion),
                evidence.proofId(), evidence.headSha(), evidence.baseSha());
    }

    private static final class Fixture
    {
        private final AtomicReference<TaskManager.State> task;
        private final CommandTestSupport.Stages store;
        private final ResultFence fence;
        private final PlanStageManager plan;
        private final LocalDevelopmentStageManager local;
        private final RemoteDevelopmentStageManager remote;

        private Fixture(
                StageKind kind,
                StageCheckpoint checkpoint,
                boolean pendingResult,
                TaskLifecycle taskLifecycle)
        {
            task = new AtomicReference<>(task(taskLifecycle));
            store = new CommandTestSupport.Stages(taskId -> task.get());
            fence = new ResultFence(
                    3, "stage", 2, "operation", 1, "code", "head", "base");
            store.put(new StageManager.State(
                    "stage", "task", kind, 2, 11, checkpoint, null,
                    pendingResult ? fence : null));
            TaskCommandExecutor executor = CommandTestSupport.executor();
            plan = new PlanStageManager(
                    executor,
                    store,
                    (taskId, stageId, generation, approvalId) -> Optional.empty(),
                    (taskId, stageId, generation, revisionId) -> Optional.of(
                            new PlanStageManager.RevisionEvidence(
                                    taskId, stageId, generation, revisionId,
                                    "previous", "revision-digest")));
            local = new LocalDevelopmentStageManager(
                    executor, store, new LocalDevelopmentStageManager.EvidenceStore()
                    {
                        @Override
                        public Optional<LocalDevelopmentStageManager.FeedbackEvidence>
                                findLocalFeedback(
                                        String taskId,
                                        String stageId,
                                        long generation,
                                        String batchId)
                        {
                            return Optional.of(
                                    new LocalDevelopmentStageManager.FeedbackEvidence(
                                            taskId, stageId, generation, batchId,
                                            "submission", "feedback-digest"));
                        }

                        @Override
                        public Optional<
                                LocalDevelopmentStageManager.PublishAuthorizationEvidence>
                                findPublishAuthorization(
                                        String taskId,
                                        String stageId,
                                        long generation,
                                        String authorizationId)
                        {
                            return Optional.empty();
                        }

                        @Override
                        public Optional<LocalDevelopmentStageManager.ReplacementEvidence>
                                findReplacement(
                                        String taskId,
                                        String stageId,
                                        long generation,
                                        String requestId)
                        {
                            if (!requestId.equals("turn-request-two")) {
                                return Optional.empty();
                            }
                            return Optional.of(
                                    new LocalDevelopmentStageManager.ReplacementEvidence(
                                            taskId, stageId, generation, requestId,
                                            new ResultFence(
                                                    3, "stage", 2,
                                                    "implementation-one", 1,
                                                    "code", "head", "base"),
                                            new ResultFence(
                                                    3, "stage", 2,
                                                    "implementation-two", 1,
                                                    "code", "head", "base")));
                        }
                    });
            remote = new RemoteDevelopmentStageManager(
                    executor, store, new RemoteDevelopmentStageManager.EvidenceStore()
                    {
                        @Override
                        public Optional<RemoteDevelopmentStageManager.FeedbackEvidence>
                                findRemoteFeedback(
                                        String taskId,
                                        String stageId,
                                        long generation,
                                        String batchId)
                        {
                            return Optional.of(
                                    new RemoteDevelopmentStageManager.FeedbackEvidence(
                                            taskId, stageId, generation, batchId,
                                            "observation", "remote-digest"));
                        }

                        @Override
                        public Optional<
                                RemoteDevelopmentStageManager.MergeAuthorizationEvidence>
                                findMergeAuthorization(
                                        String taskId,
                                        String stageId,
                                        long generation,
                                        String authorizationId)
                        {
                            return Optional.empty();
                        }

                        @Override
                        public Optional<
                                RemoteDevelopmentStageManager.TerminalObservationEvidence>
                                findTerminalObservation(
                                        String taskId,
                                        String stageId,
                                        long generation,
                                        String observationId)
                        {
                            return Optional.empty();
                        }

                        @Override
                        public Optional<RemoteDevelopmentStageManager.RemoteSubjectEvidence>
                                findCurrentRemoteSubject(
                                        String taskId, String stageId, long generation)
                        {
                            return Optional.empty();
                        }
                    });
        }

        private StageManager.Command command(String commandId)
        {
            return new StageManager.Command(
                    commandId, "user", "task", 3, "stage", 2, 11);
        }

        private PlanStageManager.RevisionCommand revision(String commandId)
        {
            return revision(command(commandId));
        }

        private PlanStageManager.RevisionCommand revision(StageManager.Command command)
        {
            return new PlanStageManager.RevisionCommand(
                    command, "revision", "previous", "revision-digest");
        }

        private LocalDevelopmentStageManager.FeedbackCommand localFeedback(String commandId)
        {
            return new LocalDevelopmentStageManager.FeedbackCommand(
                    command(commandId), "batch", "submission", "feedback-digest");
        }

        private RemoteDevelopmentStageManager.FeedbackCommand remoteFeedback(String commandId)
        {
            return new RemoteDevelopmentStageManager.FeedbackCommand(
                    command(commandId), "batch", "observation", "remote-digest");
        }

        private StageManager.ResultCommand result(String commandId)
        {
            return new StageManager.ResultCommand(commandId, "dispatcher", "task", fence);
        }
    }
}
