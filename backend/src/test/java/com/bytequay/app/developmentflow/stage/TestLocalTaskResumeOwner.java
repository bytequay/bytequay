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

import com.bytequay.app.developmentflow.stage.persistence.SqliteStageResumeRearmStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageResumeRearmStore.Intent;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalTaskResumeOwner
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @Test
    void resumesParkedBaseSyncOnlyAfterLocalReviewIsMaterialized()
    {
        SqliteStageResumeRearmStore store =
                mock(SqliteStageResumeRearmStore.class);
        TaskManager tasks = mock(TaskManager.class);
        LocalPublishBaseSyncRuntimeCoordinator baseSync =
                mock(LocalPublishBaseSyncRuntimeCoordinator.class);
        Intent intent = intent("ACTIVE");
        when(store.pending(StageKind.LOCAL_DEVELOPMENT, 32))
                .thenReturn(List.of(intent));
        when(baseSync.resumePausedInCommand(
                "handoff-1", "task-1", "stage-1", 1, 1, NOW))
                .thenReturn(Optional.empty());
        LocalTaskResumeOwner owner = new LocalTaskResumeOwner(
                store, new TaskCommandExecutor(new NoopTransactions()),
                tasks, baseSync);

        owner.maintain(NOW);

        InOrder ordered = inOrder(store, baseSync);
        ordered.verify(store).materializePassiveWait(
                intent, StageKind.LOCAL_DEVELOPMENT,
                StageCheckpoint.LOCAL_REVIEW, NOW);
        ordered.verify(baseSync).resumePausedInCommand(
                "handoff-1", "task-1", "stage-1", 1, 1, NOW);
    }

    @Test
    void doesNotResumeBaseSyncUntilTheTaskIsActive()
    {
        SqliteStageResumeRearmStore store =
                mock(SqliteStageResumeRearmStore.class);
        LocalPublishBaseSyncRuntimeCoordinator baseSync =
                mock(LocalPublishBaseSyncRuntimeCoordinator.class);
        when(store.pending(StageKind.LOCAL_DEVELOPMENT, 32))
                .thenReturn(List.of(intent("RESUMING")));
        LocalTaskResumeOwner owner = new LocalTaskResumeOwner(
                store, new TaskCommandExecutor(new NoopTransactions()),
                mock(TaskManager.class), baseSync);

        owner.maintain(NOW);

        verify(store, never()).materializePassiveWait(
                any(), any(), any(), any());
        verify(baseSync, never()).resumePausedInCommand(
                anyString(), anyString(), anyString(), anyLong(), anyLong(),
                any());
    }

    private static Intent intent(String lifecycle)
    {
        return new Intent(
                "handoff-1", "owner-proof-1", "task-resume",
                "task-1", 1, 8, "stage-1", StageKind.LOCAL_DEVELOPMENT,
                1, 4, StageCheckpoint.LOCAL_REVIEW, "reconciliation-1",
                "fingerprint", "head", "base", lifecycle, 9);
    }

    private static final class NoopTransactions
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
