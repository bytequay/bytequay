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

import com.bytequay.app.developmentflow.compatibility.DevelopmentFlowCanaryRoute;
import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.RetiredSagaGate;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLegacyExecutionRetirement
{
    private static final Path MAIN = Path.of("src/main");
    private static final Path SERVICES = MAIN.resolve(
            "java/com/bytequay/app/service");

    private static final List<String> DETACHED_LEGACY_OWNERS = List.of(
            "stage/StageLifecycle.java",
            "threads/TaskLifecycleDriver.java",
            "threads/TaskPrePushDriver.java",
            "threads/AutomationCoordinator.java",
            "threads/CiFixRunExecutor.java",
            "checks/ValidationCancellationReconciler.java",
            "stage/ReviewStageCloser.java",
            "threads/AutoApproveGateListener.java",
            "review/BranchGuardJob.java",
            "threads/TaskCompletionAnnouncer.java",
            "threads/TaskSchedulerConflictBridge.java",
            "threads/ThreadStartupReconciler.java",
            "threads/PlanningBaseRefresher.java",
            "IdleThreadArchiver.java",
            "signal/ThreadSignalRecorder.java",
            "checks/RoundValidationListener.java");

    private static final List<String> DIRECT_ONLY_LEGACY_CALLBACKS = List.of(
            "checks/ValidationClaimService.java",
            "localpr/TaskPushSaga.java",
            "review/RoundGateSaga.java",
            "review/BrainReviewServiceImpl.java",
            "threads/TaskRuntimeStopReconciler.java",
            "threads/TaskPhaseMachine.java",
            "threads/TaskTerminalSealer.java",
            "stage/PlanStageService.java",
            "stage/IterationService.java",
            "stage/StageBudgetService.java",
            "review/BranchGuardServiceImpl.java",
            "localpr/PRPublishService.java",
            "threads/TaskService.java",
            "brain/BrainServiceImpl.java");

    @Test
    void retiredCoordinatorsSwitchesAndTrunkQueueToolsCannotReturn()
            throws IOException
    {
        String production = productionText();
        assertThat(production).doesNotContain(
                "class AgentScheduler",
                "LegacyCapacityBridge",
                "LegacyCapacityLeaseMaintainer",
                "LegacySagaCapacity",
                "LegacyTaskScopeResolver",
                "TaskRuntimeProjector",
                "LegacyReviewAdmission",
                "launchLegacy(",
                "runWithSchedulerCapacity(",
                "bytequay.development-flow.v2-dispatch-enabled",
                "bytequay.development-flow.v2-workspace-allow-list",
                "\"queue_task\"",
                "\"reorder_queue\"",
                "\"drop_queued_task\"");
    }

    @Test
    void permanentRouteAndRetiredSchedulerAreFailClosed()
            throws IOException
    {
        DevelopmentFlowCanaryRoute route = new DevelopmentFlowCanaryRoute();
        assertThat(route.routesNewTaskToV2("workspace")).isTrue();
        assertThat(route.routesNewTaskToV2(" ")).isFalse();
        assertThat(route.snapshot().v2Only()).isTrue();

        Path scheduler = MAIN.resolve(
                "java/com/bytequay/app/service/threads/RetiredThreadTurnScheduler.java");
        String contents = Files.readString(scheduler);
        assertThat(contents)
                .contains("implements ThreadTurnScheduler")
                .contains("LEGACY turn execution is retired")
                .doesNotContain(
                        "Executors.",
                        "ExecutorService",
                        "Thread.ofVirtual",
                        "startVirtualThread",
                        "@Scheduled");
    }

    @Test
    void taskCommandsCannotStartRawPostCommitWorkers()
            throws IOException
    {
        String source = Files.readString(SERVICES.resolve(
                "threads/TaskCommandExecutor.java"));

        assertThat(source)
                .contains(
                        "raw post-commit callbacks are retired",
                        "persist a DispatchTicket")
                .doesNotContain(
                        "Thread.startVirtualThread",
                        "Thread.ofVirtual",
                        "Executors.",
                        "ExecutorService");
    }

    @Test
    void retiredSagaGateRejectsInsteadOfSilentlyDeferring()
    {
        RetiredSagaGate gate = new RetiredSagaGate();

        assertThatThrownBy(() -> gate.tryAcquire(
                "legacy-task", "legacy-operation",
                Set.of(CapacityManager.CapacityLane.GITHUB)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("LEGACY saga execution is retired");
    }

    @Test
    void productionStorageRejectsFreshLegacyTasks()
            throws IOException
    {
        String migration = Files.readString(MAIN.resolve(
                "resources/db/migration/V277__retire_legacy_task_creation.sql"));
        assertThat(migration)
                .contains("BEFORE INSERT ON tasks")
                .contains("NEW.workflow_version <> 'V2'")
                .contains("LEGACY Task creation is retired");
    }

    @Test
    void legacyLifecycleOwnersCannotRejoinAutomaticSpringExecution()
            throws IOException
    {
        for (String relative : DETACHED_LEGACY_OWNERS) {
            String source = Files.readString(SERVICES.resolve(relative));
            assertThat(source)
                    .as(relative)
                    .doesNotContain(
                            "@Component",
                            "@Service",
                            "@Scheduled",
                            "@Async",
                            "@EventListener",
                            "@TransactionalEventListener");
        }
        for (String relative : DIRECT_ONLY_LEGACY_CALLBACKS) {
            String source = Files.readString(SERVICES.resolve(relative));
            assertThat(source)
                    .as(relative)
                    .doesNotContain(
                            "@Scheduled",
                            "@Async",
                            "@EventListener",
                            "@TransactionalEventListener");
        }
    }

    @Test
    void typedReviewStartupCannotRecoverHistoricalRounds()
            throws IOException
    {
        String source = Files.readString(SERVICES.resolve(
                "review/InvestigationReviewService.java"));

        assertThat(source)
                .contains("typedReviewTurns.incompleteRoundIds()")
                .doesNotContain("for (ReviewRoundRow round : store.liveRounds())");
    }

    @Test
    void agentRunAndTaskPhaseMutationAuthoritiesArePhysicallyRetired()
            throws IOException
    {
        String agentRuns = Files.readString(SERVICES.resolve(
                "runs/AgentRunServiceImpl.java"));
        assertThat(agentRuns)
                .contains(
                        "createReviewCompatibilityHeader(",
                        "KIND_REVIEW_COMPATIBILITY_HEADER",
                        "throw retired()")
                .doesNotContain(
                        "StageStateMachine",
                        "TaskCommandExecutor",
                        "store.transition",
                        "store.update");

        String phases = Files.readString(SERVICES.resolve(
                "threads/TaskPhaseMachine.java"));
        assertThat(phases)
                .contains("TaskPhaseMachine is retired")
                .doesNotContain(
                        "TaskStore",
                        "ThreadTurnStore",
                        "ValidationPassStore",
                        "ApplicationEventPublisher");
    }

    private static String productionText()
            throws IOException
    {
        List<Path> files;
        try (var paths = Files.walk(MAIN)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        StringBuilder text = new StringBuilder();
        for (Path file : files) {
            text.append(Files.readString(file)).append('\n');
        }
        return text.toString();
    }
}
