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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestLocalDevelopmentStageManager
{
    @Test
    void startsOneExactPublishBaseSyncTurnAndReplaysIt()
    {
        StageManager.State review = new StageManager.State(
                "local-stage", "task-1", StageKind.LOCAL_DEVELOPMENT,
                3, 7, StageCheckpoint.LOCAL_REVIEW, null, null);
        MemoryStore store = new MemoryStore(new StageManager.OwnerState(
                "task-1", TaskLifecycle.ACTIVE, 2, "local-stage", review));
        TaskCommandExecutor commands = commands();
        LocalDevelopmentStageManager local =
                new LocalDevelopmentStageManager(commands, store);
        StageManager.Command command = new StageManager.Command(
                "start-base-sync", "runtime", "task-1", 2,
                "local-stage", 3, 7);
        ResultFence fence = new ResultFence(
                2, "local-stage", 3, "base-sync-turn-operation", 1,
                "rebased-fingerprint", "rebased-head", "target-base");

        CommandResult<StageManager.State> applied = commands.execute(
                "task-1", () -> local.startPublishBaseSyncInCommand(
                        command, fence, "base-sync-episode"));
        CommandResult<StageManager.State> duplicate = commands.execute(
                "task-1", () -> local.startPublishBaseSyncInCommand(
                        command, fence, "base-sync-episode"));

        assertThat(applied.disposition()).isEqualTo(CommandResult.Disposition.APPLIED);
        assertThat(applied.state().checkpoint()).isEqualTo(StageCheckpoint.IMPLEMENTING);
        assertThat(applied.state().pendingResult()).isEqualTo(fence);
        assertThat(duplicate.disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(duplicate.state()).isEqualTo(applied.state());
        assertThat(store.receipt).isEqualTo(new StageManager.CommandReceipt(
                "task-1", applied.state(), "START_LOCAL_BASE_SYNC", "runtime",
                2L, 3L, 7L, StageCheckpoint.LOCAL_REVIEW, fence,
                "base-sync-episode", CommandResult.Disposition.APPLIED));
    }

    private static TaskCommandExecutor commands()
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        return new TaskCommandExecutor(new DataSourceTransactionManager(dataSource));
    }

    private static final class MemoryStore
            implements StageManager.Store
    {
        private StageManager.OwnerState owner;
        private StageManager.CommandReceipt receipt;

        private MemoryStore(StageManager.OwnerState owner)
        {
            this.owner = owner;
        }

        @Override
        public Optional<StageManager.OwnerState> findOwner(String taskId, String stageId)
        {
            return Optional.of(owner);
        }

        @Override
        public Optional<StageManager.CommandReceipt> findCommandResult(
                String taskId, String stageId, String commandId)
        {
            return Optional.ofNullable(receipt);
        }

        @Override
        public StageManager.State commit(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State expected,
                StageManager.State updated)
        {
            receipt = new StageManager.CommandReceipt(
                    updated.taskId(), updated, cause, actor,
                    expectedTaskEpoch, expectedStageGeneration,
                    expectedStageVersion, sourceCheckpoint, subjectFence,
                    proofId, CommandResult.Disposition.APPLIED);
            owner = new StageManager.OwnerState(
                    owner.taskId(), owner.taskLifecycle(), owner.taskEpoch(),
                    owner.currentStageId(), updated);
            return updated;
        }

        @Override
        public StageManager.State create(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State state)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public StageManager.State recordSuperseded(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State current)
        {
            throw new UnsupportedOperationException();
        }
    }
}
