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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestLegacyExecutionRetirement
{
    private static final Path MAIN = Path.of("src/main");
    private static final Path SERVICES = MAIN.resolve(
            "java/com/bytequay/app/service");

    private static final List<String> DIRECT_ONLY_LEGACY_CALLBACKS = List.of(
            "review/BrainReviewServiceImpl.java",
            "threads/TaskPhaseMachine.java",
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
    void productionStorageRejectsFreshLegacyTasks()
            throws IOException
    {
        String migration = Files.readString(MAIN.resolve(
                "resources/db/migration/V308__baseline.sql"));
        assertThat(migration)
                .contains("BEFORE INSERT ON tasks")
                .contains("NEW.workflow_version <> 'V2'")
                .contains("LEGACY Task creation is retired");
    }

    @Test
    void legacyLifecycleOwnersCannotRejoinAutomaticSpringExecution()
            throws IOException
    {
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
                        "KIND_REVIEW_COMPATIBILITY_HEADER")
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
                        "SqliteValidationPassStore",
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
