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
package com.bytequay.app.config;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.ResultDeliveryRouter;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.execution.agentturn.ReviewAssignmentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.ThreadTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupEffects;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore;
import com.bytequay.app.developmentflow.execution.merge.GitHubMergeEffects;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler;
import com.bytequay.app.developmentflow.execution.merge.SqliteMergeOperationStore;
import com.bytequay.app.developmentflow.execution.provisioning.GitRunnerProvisioningGit;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler;
import com.bytequay.app.developmentflow.execution.provisioning.SqliteProvisionTaskOperationStore;
import com.bytequay.app.developmentflow.execution.publish.GitHubPublishEffects;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.SqlitePublishOperationStore;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler;
import com.bytequay.app.developmentflow.execution.quality.SqliteQualityIssuePublishStore;
import com.bytequay.app.developmentflow.execution.quality.V2QualityIssuePublishRuntime;
import com.bytequay.app.developmentflow.execution.remote.GitHubRemoteEffects;
import com.bytequay.app.developmentflow.execution.remote.GitHubRemoteObserver;
import com.bytequay.app.developmentflow.execution.remote.GitHubReviewBuildCommentGateway;
import com.bytequay.app.developmentflow.execution.remote.GitHubUserRemoteActionGateway;
import com.bytequay.app.developmentflow.execution.remote.RemoteFeedbackEffectOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.RemoteMarkReadyOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.ReviewBuildCommentOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.ReviewPublicationOperationStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteExternalPrActionStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteRemoteFeedbackEffectOperationStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteRemoteMarkReadyOperationStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteReviewBuildCommentStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteReviewPassPublicationStore;
import com.bytequay.app.developmentflow.execution.remote.SqliteUserRemoteActionStore;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.V2UserRemoteActionRuntime;
import com.bytequay.app.developmentflow.persistence.SqliteAgentTurnOperationStore;
import com.bytequay.app.developmentflow.persistence.SqliteReviewAssignmentTurnStore;
import com.bytequay.app.developmentflow.persistence.SqliteThreadTurnOperationStore;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.CleanupCompletionHandoff;
import com.bytequay.app.developmentflow.stage.CleanupQuiescenceHandoff;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.LocalToRemoteHandoff;
import com.bytequay.app.developmentflow.stage.LocalValidationOperationHandler;
import com.bytequay.app.developmentflow.stage.ManualPrValidationOperationHandler;
import com.bytequay.app.developmentflow.stage.ManualPrValidationResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackValidationOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteRepairTurnRuntime;
import com.bytequay.app.developmentflow.stage.V2ReadinessAssistanceRuntime;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePublishResultStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.task.TaskBrainConversationRuntime;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskPolicyRevisionRedriver;
import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.developmentflow.task.creation.V2TaskCreationService;
import com.bytequay.app.developmentflow.trunk.PlanningBaseRefreshOperationHandler;
import com.bytequay.app.developmentflow.trunk.PlanningBaseTurnRuntime;
import com.bytequay.app.developmentflow.trunk.SqlitePlanningBaseTurnStore;
import com.bytequay.app.developmentflow.trunk.SqliteTaskOutcomeSummaryStore;
import com.bytequay.app.developmentflow.trunk.TaskOutcomeSummaryResultDeliveryPort;
import com.bytequay.app.developmentflow.trunk.TaskOutcomeSummaryRuntime;
import com.bytequay.app.developmentflow.trunk.ThreadTurnHandoff;
import com.bytequay.app.developmentflow.trunk.ThreadTurnProjection;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.developmentflow.trunk.V2ThreadControlService;
import com.bytequay.app.developmentflow.trunk.V2TrunkPurge;
import com.bytequay.app.developmentflow.userwait.V2UserWaitResultDeliveryPort;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadSettingsStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ToolExposurePolicy;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.checks.ValidationCheck;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.bytequay.app.service.review.ReviewProviderEndpoints;
import com.bytequay.app.service.review.ReviewSessionSnapshotOperationHandler;
import com.bytequay.app.service.review.ReviewSessionSnapshotResultDeliveryPort;
import com.bytequay.app.service.review.TaskReviewRoundSnapshotOperationHandler;
import com.bytequay.app.service.review.TaskReviewRoundSnapshotResultDeliveryPort;
import com.bytequay.app.service.review.TaskReviewSnapshotOperationHandler;
import com.bytequay.app.service.review.TaskReviewSnapshotResultDeliveryPort;
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.WorktreeService;
import com.bytequay.app.service.workmodel.ReasoningEffortService;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDevelopmentFlowExecutionConfig
{
    @Test
    void enablingDeclaresTheCompleteExecutionGraphAndRequiredRemoteGateways()
    {
        Method dispatcher = Arrays.stream(
                        DevelopmentFlowExecutionConfig.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == ExecutionDispatcher.class)
                .findFirst()
                .orElseThrow();
        assertThat(dispatcher.getParameterTypes()).contains(
                ExecutionPorts.OperationHandlerRegistry.class,
                ExecutionPorts.ResultDeliveryPort.class,
                ExecutionPorts.ExecutionEvidencePort.class,
                ExecutionPorts.DispatchTicketStore.class,
                ExecutionPorts.DispatchWakeStore.class);
        Set<Class<?>> returnTypes = Arrays.stream(
                        DevelopmentFlowExecutionConfig.class.getDeclaredMethods())
                .map(Method::getReturnType)
                .collect(Collectors.toSet());
        assertThat(returnTypes).contains(
                ExecutionPorts.ResultDeliveryPort.class,
                ExecutionPorts.OperationHandlerRegistry.class,
                AgentTurnProviderSession.class,
                ProvisionTaskOperationHandler.class,
                AgentTurnOperationHandler.class,
                ThreadTurnOperationHandler.class,
                ReviewAssignmentTurnOperationHandler.class,
                LocalValidationOperationHandler.class,
                PublishOperationHandler.class,
                CleanupOperationHandler.class,
                RemoteFeedbackValidationOperationHandler.class,
                RemoteFeedbackEffectOperationHandler.class,
                UserRemoteActionOperationHandler.class,
                ReviewBuildCommentOperationHandler.class,
                RemoteMarkReadyOperationHandler.class,
                RemoteObservationOperationHandler.class,
                RemoteEffectOperationHandler.class,
                MergeOperationHandler.class);

        Method remoteEffects = methodReturning(
                RemoteFeedbackEffectOperationHandler.class);
        Method markReady = methodReturning(RemoteMarkReadyOperationHandler.class);
        assertThat(remoteEffects.getParameterTypes()).contains(
                RemoteFeedbackEffectOperationHandler.EffectGateway.class);
        assertThat(markReady.getParameterTypes()).contains(
                RemoteMarkReadyOperationHandler.MarkReadyGateway.class);
    }

    @Test
    void registryMapsEveryCurrentOperationKindAndRejectsUnknown()
    {
        DevelopmentFlowExecutionConfig config = new DevelopmentFlowExecutionConfig();
        ProvisionTaskOperationHandler provisioning = mock(
                ProvisionTaskOperationHandler.class);
        AgentTurnOperationHandler turns = mock(AgentTurnOperationHandler.class);
        ThreadTurnOperationHandler threadTurns = mock(
                ThreadTurnOperationHandler.class);
        PlanningBaseRefreshOperationHandler planningBase = mock(
                PlanningBaseRefreshOperationHandler.class);
        ReviewAssignmentTurnOperationHandler reviewTurns = mock(
                ReviewAssignmentTurnOperationHandler.class);
        LocalValidationOperationHandler localValidation = mock(
                LocalValidationOperationHandler.class);
        ManualPrValidationOperationHandler manualValidation = mock(
                ManualPrValidationOperationHandler.class);
        TaskReviewSnapshotOperationHandler taskReviewSnapshots = mock(
                TaskReviewSnapshotOperationHandler.class);
        TaskReviewRoundSnapshotOperationHandler taskReviewRoundSnapshots = mock(
                TaskReviewRoundSnapshotOperationHandler.class);
        ReviewSessionSnapshotOperationHandler reviewSessionSnapshots = mock(
                ReviewSessionSnapshotOperationHandler.class);
        PublishOperationHandler publish = mock(PublishOperationHandler.class);
        CleanupOperationHandler cleanup = mock(CleanupOperationHandler.class);
        RemoteFeedbackValidationOperationHandler remoteValidation = mock(
                RemoteFeedbackValidationOperationHandler.class);
        RemoteFeedbackEffectOperationHandler remoteEffects = mock(
                RemoteFeedbackEffectOperationHandler.class);
        UserRemoteActionOperationHandler userRemoteActions = mock(
                UserRemoteActionOperationHandler.class);
        ReviewBuildCommentOperationHandler reviewBuildComments = mock(
                ReviewBuildCommentOperationHandler.class);
        QualityIssuePublishOperationHandler qualityIssuePublishes = mock(
                QualityIssuePublishOperationHandler.class);
        RemoteMarkReadyOperationHandler markReady = mock(
                RemoteMarkReadyOperationHandler.class);
        RemoteObservationOperationHandler observations = mock(
                RemoteObservationOperationHandler.class);
        RemoteEffectOperationHandler finiteEffects = mock(
                RemoteEffectOperationHandler.class);
        MergeOperationHandler merge = mock(MergeOperationHandler.class);

        ExecutionPorts.OperationHandlerRegistry registry = config.v2OperationHandlers(
                provisioning, turns, threadTurns, planningBase, reviewTurns,
                localValidation, manualValidation, taskReviewSnapshots,
                taskReviewRoundSnapshots, reviewSessionSnapshots,
                publish, cleanup, remoteValidation,
                remoteEffects, userRemoteActions, reviewBuildComments,
                qualityIssuePublishes,
                markReady, observations,
                finiteEffects, merge);

        assertThat(registry.require(ProvisionTaskOperationHandler.OPERATION_KIND))
                .isSameAs(provisioning);
        assertThat(registry.require(AgentTurnOperationHandler.TASK_OPERATION_KIND))
                .isSameAs(turns);
        assertThat(registry.require(AgentTurnOperationHandler.STAGE_OPERATION_KIND))
                .isSameAs(turns);
        assertThat(registry.require(
                AgentTurnOperationHandler.TASK_OUTCOME_SUMMARY_OPERATION_KIND))
                .isSameAs(turns);
        assertThat(registry.require(ThreadTurnOperationHandler.OPERATION_KIND))
                .isSameAs(threadTurns);
        assertThat(registry.require(
                TaskReviewSnapshotOperationHandler.OPERATION_KIND))
                .isSameAs(taskReviewSnapshots);
        assertThat(registry.require(
                TaskReviewRoundSnapshotOperationHandler.OPERATION_KIND))
                .isSameAs(taskReviewRoundSnapshots);
        assertThat(registry.require(
                ReviewSessionSnapshotOperationHandler.OPERATION_KIND))
                .isSameAs(reviewSessionSnapshots);
        assertThat(registry.require(
                PlanningBaseRefreshOperationHandler.OPERATION_KIND))
                .isSameAs(planningBase);
        assertThat(registry.require(ReviewAssignmentTurnOperationHandler.OPERATION_KIND))
                .isSameAs(reviewTurns);
        assertThat(registry.require(LocalValidationOperationHandler.OPERATION_KIND))
                .isSameAs(localValidation);
        assertThat(registry.require(
                ManualPrValidationOperationHandler.OPERATION_KIND))
                .isSameAs(manualValidation);
        assertThat(registry.require(PublishOperationHandler.OPERATION_KIND))
                .isSameAs(publish);
        assertThat(registry.require(CleanupOperationHandler.OPERATION_KIND))
                .isSameAs(cleanup);
        assertThat(registry.require(
                RemoteFeedbackValidationOperationHandler.OPERATION_KIND))
                .isSameAs(remoteValidation);
        assertThat(registry.require(RemoteFeedbackEffectOperationHandler.OPERATION_KIND))
                .isSameAs(remoteEffects);
        assertThat(registry.require(RemoteFeedbackEffectOperationHandler
                .READINESS_ASSISTANCE_OPERATION_KIND)).isSameAs(remoteEffects);
        assertThat(registry.require(UserRemoteActionOperationHandler.OPERATION_KIND))
                .isSameAs(userRemoteActions);
        assertThat(registry.require(
                ReviewBuildCommentOperationHandler.OPERATION_KIND))
                .isSameAs(reviewBuildComments);
        assertThat(registry.require(
                ReviewBuildCommentOperationHandler.REVIEW_PASS_OPERATION_KIND))
                .isSameAs(reviewBuildComments);
        assertThat(registry.require(
                QualityIssuePublishOperationHandler.OPERATION_KIND))
                .isSameAs(qualityIssuePublishes);
        assertThat(registry.require(RemoteMarkReadyOperationHandler.OPERATION_KIND))
                .isSameAs(markReady);
        assertThat(registry.require(RemoteObservationOperationHandler.OPERATION_KIND))
                .isSameAs(observations);
        assertThat(registry.require(RemoteEffectOperationHandler.RERUN_CI))
                .isSameAs(finiteEffects);
        assertThat(registry.require(RemoteEffectOperationHandler.PUSH_BRANCH))
                .isSameAs(finiteEffects);
        assertThat(registry.require(MergeOperationHandler.OPERATION_KIND))
                .isSameAs(merge);
        assertThatThrownBy(() -> registry.require("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported V2 operation kind");
    }

    @Test
    void resultRouterIncludesEveryCurrentCallbackAndRecoversCleanup()
            throws Exception
    {
        DevelopmentFlowExecutionConfig config = new DevelopmentFlowExecutionConfig();
        SqliteCleanupOperationStore cleanupStore = mock(
                SqliteCleanupOperationStore.class);
        SqliteReviewAssignmentTurnStore reviewTurns = mock(
                SqliteReviewAssignmentTurnStore.class);
        when(cleanupStore.findPendingFinalizations(7)).thenReturn(List.of());

        ExecutionPorts.ResultDeliveryPort delivery = config.v2ResultDelivery(
                mock(PlanningBaseTurnRuntime.class),
                mock(PlanRuntimeCoordinator.class),
                mock(LocalDevelopmentRuntimeCoordinator.class),
                mock(RemoteFeedbackRuntimeCoordinator.class),
                mock(RemoteDevelopmentRuntimeCoordinator.class),
                mock(TaskCommandExecutor.class),
                mock(LocalDevelopmentStageManager.class),
                mock(LocalToRemoteHandoff.class),
                mock(RemoteObservationRuntimeCoordinator.class),
                mock(PRService.class),
                mock(TaskStore.class),
                mock(SqlitePublishResultStore.class),
                cleanupStore,
                mock(CleanupCompletionHandoff.class),
                mock(TrunkManager.class),
                mock(RemoteCiRepairRuntimeCoordinator.class),
                mock(BranchSyncRuntimeCoordinator.class),
                mock(RemoteRepairTurnRuntime.class),
                mock(RemoteDevelopmentStageManager.class),
                mock(SqliteMergeOperationStore.class),
                reviewTurns,
                mock(SqliteTaskOutcomeSummaryStore.class),
                mock(V2UserWaitStore.class),
                mock(TaskOutcomeSummaryRuntime.class),
                mock(TaskBrainConversationRuntime.class),
                mock(V2UserRemoteActionRuntime.class),
                mock(V2QualityIssuePublishRuntime.class),
                mock(V2ReadinessAssistanceRuntime.class),
                mock(ManualPrValidationResultDeliveryPort.class),
                mock(TaskReviewSnapshotResultDeliveryPort.class),
                mock(TaskReviewRoundSnapshotResultDeliveryPort.class),
                mock(ReviewSessionSnapshotResultDeliveryPort.class),
                mock(ObjectProvider.class),
                mock(JdbcTemplate.class),
                new ObjectMapper());

        ExecutionPorts.ResultDeliveryPort router =
                (ExecutionPorts.ResultDeliveryPort)
                        ReflectionTestUtils.getField(delivery, "delegate");
        assertThat(router).isInstanceOf(ResultDeliveryRouter.class);
        @SuppressWarnings("unchecked")
        Map<String, ExecutionPorts.ResultDeliveryPort> routes =
                (Map<String, ExecutionPorts.ResultDeliveryPort>)
                        ReflectionTestUtils.getField(router, "routes");
        assertThat(routes).containsKey(
                TaskReviewSnapshotOperationHandler.CALLBACK_ROUTE);
        assertThat(routes).isNotNull();
        assertThat(routes.keySet()).containsExactlyInAnyOrder(
                PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE,
                PlanRuntimeCoordinator.PROVISION_CALLBACK,
                PlanRuntimeCoordinator.TURN_CALLBACK,
                LocalDevelopmentRuntimeCoordinator.TURN_CALLBACK,
                LocalValidationOperationHandler.CALLBACK_ROUTE,
                ManualPrValidationOperationHandler.CALLBACK_ROUTE,
                TaskReviewSnapshotOperationHandler.CALLBACK_ROUTE,
                TaskReviewRoundSnapshotOperationHandler.CALLBACK_ROUTE,
                ReviewSessionSnapshotOperationHandler.CALLBACK_ROUTE,
                PublishOperationHandler.CALLBACK_ROUTE,
                CleanupOperationHandler.CALLBACK_ROUTE,
                RemoteFeedbackRuntimeCoordinator.TURN_CALLBACK,
                RemoteFeedbackRuntimeCoordinator.VALIDATION_CALLBACK,
                RemoteFeedbackRuntimeCoordinator.BRAIN_CALLBACK,
                RemoteDevelopmentRuntimeCoordinator.EFFECT_CALLBACK,
                RemoteDevelopmentRuntimeCoordinator.MARK_READY_CALLBACK,
                ThreadTurnOperationHandler.CALLBACK_ROUTE,
                TaskOutcomeSummaryResultDeliveryPort.CALLBACK_ROUTE,
                RemoteObservationOperationHandler.CALLBACK_ROUTE,
                "REMOTE_CI_RERUN_RESULT",
                "REMOTE_CI_VALIDATION_RESULT",
                "REMOTE_CI_PUSH_RESULT",
                RemoteRepairTurnRuntime.CI_STAGE_CALLBACK,
                RemoteRepairTurnRuntime.CI_BRAIN_CALLBACK,
                RemoteRepairTurnRuntime.BRANCH_STAGE_CALLBACK,
                RemoteRepairTurnRuntime.BRANCH_BRAIN_CALLBACK,
                "BRANCH_SYNC_FETCH_RESULT",
                "BRANCH_SYNC_REBASE_RESULT",
                "BRANCH_SYNC_VALIDATION_RESULT",
                "BRANCH_SYNC_PUSH_RESULT",
                ReviewAssignmentTurnOperationHandler.CALLBACK_ROUTE,
                UserRemoteActionOperationHandler.CALLBACK_ROUTE,
                UserRemoteActionOperationHandler.EXTERNAL_CALLBACK_ROUTE,
                ReviewBuildCommentOperationHandler.CALLBACK_ROUTE,
                ReviewBuildCommentOperationHandler.REVIEW_PASS_CALLBACK_ROUTE,
                QualityIssuePublishOperationHandler.CALLBACK_ROUTE,
                RemoteFeedbackEffectOperationHandler
                        .READINESS_ASSISTANCE_CALLBACK_ROUTE,
                MergeOperationHandler.CALLBACK_ROUTE);

        delivery.recoverCommittedDeliveries(7);
        verify(cleanupStore).findPendingFinalizations(7);
    }

    @Test
    void remoteHandlersHaveNoProductionFallbackGateway()
    {
        DevelopmentFlowExecutionConfig config = new DevelopmentFlowExecutionConfig();
        ObjectMapper json = new ObjectMapper();

        assertThatThrownBy(() -> config.v2RemoteFeedbackEffectOperationHandler(
                mock(SqliteRemoteFeedbackEffectOperationStore.class), null, json))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("effects is null");
        assertThatThrownBy(() -> config.v2RemoteMarkReadyOperationHandler(
                mock(SqliteRemoteMarkReadyOperationStore.class), null, json))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("github is null");
        assertThatThrownBy(() -> config.v2UserRemoteActionOperationHandler(
                mock(SqliteUserRemoteActionStore.class),
                mock(SqliteExternalPrActionStore.class),
                null,
                json))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("gateway is null");
        assertThatThrownBy(() -> config.reviewBuildCommentOperationHandler(
                mock(ReviewPublicationOperationStore.class), null, json))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("gateway is null");
        assertThatThrownBy(() -> config.qualityIssuePublishOperationHandler(
                mock(SqliteQualityIssuePublishStore.class), null, json))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("github is null");
    }

    @Test
    void enabledSpringGraphFailsClosedWithoutRemoteGateways()
    {
        v2ContextRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("EffectGateway");
        });
    }

    @Test
    void enabledSpringGraphBuildsAllHandlersWithExplicitRemoteGateways()
    {
        v2ContextRunner()
                .withBean(
                        RemoteFeedbackEffectOperationHandler.EffectGateway.class,
                        () -> mock(
                                RemoteFeedbackEffectOperationHandler.EffectGateway.class))
                .withBean(
                        RemoteMarkReadyOperationHandler.MarkReadyGateway.class,
                        () -> mock(RemoteMarkReadyOperationHandler.MarkReadyGateway.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ExecutionDispatcher.class);
                    verify(context.getBean(V2TaskCreationService.class))
                            .repairExistingTrunkEngineSnapshots();
                    assertThat(context).hasSingleBean(
                            ExecutionPorts.OperationHandlerRegistry.class);
                    assertThat(context).hasBean("v2ResultDelivery");
                    assertThat(context.getBean(
                            ExecutionPorts.ResultDeliveryPort.class))
                            .isInstanceOf(V2UserWaitResultDeliveryPort.class);
                    assertThat(context).hasSingleBean(AgentTurnProviderSession.class);
                    assertThat(context).hasSingleBean(V2TaskControlService.class);
                    assertThat(context).hasSingleBean(PlanningBaseTurnRuntime.class);
                    assertThat(context).hasSingleBean(V2ThreadControlService.class);
                    ExecutionPorts.OperationHandlerRegistry handlers = context.getBean(
                            ExecutionPorts.OperationHandlerRegistry.class);
                    assertThat(handlers.require(
                            RemoteFeedbackEffectOperationHandler.OPERATION_KIND))
                            .isInstanceOf(RemoteFeedbackEffectOperationHandler.class);
                    assertThat(handlers.require(
                            UserRemoteActionOperationHandler.OPERATION_KIND))
                            .isInstanceOf(UserRemoteActionOperationHandler.class);
                    assertThat(handlers.require(
                            UserRemoteActionOperationHandler.EXTERNAL_OPERATION_KIND))
                            .isInstanceOf(UserRemoteActionOperationHandler.class);
                    assertThat(handlers.require(
                            QualityIssuePublishOperationHandler.OPERATION_KIND))
                            .isInstanceOf(QualityIssuePublishOperationHandler.class);
                    assertThat(handlers.require(
                            RemoteMarkReadyOperationHandler.OPERATION_KIND))
                            .isInstanceOf(RemoteMarkReadyOperationHandler.class);
                });
    }

    @Test
    void policyProjectsWorkspaceSettingsAndMigratedTrunkLimits()
    {
        DevelopmentFlowCapacityPolicySource source =
                new DevelopmentFlowCapacityPolicySource(
                        new ObjectMapper(), 4, 4,
                        Map.of(CapacityManager.CapacityLane.VALIDATION, 3));
        CapacityManager.CapacityRequest request = new CapacityManager.CapacityRequest(
                "legacy",
                CapacityManager.WorkflowSource.LEGACY,
                Set.of(CapacityManager.CapacityLane.CLI),
                new CapacityManager.CapacityScope("workspace", "trunk", null, null),
                true,
                false,
                false);

        CapacityManager.CapacityPolicy inherited = source.current(
                request, CapacityManager.CapacityPolicySnapshot.empty());
        assertThat(inherited.workspaceLimit("workspace")).isEqualTo(4);
        assertThat(inherited.trunkLimit("trunk")).isEqualTo(4);

        CapacityManager.CapacityPolicy configured = source.current(
                request,
                new CapacityManager.CapacityPolicySnapshot("""
                        {"sessionCapUsd":100,"dailyCapUsd":500,"pauseAtCap":true,
                         "syncSeconds":60,"brainBudgetChars":8000,"distillMinutes":30,
                         "kbAudiences":["plan","dev","review","ci-fix"],
                         "providers":{},"notifyCi":true,"notifyCompletions":false,
                         "qualityScanEnabled":false,"remoteIssueIntakeEnabled":false,
                         "maxRunningTasks":2}
                        """, 3));
        assertThat(configured.workspaceLimit("workspace")).isEqualTo(2);
        assertThat(configured.trunkLimit("trunk")).isEqualTo(3);
        assertThat(configured.laneLimits())
                .containsEntry(CapacityManager.CapacityLane.CLI, 4)
                .containsEntry(CapacityManager.CapacityLane.API, 6)
                .containsEntry(CapacityManager.CapacityLane.VALIDATION, 3);
    }

    @Test
    void everyNonAgentLaneHasAnExplicitConfigurableLimit()
    {
        v2ContextRunner()
                .withPropertyValues(
                        "bytequay.development-flow.capacity.validation=11",
                        "bytequay.development-flow.capacity.review=12",
                        "bytequay.development-flow.capacity.local-git=13",
                        "bytequay.development-flow.capacity.github=14",
                        "bytequay.development-flow.capacity.remote-observation=15",
                        "bytequay.development-flow.capacity.merge=16",
                        "bytequay.development-flow.capacity.cleanup=17")
                .withBean(
                        RemoteFeedbackEffectOperationHandler.EffectGateway.class,
                        () -> mock(
                                RemoteFeedbackEffectOperationHandler.EffectGateway.class))
                .withBean(
                        RemoteMarkReadyOperationHandler.MarkReadyGateway.class,
                        () -> mock(RemoteMarkReadyOperationHandler.MarkReadyGateway.class))
                .run(context -> {
                    CapacityManager.CapacityPolicy policy = context.getBean(
                                    CapacityManager.CapacityPolicySource.class)
                            .current();
                    assertThat(policy.laneLimits()).containsExactlyInAnyOrderEntriesOf(Map.of(
                            CapacityManager.CapacityLane.CLI, 4,
                            CapacityManager.CapacityLane.API, 6,
                            CapacityManager.CapacityLane.VALIDATION, 11,
                            CapacityManager.CapacityLane.REVIEW, 12,
                            CapacityManager.CapacityLane.LOCAL_GIT, 13,
                            CapacityManager.CapacityLane.GITHUB, 14,
                            CapacityManager.CapacityLane.REMOTE_OBSERVATION, 15,
                            CapacityManager.CapacityLane.MERGE, 16,
                            CapacityManager.CapacityLane.CLEANUP, 17));
                    assertThat(policy.reservedTrunkControl()).containsExactlyInAnyOrderEntriesOf(
                            Map.of(
                                    CapacityManager.CapacityLane.CLI, 1,
                                    CapacityManager.CapacityLane.API, 1));
                });
    }

    private static ApplicationContextRunner contextRunner()
    {
        return new ApplicationContextRunner()
                .withBean(TaskStore.class, () -> mock(TaskStore.class))
                .withBean(ThreadStore.class, () -> mock(ThreadStore.class))
                .withBean(ThreadSettingsStore.class, () -> mock(ThreadSettingsStore.class))
                .withBean(
                        CapacityManager.CapacityLeaseStore.class,
                        () -> mock(CapacityManager.CapacityLeaseStore.class))
                .withBean(
                        WorktreeWriterLeaseManager.Store.class,
                        () -> mock(WorktreeWriterLeaseManager.Store.class))
                .withBean(
                        ExecutionPorts.DispatchTicketStore.class,
                        TestDevelopmentFlowExecutionConfig::ticketStore)
                .withBean(TaskManager.class, () -> mock(TaskManager.class))
                .withBean(TaskManager.Store.class,
                        () -> mock(TaskManager.Store.class))
                .withBean(TaskPolicyRevisionRedriver.class,
                        () -> mock(TaskPolicyRevisionRedriver.class))
                .withBean(RemoteCiRepairRuntimeCoordinator.class,
                        () -> mock(RemoteCiRepairRuntimeCoordinator.class))
                .withBean(TaskCommandExecutor.class,
                        () -> mock(TaskCommandExecutor.class))
                .withBean(V2TaskCreationService.class,
                        () -> mock(V2TaskCreationService.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(V2UserWaitStore.class,
                        () -> mock(V2UserWaitStore.class))
                .withBean(TrunkManager.class, () -> mock(TrunkManager.class))
                .withBean(TrunkManager.Store.class,
                        () -> mock(TrunkManager.Store.class))
                .withBean(V2TrunkPurge.class, () -> mock(V2TrunkPurge.class))
                .withBean(SqlitePlanningBaseTurnStore.class,
                        () -> mock(SqlitePlanningBaseTurnStore.class))
                .withBean(ThreadTurnHandoff.class,
                        () -> mock(ThreadTurnHandoff.class))
                .withBean(WorkspaceRepositoryResolver.class,
                        () -> mock(WorkspaceRepositoryResolver.class))
                .withBean(WatchedRepoStore.class,
                        () -> mock(WatchedRepoStore.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ThreadTurnProjection.class,
                        () -> mock(ThreadTurnProjection.class))
                .withBean(ThreadEngineOverrides.class,
                        () -> mock(ThreadEngineOverrides.class))
                .withBean(RoleRegistry.class, () -> mock(RoleRegistry.class))
                .withBean(SessionKnowledgeProvider.class,
                        () -> mock(SessionKnowledgeProvider.class))
                .withBean(SqliteReviewAssignmentTurnStore.class,
                        () -> mock(SqliteReviewAssignmentTurnStore.class))
                .withBean(SqliteReadinessAssistanceStore.class,
                        () -> mock(SqliteReadinessAssistanceStore.class))
                .withBean(SqliteUserRemoteActionStore.class,
                        () -> mock(SqliteUserRemoteActionStore.class))
                .withBean(SqliteExternalPrActionStore.class,
                        () -> mock(SqliteExternalPrActionStore.class))
                .withBean(SqliteReviewBuildCommentStore.class,
                        () -> mock(SqliteReviewBuildCommentStore.class))
                .withBean(SqliteReviewPassPublicationStore.class,
                        () -> mock(SqliteReviewPassPublicationStore.class))
                .withBean(ReviewPublicationOperationStore.class,
                        () -> mock(ReviewPublicationOperationStore.class))
                .withBean(GitHubReviewBuildCommentGateway.class,
                        () -> mock(GitHubReviewBuildCommentGateway.class))
                .withBean(PRService.class, () -> mock(PRService.class))
                .withBean(InvestigationReviewService.class,
                        () -> mock(InvestigationReviewService.class))
                .withBean(ReviewProviderEndpoints.class,
                        () -> mock(ReviewProviderEndpoints.class));
    }

    private static ExecutionPorts.DispatchTicketStore ticketStore()
    {
        ExecutionPorts.DispatchTicketStore tickets = mock(
                ExecutionPorts.DispatchTicketStore.class);
        when(tickets.findExpiredClaims(any(), anyInt())).thenReturn(List.of());
        when(tickets.findExpiredDeliveryClaims(any(), anyInt()))
                .thenReturn(List.of());
        when(tickets.findEligiblePage(any(), any(), anyInt()))
                .thenReturn(new ExecutionPorts.TicketScanPage(List.of(), null));
        return tickets;
    }

    private static ApplicationContextRunner v2ContextRunner()
    {
        SqliteCleanupOperationStore cleanup = mock(SqliteCleanupOperationStore.class);
        when(cleanup.findPendingFinalizations(100)).thenReturn(List.of());
        ExecutionPorts.DispatchWakeStore wakes = mock(
                ExecutionPorts.DispatchWakeStore.class);
        when(wakes.claimAvailable(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());

        return contextRunner()
                .withUserConfiguration(DevelopmentFlowExecutionConfig.class)
                .withBean(ReasoningEffortService.class,
                        () -> mock(ReasoningEffortService.class))
                .withBean(PlanRuntimeCoordinator.class,
                        () -> mock(PlanRuntimeCoordinator.class))
                .withBean(LocalDevelopmentRuntimeCoordinator.class,
                        () -> mock(LocalDevelopmentRuntimeCoordinator.class))
                .withBean(RemoteFeedbackRuntimeCoordinator.class,
                        () -> mock(RemoteFeedbackRuntimeCoordinator.class))
                .withBean(RemoteDevelopmentRuntimeCoordinator.class,
                        () -> mock(RemoteDevelopmentRuntimeCoordinator.class))
                .withBean(RemoteObservationRuntimeCoordinator.class,
                        () -> mock(RemoteObservationRuntimeCoordinator.class))
                .withBean(BranchSyncRuntimeCoordinator.class,
                        () -> mock(BranchSyncRuntimeCoordinator.class))
                .withBean(RemoteRepairTurnRuntime.class,
                        () -> mock(RemoteRepairTurnRuntime.class))
                .withBean(LocalDevelopmentStageManager.class,
                        () -> mock(LocalDevelopmentStageManager.class))
                .withBean(RemoteDevelopmentStageManager.class,
                        () -> mock(RemoteDevelopmentStageManager.class))
                .withBean(SqliteTaskOutcomeSummaryStore.class,
                        () -> mock(SqliteTaskOutcomeSummaryStore.class))
                .withBean(TaskOutcomeSummaryRuntime.class,
                        () -> mock(TaskOutcomeSummaryRuntime.class))
                .withBean(TaskBrainConversationRuntime.class,
                        () -> mock(TaskBrainConversationRuntime.class))
                .withBean(WorktreeService.class,
                        () -> mock(WorktreeService.class))
                .withBean(LocalToRemoteHandoff.class,
                        () -> mock(LocalToRemoteHandoff.class))
                .withBean(SqlitePublishResultStore.class,
                        () -> mock(SqlitePublishResultStore.class))
                .withBean(SqliteCleanupOperationStore.class, () -> cleanup)
                .withBean(CleanupCompletionHandoff.class,
                        () -> mock(CleanupCompletionHandoff.class))
                .withBean(CredentialService.class,
                        () -> mock(CredentialService.class))
                .withBean(Ds4LifecycleService.class,
                        () -> mock(Ds4LifecycleService.class))
                .withBean(TurnRunner.class, () -> mock(TurnRunner.class))
                .withBean(SqliteProvisionTaskOperationStore.class,
                        () -> mock(SqliteProvisionTaskOperationStore.class))
                .withBean(GitRunnerProvisioningGit.class,
                        () -> mock(GitRunnerProvisioningGit.class))
                .withBean(PullRequestRepository.class,
                        () -> mock(PullRequestRepository.class))
                .withBean(PatResolver.class,
                        () -> mock(PatResolver.class))
                .withBean(SqliteAgentTurnOperationStore.class,
                        () -> mock(SqliteAgentTurnOperationStore.class))
                .withBean(SqliteThreadTurnOperationStore.class,
                        () -> mock(SqliteThreadTurnOperationStore.class))
                .withBean(ActiveAgentContextRegistry.class,
                        () -> mock(ActiveAgentContextRegistry.class))
                .withBean(ToolExposurePolicy.class,
                        () -> mock(ToolExposurePolicy.class))
                .withBean(SqliteLocalDevelopmentRuntimeStore.class,
                        () -> mock(SqliteLocalDevelopmentRuntimeStore.class))
                .withBean(SqliteStageSteeringStore.class,
                        () -> mock(SqliteStageSteeringStore.class))
                .withBean(ValidationCheck.class,
                        () -> mock(ValidationCheck.class))
                .withBean(CodeFingerprints.class,
                        () -> mock(CodeFingerprints.class))
                .withBean(GitRunner.class, () -> mock(GitRunner.class))
                .withBean(ManualPrValidationOperationHandler.class,
                        () -> mock(ManualPrValidationOperationHandler.class))
                .withBean(ManualPrValidationResultDeliveryPort.class,
                        () -> mock(ManualPrValidationResultDeliveryPort.class))
                .withBean(TaskReviewSnapshotOperationHandler.class,
                        () -> mock(TaskReviewSnapshotOperationHandler.class))
                .withBean(TaskReviewSnapshotResultDeliveryPort.class,
                        () -> mock(TaskReviewSnapshotResultDeliveryPort.class))
                .withBean(TaskReviewRoundSnapshotOperationHandler.class,
                        () -> mock(TaskReviewRoundSnapshotOperationHandler.class))
                .withBean(TaskReviewRoundSnapshotResultDeliveryPort.class,
                        () -> mock(TaskReviewRoundSnapshotResultDeliveryPort.class))
                .withBean(ReviewSessionSnapshotOperationHandler.class,
                        () -> mock(ReviewSessionSnapshotOperationHandler.class))
                .withBean(ReviewSessionSnapshotResultDeliveryPort.class,
                        () -> mock(ReviewSessionSnapshotResultDeliveryPort.class))
                .withBean(SqlitePublishOperationStore.class,
                        () -> mock(SqlitePublishOperationStore.class))
                .withBean(GitHubPublishEffects.class,
                        () -> mock(GitHubPublishEffects.class))
                .withBean(SqliteCleanupEffects.class,
                        () -> mock(SqliteCleanupEffects.class))
                .withBean(CleanupQuiescenceHandoff.class,
                        () -> mock(CleanupQuiescenceHandoff.class))
                .withBean(SqliteRemoteFeedbackLoopStore.class,
                        () -> mock(SqliteRemoteFeedbackLoopStore.class))
                .withBean(SqliteRemoteFeedbackEffectOperationStore.class,
                        () -> mock(SqliteRemoteFeedbackEffectOperationStore.class))
                .withBean(SqliteRemoteMarkReadyOperationStore.class,
                        () -> mock(SqliteRemoteMarkReadyOperationStore.class))
                .withBean(GitHubUserRemoteActionGateway.class,
                        () -> mock(GitHubUserRemoteActionGateway.class))
                .withBean(SqliteQualityIssuePublishStore.class,
                        () -> mock(SqliteQualityIssuePublishStore.class))
                .withBean(QualityIssuePublishOperationHandler.Gateway.class,
                        () -> mock(QualityIssuePublishOperationHandler.Gateway.class))
                .withBean(V2QualityIssuePublishRuntime.class,
                        () -> mock(V2QualityIssuePublishRuntime.class))
                .withBean(SqliteRemoteRuntimeStore.class,
                        () -> mock(SqliteRemoteRuntimeStore.class))
                .withBean(GitHubRemoteObserver.class,
                        () -> mock(GitHubRemoteObserver.class))
                .withBean(GitHubRemoteEffects.class,
                        () -> mock(GitHubRemoteEffects.class))
                .withBean(SqliteMergeOperationStore.class,
                        () -> mock(SqliteMergeOperationStore.class))
                .withBean(GitHubMergeEffects.class,
                        () -> mock(GitHubMergeEffects.class))
                .withBean(ExecutionPorts.DispatchWakeStore.class, () -> wakes)
                .withBean(ExecutionPorts.ExecutionEvidencePort.class,
                        () -> mock(ExecutionPorts.ExecutionEvidencePort.class));
    }

    private static Method methodReturning(Class<?> returnType)
    {
        return Arrays.stream(DevelopmentFlowExecutionConfig.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == returnType)
                .findFirst()
                .orElseThrow();
    }
}
