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

import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.LocalContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalDevelopmentSteeringBaseSyncGuard
{
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");

    @Test
    void localOwnerRechecksLiveBaseSyncBeforeWritingSteeringTurn()
    {
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new NoopTransactions());
        SqliteStageSteeringStore steering = mock(SqliteStageSteeringStore.class);
        LocalDevelopmentRuntimeCoordinator runtime =
                new LocalDevelopmentRuntimeCoordinator(
                        commands, mock(TaskManager.class),
                        mock(LocalDevelopmentStageManager.class),
                        mock(SqliteLocalDevelopmentRuntimeStore.class),
                        mock(PRService.class), new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC), 53123);
        runtime.setSteeringStore(steering);
        Request request = request();
        when(steering.requireLocalContext(request, 7))
                .thenReturn(context());
        when(steering.hasLiveLocalPublishBaseSync(request)).thenReturn(true);

        assertThatThrownBy(() -> commands.execute(
                "task-1", () -> runtime.admitSteeringInCommand(request, 7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot race active publish base sync");

        verify(steering, never()).insertLocalTurn(any());
    }

    private static Request request()
    {
        return new Request(
                "request-1", "command-1", "task-1", 1,
                "stage-1", StageKind.LOCAL_DEVELOPMENT, 1, 7,
                StageCheckpoint.LOCAL_REVIEW,
                V2StageSteeringControl.Mode.APPEND, "Change the review",
                "a".repeat(64), null, "PENDING", null, null, null,
                "user", NOW);
    }

    private static LocalContext context()
    {
        return new LocalContext(
                "task-1", "trunk-1", "workspace-1", 1,
                "stage-1", 1, 7, StageCheckpoint.LOCAL_REVIEW,
                "fingerprint", "head", "base", "/tmp/task-1",
                "{}", "openai", "model", "dev");
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
